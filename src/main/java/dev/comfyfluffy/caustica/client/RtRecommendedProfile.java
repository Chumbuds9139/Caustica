package dev.comfyfluffy.caustica.client;

import java.util.Locale;

/**
 * Picks the recommended settings for a GPU and screen. Pure logic, no Minecraft or config access, so it
 * is unit-tested; {@link RtRecommendedSettings} gathers the {@link Hardware} and applies the result.
 *
 * <p>The reference is a laptop RTX 4050 (20 SMs) at 1920x1080 with DLSS Ray Reconstruction on
 * Performance (960x540 traced), four bounces, SHaRC, volumetric clouds, fog and DLSS Frame Generation:
 * the F8 test measured about 36-39 ms per ray-traced frame there, with and without Shader Execution
 * Reordering held back, so about 25 real frames a second shown as about 50. That card is at the bottom
 * of the range DLSS Frame Generation runs on (GeForce RTX 40 and 50), so a GPU that has DLSS-G gets the
 * reference's full look, and its upscaler mode is chosen to trace about as many pixels as the reference
 * does per unit of GPU speed. GPU speed is estimated from the streaming-multiprocessor count Vulkan
 * reports on NVIDIA (no table of card names), with desktop cards counted a little faster per SM than
 * laptop ones, and older generations at half that per SM. Only the reference point is measured: the
 * desktop, older-generation and cap factors are estimates.
 *
 * <p>The estimate only sets the starting point. {@link #calibratedQuality} then settles the upscaler mode
 * from a minute of measured play ({@code RtCalibration}), against a GPU-time budget taken from the
 * reference: about 36 ms per ray-traced frame, which its player called good with frame generation on.
 *
 * <p>It only turns things on. Volumetric clouds, fog and frame generation are switched on for a DLSS-G
 * GPU and otherwise left as the player set them: a player who chose volumetric clouds chose them to see
 * them. Shader Execution Reordering is never part of it: every device loss seen so far happened in the one
 * dispatch that uses it, and it does not change the image.
 */
public final class RtRecommendedProfile {
    /** DLSS / FSR / XeSS quality numbering ({@code dlss-rr.quality}). */
    public static final int PERFORMANCE = 0;
    public static final int BALANCED = 1;
    public static final int QUALITY = 2;
    public static final int ULTRA_PERFORMANCE = 3;
    /** {@code dlss-rr.preset} value of preset F, the second-generation transformer model. */
    public static final int PRESET_F = 6;
    /** A setting the profile leaves as it is. */
    public static final int UNCHANGED = -1;

    /** Pixels the reference traces per frame (1920x1080 at DLSS Performance). */
    static final long REFERENCE_RENDER_PIXELS = 960L * 540L;
    /** SMs of the reference GPU (laptop RTX 4050). */
    static final int REFERENCE_SMS = 20;
    /** A desktop card runs its SMs faster than a laptop card (clocks and power). */
    static final double DESKTOP_PER_SM = 1.25;
    /** Ray tracing per SM on a GPU without DLSS-G (RTX 20 and 30) against RTX 40 and 50. */
    static final double OLDER_GENERATION_PER_SM = 0.5;
    static final double MIN_SPEED = 0.5;
    /** Beyond this the estimate is too coarse to push the upscaler further. */
    static final double MAX_SPEED = 4.0;
    /** How far over the pixel budget a mode may go and still be picked. */
    static final double BUDGET_TOLERANCE = 1.1;
    /** Ultra Performance is only offered from this output height up: below it the traced image gets too small. */
    static final int ULTRA_PERFORMANCE_MIN_HEIGHT = 1440;

    private RtRecommendedProfile() {
    }

    /**
     * What the picker knows about the machine.
     *
     * @param rayReconstruction DLSS Ray Reconstruction runs here
     * @param dlssFrameGeneration DLSS Frame Generation runs here (GeForce RTX 40/50)
     * @param reflex NVIDIA Reflex is available
     * @param smCount streaming multiprocessors, or 0 when unknown
     * @param laptop a laptop (mobile) GPU
     * @param outputWidth output width in pixels (the monitor's, or the window's)
     * @param outputHeight output height in pixels
     */
    public record Hardware(boolean rayReconstruction, boolean dlssFrameGeneration, boolean reflex, int smCount,
                           boolean laptop, int outputWidth, int outputHeight) {
    }

