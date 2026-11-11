package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;

/**
 * Keeps one frame's GPU work far away from the point where the operating system resets the GPU.
 *
 * <p>Windows declares a GPU hung (a "TDR", seen by the game as {@code VK_ERROR_DEVICE_LOST}) when it
 * cannot get the device's attention for about two seconds. A path tracer's cost is the product of
 * resolution, samples per pixel and path length, so a combination the hardware cannot afford, or video
 * memory running out, turns directly into frames that long. Once that happens the device is gone and
 * nothing in the game can bring it back; the only real protection is never to submit such a frame.
 *
 * <p>The governor therefore owns the two cheapest cost levers, samples per pixel and maximum bounces,
 * and treats the player's settings as an upper bound:
 * <ul>
 *   <li><b>Slow start.</b> Tracing begins at the minimum (1 sample, 2 bounces) and climbs towards the
 *       configured quality one step at a time, and only while the measured frame time predicts that the
 *       next step still fits. An unaffordable setting is thus never submitted at full cost.</li>
 *   <li><b>Back-off.</b> When a GPU-bound frame exceeds the target the load is stepped down; when one
 *       exceeds the panic threshold it drops straight to the minimum.</li>
 * </ul>
 *
 * <p>Only frames the GPU was actually behind on count: the time the render thread spent blocked on an
 * earlier frame's completion ({@link #noteGpuWait}) separates a GPU-bound slow frame from a CPU hitch
 * such as chunk loading, which must not cost image quality.
 *
 * <p>Both levers are plain per-frame values in the tracer's uniform block, so changing them needs no
 * rebuild and cannot stall; the temporal denoiser/upscaler absorbs the changing sample count.
 */
public final class RtLoadGovernor {
    public static final RtLoadGovernor INSTANCE = new RtLoadGovernor();

    static final int MIN_SPP = 1;
    static final int MIN_BOUNCES = 2;
    /** Consecutive comfortable frames required before the load may grow again. */
    static final int STABLE_FRAMES_TO_GROW = 6;
    /** A frame this many times over the target is an emergency, not a trend. */
    static final float PANIC_FACTOR = 3.0f;
    /** A frame counts as GPU-bound when at least this share of it was spent waiting for the GPU. */
    static final float GPU_BOUND_SHARE = 0.25f;
    /** Growth must be predicted to land below this share of the target, leaving headroom for variance. */
    static final float GROW_HEADROOM = 0.6f;
    private static final long LOG_INTERVAL_NANOS = 5_000_000_000L;

    private int spp = MIN_SPP;
    private int bounces = MIN_BOUNCES;
    private int stableFrames;
    private long lastFrameNanos;
    private long gpuWaitNanos;
    private long lastLogNanos;
    private boolean limitedLogged;

    private RtLoadGovernor() {
    }

    /** For tests: an independent instance with the same policy. */
    static RtLoadGovernor createForTest() {
        return new RtLoadGovernor();
    }

    /** Render thread, from the frame-submit wait: time spent blocked on an earlier frame's GPU work. */
    public void noteGpuWait(long nanos) {
        if (nanos > 0L) gpuWaitNanos += nanos;
    }

    /**
     * Render thread, once per ray-traced frame before its uniforms are written. Measures the previous
     * frame and settles the samples/bounces this frame will use.
     */
    public void beginFrame(int userSpp, int userBounces) {
        long now = System.nanoTime();
        long frameNanos = lastFrameNanos == 0L ? 0L : now - lastFrameNanos;
        lastFrameNanos = now;
        long waited = gpuWaitNanos;
        gpuWaitNanos = 0L;
        boolean enabled;
        int targetMs;
        try {
            enabled = CausticaConfig.Rt.Stability.TDR_GUARD.value();
            targetMs = CausticaConfig.Rt.Stability.TDR_GUARD_FRAME_MS.value();
        } catch (Throwable t) {
            enabled = true;
            targetMs = 350;
        }
        int beforeSpp = spp;
        int beforeBounces = bounces;
        update(enabled, frameNanos / 1.0e6f, waited / 1.0e6f, targetMs, userSpp, userBounces);
        logChange(now, beforeSpp, beforeBounces, userSpp, userBounces, frameNanos / 1.0e6f);
    }

