package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Keeps Shader Execution Reordering off the indirect trace while it is at its most dangerous. During a
 * hold the indirect pass runs a raygen built without reordering at all (the world pipeline carries it as
 * a third raygen when SER is on), and the SER raygen, should it run, is told to take no bounce onto the
 * hit-object path. Both draw the same image, so a hold only costs the reorder's speed-up for a while.
 *
 * <p>A hold used to be the bounce count alone, which left the SER-built module running with no hit-object
 * call in it; a device loss still came 8 s into such a hold, inside that module. Running the plain raygen
 * instead says which it was: a loss during a hold now names raygen 2, and if one still comes there the
 * cause is not in the reordering module.
 *
 * <ul>
 * <li>For {@link #WORLD_CHANGE_HOLD_NANOS} after the world changes (joining, a server hop, a dimension
 *     change). Six of the eight device losses logged with reordering on happened within 30 s of one,
 *     while those 30 s windows held under a seventh of the play time.</li>
 * <li>For {@link #REFUSAL_HOLD_NANOS} after a reordered trace has had a hit refused: an index out of
 *     range, or a hit object that described a different hit after the reorder than before it. Three of
 *     the four losses that followed such a refusal came within 4 to 21 s of it. The hold is not for the
 *     rest of the session because the reorder is worth about 40% of the indirect pass on the hardware
 *     it was measured on; one loss did come ten minutes after its refusal, which this does not cover.</li>
 * </ul>
 *
 * A hold that is already running is only ever extended, never shortened. Render thread only.
 */
public final class RtSerGuard {
    private static final long WORLD_CHANGE_HOLD_NANOS = 30_000_000_000L;
    private static final long REFUSAL_HOLD_NANOS = 120_000_000_000L;

    private static ClientLevel lastLevel;
    private static int lastDimension;
    private static boolean holding;
    private static long holdUntil;
    private static String holdReason;
    private static int refusalHolds;

    private RtSerGuard() {
    }

    /**
     * Leading bounces of each path allowed onto the hit-object path this frame: the configured count, or
     * 0 (every bounce traces the ordinary way) while a hold is running.
     */
    static int bounces(ClientLevel level, int dimension) {
        int configured = CausticaConfig.Rt.Reorder.BOUNCES.value();
        if (!RtDeviceBringup.serEnabled()) {
            return configured;
        }
        long now = System.nanoTime();
        if (level == null) {
            lastLevel = null;
        } else if (level != lastLevel || dimension != lastDimension) {
            lastLevel = level;
            lastDimension = dimension;
            hold(now, WORLD_CHANGE_HOLD_NANOS, "the world changed");
        }
        if (holding && now - holdUntil >= 0L) {
            holding = false;
            holdReason = null;
            CausticaMod.LOGGER.info("Shader Execution Reordering back on");
        }
        return holding ? 0 : configured;
    }

    /** True while a hold is running: the indirect pass should run the raygen built without reordering. */
    static boolean holding() {
        return holding;
    }

    /** A reordered trace had a hit refused (see {@code RtComposite.reportHitFault}). */
    static void refused(String what) {
        if (!RtDeviceBringup.serEnabled()) {
            return;
        }
        if (!holding || !holdReason.equals(what)) {
            refusalHolds++;
        }
        hold(System.nanoTime(), REFUSAL_HOLD_NANOS, what);
    }

    private static void hold(long now, long nanos, String reason) {
        long until = now + nanos;
        if (holding && until - holdUntil <= 0L) {
            return; // the running hold already lasts at least as long
        }
        boolean wasHolding = holding;
        // Frames still in flight when a refusal starts the hold report theirs a little later; extending
        // for the same reason again needs no log line each time.
        boolean sameReason = wasHolding && reason.equals(holdReason);
        holding = true;
        holdUntil = until;
        holdReason = reason;
        if (sameReason) {
            return;
        }
        if (wasHolding) {
            CausticaMod.LOGGER.info("Shader Execution Reordering hold extended to {} s from now: {}",
                    nanos / 1_000_000_000L, reason);
        } else {
            CausticaMod.LOGGER.info("Shader Execution Reordering held back for {} s: {}. The indirect pass "
                    + "runs the raygen built without reordering meanwhile, which draws the same image.",
                    nanos / 1_000_000_000L, reason);
        }
    }

    /** Why the hit-object path is off right now, or null while the configured setting applies. */
    static String heldBackReason() {
        return holding ? "held back (" + holdReason + ")" : null;
    }

    /** The guard's state, for the device-loss report. */
    static String summary() {
        String refusals = ", holds after a refused hit=" + refusalHolds;
        if (holding) {
            long left = Math.max(0L, holdUntil - System.nanoTime()) / 1_000_000_000L;
            return "held back (" + holdReason + "), " + left + " s left, indirect pass on the raygen built "
                    + "without reordering" + refusals;
        }
        return "active" + refusals;
    }
}