    /**
     * The recommended values. {@link #UNCHANGED} in an int field means "leave it as it is"; a boolean
     * field means "turn it on" when true and "leave it as it is" when false.
     *
     * @param useDlss select DLSS Ray Reconstruction as the upscaler
     * @param dlssQuality {@code dlss-rr.quality}, or {@link #UNCHANGED}
     * @param dlssPreset {@code dlss-rr.preset}, or {@link #UNCHANGED}
     */
    public record Profile(boolean useDlss, int dlssQuality, int dlssPreset, boolean frameGeneration,
                          boolean reflex, boolean sharc, boolean fog, boolean volumetricClouds) {
        /** One line for the log. */
        public String describe() {
            return String.format(Locale.ROOT,
                    "upscaler=%s, dlssQuality=%s, dlssPreset=%s, frameGeneration=%s, reflex=%s, sharc=%s, fog=%s, "
                            + "volumetricClouds=%s",
                    useDlss ? "DLSS" : "unchanged", dlssQuality == UNCHANGED ? "unchanged" : qualityName(dlssQuality),
                    dlssPreset == UNCHANGED ? "unchanged" : Integer.toString(dlssPreset), onOrUnchanged(frameGeneration),
                    onOrUnchanged(reflex), onOrUnchanged(sharc), onOrUnchanged(fog), onOrUnchanged(volumetricClouds));
        }

        private static String onOrUnchanged(boolean on) {
            return on ? "on" : "unchanged";
        }
    }

    /**
     * What the one-off measurement found, at the settings it ran with.
     *
     * @param quality      the upscaler mode it ran at
     * @param outputWidth  output width it ran at
     * @param outputHeight output height it ran at
     * @param frameMs      GPU time per ray-traced frame (an upper percentile)
     * @param traceShare   share of that time spent in the trace passes, which grow with traced pixels
     */
    public record Measurement(int quality, int outputWidth, int outputHeight, double frameMs, double traceShare) {
    }

    /**
     * GPU time per ray-traced frame to stay within, with frame generation: the reference's 36-39 ms, which
     * its player called good (about 25 real frames a second, 50 shown).
     */
    static final double BUDGET_MS_WITH_FG = 36.0;
    /** Without frame generation every frame is a real one, so the budget is tighter (30 frames a second). */
    static final double BUDGET_MS_WITHOUT_FG = 30.0;
    /** A mode sharper than the measured one is picked only if it is predicted to fit with this much room. */
    static final double SHARPER_MARGIN = 0.9;
    /**
     * The measurement must have run at no less than this share of the target screen's pixels. Scaling a
     * small window's frame up to a large screen also scales costs that do not grow with pixels, so a
     * measurement in the small window of a first launch would over-predict and pick too light a mode.
     */
    static final double MIN_MEASURED_SHARE = 2.0 / 3.0;

    /** Whether the measurement ran at close enough to the target size to predict it. */
    public static boolean measuredNearTarget(Measurement m, int targetWidth, int targetHeight) {
        double target = (double) Math.max(targetWidth, 1) * Math.max(targetHeight, 1);
        return (double) m.outputWidth() * m.outputHeight() >= target * MIN_MEASURED_SHARE;
    }

    /**
     * The upscaler mode for the target screen, from a measurement. The trace passes are scaled by traced
     * pixels and the rest of the frame by output pixels; the sharpest mode predicted to fit the budget wins,
     * where a mode sharper than the measured one must fit with {@link #SHARPER_MARGIN} to spare, so a
     * measurement that happened to see little sky does not push it too far. A measurement taken well below
     * the target size ({@link #measuredNearTarget}) keeps the mode it ran at.
     */
    public static int calibratedQuality(Measurement m, int targetWidth, int targetHeight, boolean frameGeneration) {
        if (!measuredNearTarget(m, targetWidth, targetHeight)) {
            return m.quality();
        }
        int width = Math.max(targetWidth, 1);
        int height = Math.max(targetHeight, 1);
        double budget = frameGeneration ? BUDGET_MS_WITH_FG : BUDGET_MS_WITHOUT_FG;
        double trace = m.frameMs() * Math.clamp(m.traceShare(), 0.0, 1.0);
        double rest = m.frameMs() - trace;
        double measuredPixels = Math.max(renderPixels(m.quality(), m.outputWidth(), m.outputHeight()), 1L);
        double outputRatio = (double) width * height / Math.max((double) m.outputWidth() * m.outputHeight(), 1.0);
        int[] candidates = candidates(height);
        for (int quality : candidates) {
            double predicted = rest * outputRatio + trace * renderPixels(quality, width, height) / measuredPixels;
            double limit = budget * (rank(quality) > rank(m.quality()) ? SHARPER_MARGIN : BUDGET_TOLERANCE);
            if (predicted <= limit) {
                return quality;
            }
        }
        return candidates[candidates.length - 1];
    }

    /** Sharper modes rank higher. */
    static int rank(int quality) {
        return switch (quality) {
            case QUALITY -> 3;
            case BALANCED -> 2;
            case PERFORMANCE -> 1;
            default -> 0;
        };
    }

    /** The lightest mode the picker uses at this height (for a GPU too slow to measure at all). */
    public static int lightestQuality(int height) {
        int[] candidates = candidates(height);
        return candidates[candidates.length - 1];
    }