    /** Samples per pixel to trace this frame; never above the configured value. */
    public int spp() {
        return spp;
    }

    /** Maximum path length to trace this frame; never above the configured value. */
    public int bounces() {
        return bounces;
    }

    /**
     * The policy itself, free of clocks and configuration.
     *
     * @param frameMs   wall time of the previous frame (0 when unknown, e.g. the first frame)
     * @param gpuWaitMs part of that time the render thread was blocked waiting for the GPU
     */
    void update(boolean enabled, float frameMs, float gpuWaitMs, int targetMs, int userSpp, int userBounces) {
        int maxSpp = Math.max(MIN_SPP, userSpp);
        int maxBounces = Math.max(MIN_BOUNCES, userBounces);
        if (!enabled) {
            spp = maxSpp;
            bounces = maxBounces;
            stableFrames = 0;
            return;
        }
        // The player lowering a setting takes effect at once.
        spp = Math.clamp(spp, MIN_SPP, maxSpp);
        bounces = Math.clamp(bounces, MIN_BOUNCES, maxBounces);

        boolean gpuBound = gpuWaitMs >= frameMs * GPU_BOUND_SHARE && gpuWaitMs > 0.0f;
        if (frameMs > targetMs * PANIC_FACTOR && gpuBound) {
            spp = MIN_SPP;
            bounces = MIN_BOUNCES;
            stableFrames = 0;
            return;
        }
        if (frameMs > targetMs && gpuBound) {
            // Samples first: halving them halves the cost, and the denoiser hides it best.
            if (spp > MIN_SPP) {
                spp = Math.max(MIN_SPP, spp / 2);
            } else if (bounces > MIN_BOUNCES) {
                bounces--;
            }
            stableFrames = 0;
            return;
        }
        if (frameMs <= 0.0f || frameMs > targetMs * 0.5f) {
            // Unknown, or too close to the target to risk more work. A long CPU-bound frame lands here
            // too: it neither costs quality nor counts as evidence that the GPU has room.
            stableFrames = 0;
            return;
        }
        if (spp >= maxSpp && bounces >= maxBounces) {
            return;
        }
        if (++stableFrames < STABLE_FRAMES_TO_GROW) {
            return;
        }
        // Path length first (it restores indirect light), then samples. Cost is modelled as
        // samples x bounces; the step is taken only if that model keeps the frame well inside the target.
        int nextSpp = spp;
        int nextBounces = bounces;
        if (bounces < maxBounces) {
            nextBounces++;
        } else {
            nextSpp++;
        }
        float predicted = frameMs * ((float) nextSpp * nextBounces) / ((float) spp * bounces);
        if (predicted <= targetMs * GROW_HEADROOM) {
            spp = nextSpp;
            bounces = nextBounces;
        }
        stableFrames = 0;
    }

    private void logChange(long now, int beforeSpp, int beforeBounces, int userSpp, int userBounces, float frameMs) {
        boolean reduced = spp < beforeSpp || bounces < beforeBounces;
        boolean limited = spp < Math.max(MIN_SPP, userSpp) || bounces < Math.max(MIN_BOUNCES, userBounces);
        if (reduced && now - lastLogNanos >= LOG_INTERVAL_NANOS) {
            lastLogNanos = now;
            limitedLogged = true;
            CausticaMod.LOGGER.warn(
                    "TDR guard: a GPU-bound frame took {} ms; tracing at {} spp / {} bounces instead of the "
                            + "configured {} / {} until the GPU has headroom again. If this keeps happening, "
                            + "lower the render scale, samples or bounces, or check video memory use.",
                    Math.round(frameMs), spp, bounces, userSpp, userBounces);
        } else if (!limited && limitedLogged) {
            limitedLogged = false;
            CausticaMod.LOGGER.info("TDR guard: back at the configured {} spp / {} bounces", spp, bounces);
        }
    }
}
