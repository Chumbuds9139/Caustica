package dev.comfyfluffy.caustica.rt.accel;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.VulkanDiagnostics;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.function.LongSupplier;

/**
 * Every live bottom-level acceleration structure, keyed by the exact device address a TLAS instance
 * names it by, and the last frame whose TLAS referenced each one.
 *
 * <p>The TLAS audit used to ask only whether an instance's address fell inside <i>some</i> live buffer.
 * Buffers are freed and their memory handed out again hundreds of times a second, so a structure
 * destroyed a moment ago usually still passes that test with another object's memory where its nodes
 * were, and the GPU then walks that memory as a tree. Matching the exact address of a live structure
 * leaves no such gap.
 *
 * <p>It also catches the other way to reach a destroyed structure: destroying it while a frame that
 * traverses it is still on the GPU. That frame was audited when its TLAS was written, while the
 * structure was alive; only the destroy can see it. Such a destroy is logged with the call stack that
 * did it.
 */
public final class RtAccelRegistry {
    private static final Object LOCK = new Object();
    private static final Long2ObjectOpenHashMap<Entry> LIVE = new Long2ObjectOpenHashMap<>();

    // Recently destroyed structures, so a dropped instance can be named. Guarded by LOCK.
    private static final int DESTROYED_RING = 1 << 12;
    private static final long[] DESTROYED_ADDRESS = new long[DESTROYED_RING];
    private static final long[] DESTROYED_NANOS = new long[DESTROYED_RING];
    private static final String[] DESTROYED_LABEL = new String[DESTROYED_RING];
    private static long destroyedCount;

    private static final int STACK_TRACES_LOGGED = 8;
    private static final long SUMMARY_INTERVAL_NANOS = 30_000_000_000L;

    // Set by the GPU executor while its graphics timeline exists; null when nothing can be in flight.
    private static volatile LongSupplier completedGraphics;
    private static volatile LongSupplier submittedGraphics;
    private static long completedCache;

    private static int inFlightDestroys;
    private static int inFlightUnloggedSinceSummary;
    private static long lastInFlightSummaryNanos;
    private static String lastInFlightLabel;
    private static long lastInFlightNanos;
    private static int sharedAddressWarnings;
    private static long droppedInstances;
    private static String lastDroppedLabel;
    private static long lastDroppedNanos;

    static final class Entry {
        final long handle;
        final String label;
        final long createdNanos;
        long lastTlasUse; // graphics-timeline value of the newest frame whose TLAS names it; LOCK

        Entry(long handle, String label, long createdNanos) {
            this.handle = handle;
            this.label = label;
            this.createdNanos = createdNanos;
        }
    }

    private RtAccelRegistry() {
    }

    /**
     * Point the registry at the graphics timeline: {@code completed} reads the value the GPU has
     * finished, {@code submitted} the newest frame value handed to the queue. Pass nulls once the device
     * is idle for good, so teardown destroys are not mistaken for early ones.
     */
    public static void trackGraphics(LongSupplier completed, LongSupplier submitted) {
        synchronized (LOCK) {
            completedGraphics = completed;
            submittedGraphics = submitted;
            completedCache = 0L;
        }
    }

    static void created(long address, long handle, String label) {
        if (address == 0L) {
            return;
        }
        Entry fresh = new Entry(handle, label, System.nanoTime());
        Entry previous;
        synchronized (LOCK) {
            previous = LIVE.put(address, fresh);
            if (previous != null && sharedAddressWarnings < STACK_TRACES_LOGGED) {
                sharedAddressWarnings++;
            } else {
                previous = null;
            }
        }
        if (previous != null) {
            CausticaMod.LOGGER.warn("BLAS registry: '{}' was created at 0x{}, where '{}' is still alive; a TLAS "
                            + "naming the address now reaches the new one",
                    label, Long.toHexString(address), previous.label, new Throwable("created here"));
        }
    }

    static void destroyed(long address, long handle) {
        if (address == 0L) {
            return;
        }
        Entry entry;
        long lastUse;
        synchronized (LOCK) {
            entry = LIVE.get(address);
            if (entry == null || entry.handle != handle) {
                return;
            }
            LIVE.remove(address);
            int slot = (int) (destroyedCount++ & (DESTROYED_RING - 1));
            DESTROYED_ADDRESS[slot] = address;
            DESTROYED_NANOS[slot] = System.nanoTime();
            DESTROYED_LABEL[slot] = entry.label;
            lastUse = entry.lastTlasUse;
        }
        if (lastUse != 0L) {
            checkNotInFlight(entry, lastUse);
        }
    }