    private static int[] candidates(int height) {
        return height >= ULTRA_PERFORMANCE_MIN_HEIGHT
                ? new int[] {QUALITY, BALANCED, PERFORMANCE, ULTRA_PERFORMANCE}
                : new int[] {QUALITY, BALANCED, PERFORMANCE};
    }

    static final double GC_SHARE_ADVICE = 0.05;
    static final double HEAP_AFTER_GC_ADVICE = 0.85;
    static final long GIB = 1024L * 1024L * 1024L;

    /**
     * The Java heap to suggest in whole GiB, or 0 when the measurement shows no shortage. Caustica cannot
     * change the heap itself (the launcher sets it), so this is advice only: half again the current heap,
     * at least 4 GiB, and never more than half the computer's memory.
     */
    public static int suggestedHeapGib(double gcShare, double heapAfterGcShare, long heapMaxBytes,
                                       long physicalBytes) {
        boolean shortOfMemory = gcShare > GC_SHARE_ADVICE || heapAfterGcShare > HEAP_AFTER_GC_ADVICE;
        if (!shortOfMemory || heapMaxBytes <= 0L || physicalBytes <= 0L) {
            return 0;
        }
        long suggested = Math.max(heapMaxBytes + heapMaxBytes / 2, 4L * GIB);
        suggested = Math.min(suggested, physicalBytes / 2);
        int gib = (int) ((suggested + GIB / 2) / GIB);
        return gib * GIB > heapMaxBytes + GIB / 2 ? gib : 0;
    }

    public static Profile recommend(Hardware hw) {
        // DLSS-G means an RTX 40 or 50: at least as fast as the reference, with frame generation to
        // double what is shown, so it gets the reference's whole look. Anywhere else those are left alone.
        boolean full = hw.rayReconstruction() && hw.dlssFrameGeneration();
        return new Profile(
                hw.rayReconstruction(),
                hw.rayReconstruction() ? dlssQuality(hw) : UNCHANGED,
                // Preset F is heavier on RTX 20 and 30, which lack the FP8 units it is built for.
                full ? PRESET_F : UNCHANGED,
                full,
                hw.reflex(),
                // Shorter path tails and much less multi-bounce noise, on any GPU.
                true,
                full,
                full);
    }

    /** GPU speed against the reference, from the SM count; 1 when it is unknown. */
    static double speed(Hardware hw) {
        if (hw.smCount() <= 0) {
            return hw.dlssFrameGeneration() ? 1.0 : MIN_SPEED;
        }
        double speed = (double) hw.smCount() / REFERENCE_SMS;
        if (!hw.laptop()) {
            speed *= DESKTOP_PER_SM;
        }
        if (!hw.dlssFrameGeneration()) {
            speed *= OLDER_GENERATION_PER_SM;
        }
        return Math.clamp(speed, MIN_SPEED, MAX_SPEED);
    }

    /** The best-looking upscaler mode whose traced pixels fit this GPU's share of the reference's budget. */
    static int dlssQuality(Hardware hw) {
        int width = Math.max(hw.outputWidth(), 1);
        int height = Math.max(hw.outputHeight(), 1);
        double budget = REFERENCE_RENDER_PIXELS * speed(hw) * BUDGET_TOLERANCE;
        int[] candidates = candidates(height);
        for (int quality : candidates) {
            if (renderPixels(quality, width, height) <= budget) {
                return quality;
            }
        }
        return candidates[candidates.length - 1];
    }

    /** Traced pixels for an upscaler mode at this output size. */
    static long renderPixels(int quality, int width, int height) {
        double scale = axisScale(quality);
        return Math.round(width * scale) * Math.round(height * scale);
    }

    /** Per-axis render scale of a mode (DLSS's published ratios). */
    static double axisScale(int quality) {
        return switch (quality) {
            case QUALITY -> 2.0 / 3.0;
            case BALANCED -> 0.58;
            case ULTRA_PERFORMANCE -> 1.0 / 3.0;
            default -> 0.5;
        };
    }

    static String qualityName(int quality) {
        return switch (quality) {
            case QUALITY -> "Quality";
            case BALANCED -> "Balanced";
            case ULTRA_PERFORMANCE -> "Ultra Performance";
            case PERFORMANCE -> "Performance";
            default -> Integer.toString(quality);
        };
    }

    /** NVIDIA names its mobile parts "... Laptop GPU" (older ones "Max-Q" or "Mobile"). */
    public static boolean looksLikeLaptop(String gpuName) {
        String name = gpuName == null ? "" : gpuName.toUpperCase(Locale.ROOT);
        return name.contains("LAPTOP") || name.contains("MAX-Q") || name.contains("MOBILE");
    }
}
