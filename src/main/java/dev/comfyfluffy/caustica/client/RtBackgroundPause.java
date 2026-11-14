package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtComposite;
import net.minecraft.client.Minecraft;

import java.util.concurrent.locks.LockSupport;

/**
 * Skips whole frames while the game window is out of focus, so no ray-tracing work runs while nobody is
 * looking at it. The game loop itself keeps going: window events are pumped, packets are handled and the
 * game ticks exactly as before (all of that happens in {@code Minecraft.run}/{@code runTick} before
 * {@code renderFrame}); only {@code renderFrame} is left out, and the window keeps showing the last frame
 * it presented. Focus coming back resumes rendering on the next loop iteration.
 *
 * <p>A short grace period keeps a brief focus blip from freezing the picture, and lets the frames in
 * between run vanilla's own unfocused handling (the single-player pause menu opens from
 * {@code renderFrame}).
 */
public final class RtBackgroundPause {
    /** How long the window has to stay unfocused before frames stop. */
    private static final long GRACE_NANOS = 1_000_000_000L;
    /** Loop pacing while paused: well under a tick, so ticks and input stay timely, without spinning. */
    private static final long PAUSED_LOOP_NANOS = 25_000_000L;

    private static long unfocusedSince = -1L;
    private static boolean paused;

    private RtBackgroundPause() {
    }

    /** Decide, once per loop iteration and before anything else in it, whether this iteration renders. */
    public static void update(Minecraft mc) {
        boolean wanted = CausticaConfig.Rt.PAUSE_WHEN_UNFOCUSED.value()
                && RtComposite.enabled()
                && !RtComposite.INSTANCE.hasFailed()
                && mc.level != null
                && !mc.isWindowActive();
        if (!wanted) {
            unfocusedSince = -1L;
            if (paused) {
                paused = false;
                CausticaMod.LOGGER.info("Game window focused again: rendering resumed");
            }
            return;
        }
        long now = System.nanoTime();
        if (unfocusedSince < 0L) {
            unfocusedSince = now;
        }
        if (!paused && now - unfocusedSince >= GRACE_NANOS) {
            paused = true;
            CausticaMod.LOGGER.info("Game window out of focus: rendering paused (pause-when-unfocused)");
        }
    }

    /** Whether this loop iteration skips its frame. */
    public static boolean paused() {
        return paused;
    }

    /** Stands in for the skipped frame so the paused loop does not spin a CPU core. */
    public static void idle() {
        LockSupport.parkNanos(PAUSED_LOOP_NANOS);
    }
}