    private static void checkNotInFlight(Entry entry, long lastUse) {
        LongSupplier completedSource = completedGraphics;
        LongSupplier submittedSource = submittedGraphics;
        if (completedSource == null || submittedSource == null) {
            return;
        }
        long completed;
        long submitted;
        try {
            synchronized (LOCK) {
                if (lastUse <= completedCache) {
                    return;
                }
            }
            completed = completedSource.getAsLong();
            submitted = submittedSource.getAsLong();
        } catch (RuntimeException e) {
            return; // the device may already be lost; the report covers that
        }
        boolean logStack;
        int count;
        int unlogged = 0;
        long now = System.nanoTime();
        synchronized (LOCK) {
            completedCache = Math.max(completedCache, completed);
            if (lastUse <= completed) {
                return;
            }
            count = ++inFlightDestroys;
            lastInFlightLabel = entry.label;
            lastInFlightNanos = now;
            logStack = count <= STACK_TRACES_LOGGED;
            if (!logStack) {
                inFlightUnloggedSinceSummary++;
                if (now - lastInFlightSummaryNanos >= SUMMARY_INTERVAL_NANOS) {
                    lastInFlightSummaryNanos = now;
                    unlogged = inFlightUnloggedSinceSummary;
                    inFlightUnloggedSinceSummary = 0;
                }
            }
        }
        String when = lastUse > submitted
                ? "frame " + lastUse + " named it in its TLAS and has not been submitted yet"
                : "frame " + lastUse + " named it in its TLAS and the GPU has only finished up to frame " + completed;
        if (logStack) {
            CausticaMod.LOGGER.error("BLAS '{}' was destroyed while still in use: {}. The GPU can walk freed "
                            + "memory, which ends in a lost device. ({} so far this session; the first {} are "
                            + "logged with the stack that destroyed them)",
                    entry.label, when, count, STACK_TRACES_LOGGED, new Throwable("destroyed here"));
        } else if (unlogged > 0) {
            CausticaMod.LOGGER.error("BLAS destroyed while still in use: {} more since the last summary, {} this "
                    + "session; latest '{}': {}", unlogged, count, entry.label, when);
        }
    }

    /**
     * Look an instance's BLAS address up and, when it is live, record that frame {@code graphicsUse}
     * references it. Caller holds {@link #lock()}.
     */
    static boolean acceptLocked(long address, long graphicsUse) {
        Entry entry = LIVE.get(address);
        if (entry == null) {
            return false;
        }
        if (graphicsUse > entry.lastTlasUse) {
            entry.lastTlasUse = graphicsUse;
        }
        return true;
    }

    /** Held across one TLAS write so the per-instance lookups take the lock once. */
    static Object lock() {
        return LOCK;
    }

    /** What is known about an address no live structure has, for a log line. Takes the lock itself. */
    static String describeDead(long address) {
        String verdict = null;
        synchronized (LOCK) {
            long now = System.nanoTime();
            long entries = Math.min(destroyedCount, DESTROYED_RING);
            for (long back = 1; back <= entries; back++) {
                int slot = (int) ((destroyedCount - back) & (DESTROYED_RING - 1));
                if (DESTROYED_ADDRESS[slot] == address) {
                    verdict = "'" + DESTROYED_LABEL[slot] + "', destroyed "
                            + (now - DESTROYED_NANOS[slot]) / 1_000_000L + " ms earlier";
                    break;
                }
            }
            if (verdict == null) {
                verdict = "not among the last " + entries + " destroyed structures";
            }
        }
        return verdict + "; that memory now: " + VulkanDiagnostics.describeAddress(address);
    }

    static void noteDropped(int count, String label) {
        synchronized (LOCK) {
            droppedInstances += count;
            lastDroppedLabel = label;
            lastDroppedNanos = System.nanoTime();
        }
    }

    /** How many structures were destroyed while a frame still used them this session. */
    static int inFlightDestroyCount() {
        synchronized (LOCK) {
            return inFlightDestroys;
        }
    }

    /** One line for the device-loss report. */
    public static String summary() {
        synchronized (LOCK) {
            long now = System.nanoTime();
            StringBuilder sb = new StringBuilder("BLAS registry: ").append(LIVE.size()).append(" live, ")
                    .append(destroyedCount).append(" destroyed; destroyed while a frame still used them: ")
                    .append(inFlightDestroys);
            if (lastInFlightLabel != null) {
                sb.append(" (latest '").append(lastInFlightLabel).append("', ")
                        .append((now - lastInFlightNanos) / 1_000_000_000L).append(" s ago)");
            }
            sb.append("; TLAS instances dropped for naming a dead BLAS: ").append(droppedInstances);
            if (lastDroppedLabel != null) {
                sb.append(" (latest ").append(lastDroppedLabel).append(", ")
                        .append((now - lastDroppedNanos) / 1_000_000_000L).append(" s ago)");
            }
            return sb.toString();
        }
    }
}
