package dev.comfyfluffy.caustica.rt.accel;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor;
import dev.comfyfluffy.caustica.rt.VulkanDiagnostics;
import org.lwjgl.vulkan.VK10;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reuses the scratch buffers acceleration-structure builds need for a moment and then drop.
 *
 * <p>Every build used to create its own scratch buffer and free it afterwards. Scratch must be aligned,
 * which this VMA binding can only promise through a dedicated allocation, so each one was a separate
 * driver allocation whose address range was unmapped again on free: hundreds a second while terrain
 * streams. Here a returned buffer is kept and lent to the next build of a similar size.
 *
 * <p>A returned buffer is not lent again until both GPU timelines have passed the point they had reached
 * when it came back: every frame handed out by then (even one still being recorded) has finished, and
 * every build queued by then has finished or been dropped. A buffer returned at the right time passes
 * that at once. One returned too early can then not end up under two builds at the same time, which an
 * early free followed by a fresh allocation of the same memory could.
 *
 * <p>Sizes are rounded up to quarter steps between powers of two (at most a quarter more than asked),
 * requests above {@link #MAX_POOLED_BYTES} get a buffer of their own as before, and buffers idle past
 * {@link #IDLE_TTL_NANOS} or beyond {@link #IDLE_CAP_BYTES} in total are freed.
 */
public final class RtScratchPool {
    static final long MIN_CLASS_BYTES = 16L << 10;
    static final long MAX_POOLED_BYTES = 64L << 20;
    static final long IDLE_CAP_BYTES = 64L << 20;
    static final long IDLE_TTL_NANOS = 20_000_000_000L;
    private static final long TRIM_INTERVAL_NANOS = 1_000_000_000L;
    private static final long STATS_INTERVAL_NANOS = 300_000_000_000L;

    /** Where the GPU is; the pool's only view of the two timelines. */
    interface Progress {
        long completedGraphics();

        long reservedGraphics();

        long finishedBuild();

        long assignedBuild();
    }

    /** Makes and frees the pool's own buffers. */
    interface Allocator {
        RtBuffer create(long size, String label);

        void free(RtBuffer buffer);
    }

    private record Idle(RtBuffer buffer, long readyGraphics, long readyBuild, long sinceNanos) {
    }

    private static volatile RtScratchPool current;

    private final Allocator allocator;
    private final Progress progress;
    private final long alignment;
    private final TreeMap<Long, ArrayDeque<Idle>> idle = new TreeMap<>(); // size class -> oldest first
    private long idleBytes;
    private int idleCount;
    private boolean closed;
    private long lastTrimNanos;
    private long lastStatsNanos;

    private long leases;
    private long reused;
    private long created;
    private long unpooled;
    private long notReadyYet;
    private long freed;

    RtScratchPool(Allocator allocator, Progress progress, long alignment) {
        this.allocator = allocator;
        this.progress = progress;
        this.alignment = Math.max(1L, alignment);
        long now = System.nanoTime();
        this.lastTrimNanos = now;
        this.lastStatsNanos = now;
    }

    /** The pool for {@code ctx}'s device. Close it once the device is idle, before the allocator goes. */
    public static RtScratchPool create(RtContext ctx) {
        long alignment = ctx.accelerationStructureScratchAlignment();
        Allocator allocator = new Allocator() {
            @Override
            public RtBuffer create(long size, String label) {
                return ctx.createAlignedBuffer(size, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false, label, alignment);
            }

            @Override
            public void free(RtBuffer buffer) {
                buffer.destroy();
            }
        };
        Progress progress = new Progress() {
            private RtGpuExecutor executor() {
                return ctx.gpuExecutor();
            }

            @Override
            public long completedGraphics() {
                return executor().completedGraphicsValue();
            }

            @Override
            public long reservedGraphics() {
                return executor().reservedGraphicsValue();
            }

            @Override
            public long finishedBuild() {
                return executor().finishedBuildValue();
            }

            @Override
            public long assignedBuild() {
                return executor().assignedBuildValue();
            }
        };
        RtScratchPool pool = new RtScratchPool(allocator, progress, alignment);
        current = pool;
        return pool;
    }

    /** The size class a request of {@code bytes} is served from. */
    static long classSize(long bytes) {
        if (bytes <= MIN_CLASS_BYTES) {
            return MIN_CLASS_BYTES;
        }
        long power = Long.highestOneBit(bytes);
        if (power == bytes) {
            return bytes;
        }
        long step = power >> 2;
        return (bytes + step - 1L) / step * step;
    }

    /** Scratch of at least {@code requiredSize} bytes, named {@code label} while lent; give it back with destroy(). */
    public RtBuffer acquire(long requiredSize, String label) {
        long need = Math.max(requiredSize, alignment);
        long size = classSize(need);
        if (size > MAX_POOLED_BYTES) {
            synchronized (this) {
                unpooled++;
            }
            return allocator.create(need, label);
        }
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("build scratch pool is closed");
            }
            leases++;
            RtBuffer ready = takeReady(size);
            if (ready != null) {
                reused++;
                return new RtBuffer(ready, this, label + " [pooled]");
            }
            created++;
        }
        RtBuffer buffer = allocator.create(size, "pooled build scratch " + (size >> 10) + " KiB");
        return new RtBuffer(buffer, this, label + " [pooled]");
    }

    /** The oldest idle buffer of this class both timelines have passed, or null. Caller holds the lock. */
    private RtBuffer takeReady(long size) {
        ArrayDeque<Idle> queue = idle.get(size);
        if (queue == null || queue.isEmpty()) {
            return null;
        }
        long completedGraphics;
        long finishedBuild;
        try {
            completedGraphics = progress.completedGraphics();
            finishedBuild = progress.finishedBuild();
        } catch (RuntimeException e) {
            return null; // cannot tell (the device may be lost): a new buffer is the safe answer
        }
        for (Iterator<Idle> it = queue.iterator(); it.hasNext(); ) {
            Idle entry = it.next();
            if (isReady(entry, completedGraphics, finishedBuild)) {
                it.remove();
                idleBytes -= size;
                idleCount--;
                return entry.buffer;
            }
        }
        notReadyYet++;
        return null;
    }

    private static boolean isReady(Idle entry, long completedGraphics, long finishedBuild) {
        if (completedGraphics == Long.MAX_VALUE) {
            return true; // the executor has shut down after idling the device
        }
        return entry.readyGraphics <= completedGraphics && entry.readyBuild <= finishedBuild;
    }

    /** Called by a lease's destroy(). */
    void release(RtBuffer lease) {
        RtBuffer buffer;
        boolean freeNow;
        synchronized (this) {
            if (!lease.returnLease()) {
                return;
            }
            buffer = lease.pooledBuffer();
            freeNow = closed;
            if (!freeNow) {
                VulkanDiagnostics.registerBuffer(buffer.deviceAddress, buffer.size, buffer.handle,
                        "idle pooled build scratch " + (buffer.size >> 10) + " KiB");
                idle.computeIfAbsent(buffer.size, ignored -> new ArrayDeque<>())
                        .addLast(new Idle(buffer, progress.reservedGraphics(), progress.assignedBuild(),
                                System.nanoTime()));
                idleBytes += buffer.size;
                idleCount++;
            }
        }
        if (freeNow) {
            allocator.free(buffer);
            return;
        }
        trimIfDue();
    }

    private void trimIfDue() {
        List<RtBuffer> doomed = null;
        String stats = null;
        synchronized (this) {
            long now = System.nanoTime();
            if (closed || now - lastTrimNanos < TRIM_INTERVAL_NANOS) {
                return;
            }
            lastTrimNanos = now;
            doomed = trimLocked(now);
            if (now - lastStatsNanos >= STATS_INTERVAL_NANOS) {
                lastStatsNanos = now;
                stats = summaryLocked();
            }
        }
        for (RtBuffer buffer : doomed) {
            allocator.free(buffer);
        }
        if (stats != null) {
            CausticaMod.LOGGER.info("{}", stats);
        }
    }

    /** Ready idle buffers past their time to live, then the oldest ready ones until under the cap. */
    List<RtBuffer> trimLocked(long now) {
        List<RtBuffer> doomed = new ArrayList<>();
        if (idleCount == 0) {
            return doomed;
        }
        long completedGraphics;
        long finishedBuild;
        try {
            completedGraphics = progress.completedGraphics();
            finishedBuild = progress.finishedBuild();
        } catch (RuntimeException e) {
            return doomed;
        }
        for (Map.Entry<Long, ArrayDeque<Idle>> classEntry : idle.entrySet()) {
            for (Iterator<Idle> it = classEntry.getValue().iterator(); it.hasNext(); ) {
                Idle entry = it.next();
                if (now - entry.sinceNanos >= IDLE_TTL_NANOS && isReady(entry, completedGraphics, finishedBuild)) {
                    it.remove();
                    drop(entry, doomed);
                }
            }
        }
        // Over the cap: largest classes first, oldest first within a class.
        for (Iterator<ArrayDeque<Idle>> classes = idle.descendingMap().values().iterator();
             idleBytes > IDLE_CAP_BYTES && classes.hasNext(); ) {
            ArrayDeque<Idle> queue = classes.next();
            for (Iterator<Idle> it = queue.iterator(); idleBytes > IDLE_CAP_BYTES && it.hasNext(); ) {
                Idle entry = it.next();
                if (isReady(entry, completedGraphics, finishedBuild)) {
                    it.remove();
                    drop(entry, doomed);
                }
            }
        }
        return doomed;
    }

    private void drop(Idle entry, List<RtBuffer> doomed) {
        idleBytes -= entry.buffer.size;
        idleCount--;
        freed++;
        doomed.add(entry.buffer);
    }

    /** Free every idle buffer. Only once the device is idle; later returns free their buffer at once. */
    public void close() {
        List<RtBuffer> doomed = new ArrayList<>();
        synchronized (this) {
            closed = true;
            for (ArrayDeque<Idle> queue : idle.values()) {
                for (Idle entry : queue) {
                    doomed.add(entry.buffer);
                }
            }
            idle.clear();
            idleBytes = 0L;
            idleCount = 0;
            if (current == this) {
                current = null;
            }
        }
        for (RtBuffer buffer : doomed) {
            allocator.free(buffer);
        }
    }

    synchronized long idleBytes() {
        return idleBytes;
    }

    synchronized int idleCount() {
        return idleCount;
    }

    private String summaryLocked() {
        return "Build scratch pool: " + leases + " lent (" + reused + " reused, " + created + " new, "
                + notReadyYet + " times the GPU had not yet passed an idle one), " + unpooled
                + " too large to pool, " + freed + " freed; idle now " + idleCount + " ("
                + (idleBytes >> 20) + " MiB)";
    }

    /** One line for the device-loss report, or null when no pool is open. */
    public static String summary() {
        RtScratchPool pool = current;
        if (pool == null) {
            return null;
        }
        synchronized (pool) {
            return pool.summaryLocked();
        }
    }
}
