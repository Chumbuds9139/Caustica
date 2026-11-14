package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Keeps the indirect trace off the hit-object path while Shader Execution Reordering is at its most
 * dangerous, and for good once it has misbehaved in this session. The SER raygen carries the ordinary
 * TraceRay path as well (a bounce past {@code reorder.bounces} takes it), and that path draws the same
 * image, so holding SER back only costs the reorder's speed-up for a while.
 *
 * <ul>
 * <li>For {@link #SETTLE_NANOS} after the world changes (joining, a server hop, a dimension change).
 *     Six of the eight device losses logged with reordering on happened within 30 s of one, while those
 *     30 s windows held under a seventh of the play time.</li>
 * <li>For the rest of the session once a reordered trace has had a hit refused: an index out of range,
 *     or a hit object that described a different hit after the reorder than before it. Several losses
 *     followed such a refusal within seconds, and the second kind cannot happen while SER works.</li>
 * </ul>
 *
 * Render thread only.
 */
public final class RtSerGuard {
    private static final long SETTLE_NANOS = 30_000_000_000L;

    private static ClientLevel lastLevel;
    private static int lastDimension;
    private static long settleUntil;
    private static boolean settling;
    private static String trippedBy;

    private RtSerGuard() {
    }

    /**
     * Leading bounces of each path allowed onto the hit-object path this frame: the configured count, or
     * 0 (every bounce traces the ordinary way) while settling after a world change or once tripped.
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
            settleUntil = now + SETTLE_NANOS;
            if (!settling && trippedBy == null) {
                CausticaMod.LOGGER.info("Shader Execution Reordering held back for {} s: the world changed",
                        SETTLE_NANOS / 1_000_000_000L);
            }
            settling = true;
        }
        if (settling && now - settleUntil >= 0L) {
            settling = false;
            if (trippedBy == null) {
                CausticaMod.LOGGER.info("Shader Execution Reordering back on");
            }
        }
        return trippedBy != null || settling ? 0 : configured;
    }

    /** A reordered trace had a hit refused (see {@code RtComposite.reportHitFault}). */
    static void trip(String what) {
        if (trippedBy != null || !RtDeviceBringup.serEnabled()) {
            return;
        }
        trippedBy = what;
        CausticaMod.LOGGER.warn("Shader Execution Reordering switched off for the rest of this session: {}. "
                + "Every bounce now traces the ordinary way, which draws the same image.", what);
    }

    /** Why the hit-object path is off right now, or null while the configured setting applies. */
    static String heldBackReason() {
        if (trippedBy != null) {
            return "switched off for this session (" + trippedBy + ")";
        }
        return settling ? "held back after a world change" : null;
    }

    /** The guard's state, for the device-loss report. */
    static String summary() {
        if (trippedBy != null) {
            return "off for this session (" + trippedBy + ")";
        }
        if (settling) {
            long left = Math.max(0L, settleUntil - System.nanoTime()) / 1_000_000_000L;
            return "held back after a world change, " + left + " s left";
        }
        return "active";
    }
}
