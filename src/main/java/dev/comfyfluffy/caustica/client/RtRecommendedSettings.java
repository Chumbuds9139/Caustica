package dev.comfyfluffy.caustica.client;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.ngx.NgxLibrary;
import dev.comfyfluffy.caustica.ngx.NgxRuntime;
import dev.comfyfluffy.caustica.rt.RtCalibration;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.lang.management.ManagementFactory;
import java.util.Locale;

/**
 * Sets up the recommended settings once: an estimate from the hardware, then about a minute of measured
 * play that settles the upscaler mode. Nothing is watched or changed after that minute.
 *
 * <p>Two ways in: the "Apply Recommended Settings" button, and a brand-new config file, which the first
 * world starts by itself ({@link CausticaConfig.Rt.Recommended#PENDING}) a few seconds in, once DLSS Ray
 * Reconstruction has brought NGX up and the driver has said what runs here; it only starts by itself
 * where the driver confirmed Ray Reconstruction. Either way:
 * <ol>
 *   <li>a notice says the measurement has started, and the estimate is applied, except frame generation
 *       (on a new install the DLSS-G feature is then created once, at the end, at its final size);</li>
 *   <li>{@link RtCalibration} measures ordinary play, skipping anything that passes by itself;</li>
 *   <li>the upscaler mode is settled from the measurement, frame generation is set, and a notice says
 *       what was chosen. The estimate stays when no clean minute came together within
 *       {@value #TIMEOUT_TICKS} ticks of play, or when the window was much smaller than the screen; a GPU
 *       the TDR guard never let reach the configured settings gets the lightest mode. If the player picks
 *       a DLSS mode during the minute, the measurement stops and their mode stays.</li>
 * </ol>
 * The result line in the log also reports the CPU, memory and video memory, and when the measurement
 * shows the Java heap short, a second notice suggests a size: Caustica cannot set it, the launcher does.
 */
public final class RtRecommendedSettings {
    /** Ticks in a world before the automatic start looks (about 5 s). */
    private static final int SETTLE_TICKS = 100;
    /** On an NVIDIA GPU, how long to wait for NGX before deciding without it (about 30 s). */
    private static final int NGX_WAIT_TICKS = 600;
    /** In-world ticks the measurement may take before the estimate is kept (5 minutes). */
    static final int TIMEOUT_TICKS = 6000;
    private static final long TOAST_MILLIS = 8000L;

    private static int worldTicks;
    /** Set while a measurement is running: what the estimate was built from and what it chose. */
    private static RtRecommendedProfile.Hardware runningHardware;
    private static RtRecommendedProfile.Profile runningProfile;
    private static String runningReason;
    private static int runningTicks;
    /** The upscaler mode and window size the running measurement is taken at; a change starts it over. */
    private static int runningQuality;
    private static int runningWidth;
    private static int runningHeight;

    private RtRecommendedSettings() {
    }

    /** What the picker needs to know, from the device, NGX and the screen. */
    public static RtRecommendedProfile.Hardware detect(Minecraft minecraft) {
        boolean rayReconstruction;
        boolean frameGeneration;
        NgxLibrary lib = NgxRuntime.INSTANCE.library();
        if (lib != null) {
            // NGX is up: the driver's own answer.
            rayReconstruction = lib.dlssdAvailable();
            frameGeneration = rayReconstruction && lib.hasDlssg() && lib.dlssgAvailable();
        } else if (!RtDeviceBringup.isNvidia() || NgxRuntime.INSTANCE.hasFailed()) {
            rayReconstruction = false;
            frameGeneration = false;
        } else {
            rayReconstruction = RtDeviceBringup.looksLikeRtx();
            frameGeneration = rayReconstruction && RtDeviceBringup.looksLikeRtxFrameGenerationSeries();
        }
        int[] output = targetSize(minecraft);
        return new RtRecommendedProfile.Hardware(rayReconstruction, frameGeneration, RtDeviceBringup.reflexEnabled(),
                RtDeviceBringup.gpuSmCount(), RtRecommendedProfile.looksLikeLaptop(RtDeviceBringup.gpuName()),
                output[0], output[1]);
    }

    /**
     * The size the game is likely to be played at: the larger of the window and the monitor it is on, so a
     * small window on the first launch does not pick settings that are too heavy once it is maximised.
     */
    private static int[] targetSize(Minecraft minecraft) {
        Window window = minecraft.getWindow();
        int width = Math.max(window.getWidth(), 1);
        int height = Math.max(window.getHeight(), 1);
        try {
            Monitor monitor = window.findBestMonitor();
            VideoMode mode = monitor != null ? monitor.currentMode() : null;
            if (mode != null && (long) mode.getWidth() * mode.getHeight() > (long) width * height) {
                width = mode.getWidth();
                height = mode.getHeight();
            }
        } catch (Throwable ignored) {
            // The window's own size is a fine answer.
        }
        return new int[] {width, height};
    }

    /** Whether a measurement is running. */
    public static boolean measuring() {
        return runningProfile != null;
    }

    /**
     * Applies the estimate (all but frame generation), starts the minute of measurement and says so.
     * The button calls this; a brand-new config calls it from {@link #tick}.
     */
    public static void begin(Minecraft minecraft, String reason) {
        begin(minecraft, detect(minecraft), reason);
    }

    private static void begin(Minecraft minecraft, RtRecommendedProfile.Hardware hardware, String reason) {
        RtRecommendedProfile.Profile profile = RtRecommendedProfile.recommend(hardware);
        applyAllButFrameGeneration(profile);
        CausticaConfig.save();
        runningHardware = hardware;
        runningProfile = profile;
        runningReason = reason;
        runningTicks = 0;
        rememberMeasuredSetup(minecraft);
        RtCalibration.INSTANCE.arm();
        toast(minecraft, Component.translatable("caustica.options.rt.recommended.measuring.title"),
                Component.translatable("caustica.options.rt.recommended.measuring.message"));
        CausticaMod.LOGGER.info("Recommended settings ({}): estimate applied, measuring about a minute of play: GPU '{}', "
                        + "SMs={}, laptop={}, target output {}x{}, rayReconstruction={}, dlssFrameGeneration={}, "
                        + "reflex={} -> {}",
                reason, RtDeviceBringup.gpuName(), hardware.smCount(), hardware.laptop(), hardware.outputWidth(),
                hardware.outputHeight(), hardware.rayReconstruction(), hardware.dlssFrameGeneration(),
                hardware.reflex(), profile.describe());
    }

    /** Sets the values the way the option widgets do: each of these settings is read live every frame. */
    static void applyAllButFrameGeneration(RtRecommendedProfile.Profile profile) {
        if (profile.useDlss()) {
            RtUpscalerSupport.applyUpscalerMode(RtUpscalerSupport.MODE_DLSS);
            CausticaConfig.Rt.Composite.DENOISER.set(true);
        }
        if (profile.dlssQuality() != RtRecommendedProfile.UNCHANGED) {
            CausticaConfig.Rt.DlssRr.QUALITY.set(profile.dlssQuality());
        }
        if (profile.dlssPreset() != RtRecommendedProfile.UNCHANGED) {
            CausticaConfig.Rt.DlssRr.PRESET.set(profile.dlssPreset());
        }
        // Only ever turned on: what the player switched on stays on, and what the profile does not cover
        // for this GPU stays as the player set it.
        if (profile.reflex()) {
            CausticaConfig.Rt.Reflex.ENABLED.set(true);
        }
        if (profile.sharc()) {
            CausticaConfig.Rt.Sharc.ENABLED.set(true);
        }
        if (profile.fog()) {
            CausticaConfig.Rt.Composite.FOG_ENABLED.set(true);
        }
        if (profile.volumetricClouds()) {
            CausticaConfig.Rt.Composite.CLOUD_STYLE.set("volumetric");
        }
    }

    /**
     * Call once per client tick: starts the measurement for a brand-new config, and finishes a running one
     * when it has its minute or has run out of time.
     */
    public static void tick(Minecraft minecraft) {
        if (measuring()) {
            tickMeasurement(minecraft);
            return;
        }
        if (!CausticaConfig.Rt.Recommended.PENDING.value()) {
            return;
        }
        if (minecraft.level == null || RtContext.currentOrNull() == null) {
            worldTicks = 0;
            return;
        }
        worldTicks++;
        if (worldTicks < SETTLE_TICKS) {
            return;
        }
        boolean ngxSettled = !RtDeviceBringup.isNvidia() || NgxRuntime.INSTANCE.isInitialized()
                || NgxRuntime.INSTANCE.hasFailed();
        if (!ngxSettled && worldTicks < NGX_WAIT_TICKS) {
            return;
        }
        RtRecommendedProfile.Hardware hardware = detect(minecraft);
        if (NgxRuntime.INSTANCE.library() == null || !hardware.rayReconstruction()) {
            // Only machines where the driver itself confirmed Ray Reconstruction get it unasked; the
            // profile is untested elsewhere, so there it waits for the button.
            CausticaConfig.Rt.Recommended.PENDING.set(false);
            CausticaConfig.save();
            CausticaMod.LOGGER.info("Recommended settings not applied automatically: DLSS Ray Reconstruction is "
                    + "not confirmed on this machine (the button in the ray-tracing settings still applies them)");
            return;
        }
        begin(minecraft, hardware, "first run");
    }

    private static void rememberMeasuredSetup(Minecraft minecraft) {
        runningQuality = CausticaConfig.Rt.DlssRr.QUALITY.value();
        runningWidth = minecraft.getWindow().getWidth();
        runningHeight = minecraft.getWindow().getHeight();
    }

    private static void tickMeasurement(Minecraft minecraft) {
        RtCalibration.Result result = RtCalibration.INSTANCE.result();
        if (result != null) {
            finish(minecraft, result, Outcome.MEASURED);
            return;
        }
        if (CausticaConfig.Rt.DlssRr.QUALITY.value() != runningQuality) {
            // The player picked a DLSS mode during the measurement: theirs stands, nothing more is changed.
            RtCalibration.INSTANCE.disarm();
            finish(minecraft, null, Outcome.PLAYER_CHOSE);
            return;
        }
        Window window = minecraft.getWindow();
        if (window.getWidth() != runningWidth || window.getHeight() != runningHeight) {
            // The frames so far were taken at another size: start over at this one (the time-box goes on).
            rememberMeasuredSetup(minecraft);
            RtCalibration.INSTANCE.arm();
            CausticaMod.LOGGER.info("Recommended settings: the window size changed, measuring again");
        }
        // Only time in play counts towards the time-box, not time in a menu or paused.
        if (minecraft.level != null && RtContext.currentOrNull() != null && minecraft.gui.screen() == null
                && !minecraft.isPaused()) {
            runningTicks++;
        }
        if (runningTicks >= TIMEOUT_TICKS) {
            RtCalibration.INSTANCE.disarm();
            finish(minecraft, null, Outcome.TIMED_OUT);
        }
    }

    private enum Outcome { MEASURED, TIMED_OUT, PLAYER_CHOSE }

    /** Settles the upscaler mode from the measurement (or keeps it), sets frame generation, says so. */
    private static void finish(Minecraft minecraft, RtCalibration.Result result, Outcome outcome) {
        RtRecommendedProfile.Hardware hardware = runningHardware;
        RtRecommendedProfile.Profile profile = runningProfile;
        String reason = runningReason;
        runningHardware = null;
        runningProfile = null;
        runningReason = null;

        int measuredQuality = runningQuality;
        int quality = CausticaConfig.Rt.DlssRr.QUALITY.value();
        // Frame generation only where the driver, now that NGX is up, still says it runs (the button may
        // have been pressed on the title screen, before NGX was up, on the GPU's name alone).
        NgxLibrary lib = NgxRuntime.INSTANCE.library();
        boolean frameGeneration = profile.frameGeneration() && (lib != null
                ? lib.hasDlssg() && lib.dlssgAvailable() : !NgxRuntime.INSTANCE.hasFailed());
        boolean fgForBudget = frameGeneration || CausticaConfig.Rt.Fg.ENABLED.value();
        String decision;
        Component message;
        RtRecommendedProfile.Measurement measurement = result == null ? null
                : new RtRecommendedProfile.Measurement(measuredQuality, runningWidth, runningHeight,
                        result.frameMs(), result.traceShare());
        boolean slowGpu = outcome == Outcome.TIMED_OUT && RtCalibration.INSTANCE.heldByGovernor();
        if (outcome == Outcome.PLAYER_CHOSE) {
            decision = "the player chose the DLSS mode during the measurement; kept it";
            message = Component.translatable("caustica.options.rt.recommended.kept.message");
        } else if (slowGpu && profile.useDlss()) {
            // The TDR guard never let this GPU trace at the configured settings: as light as it goes.
            quality = RtRecommendedProfile.lightestQuality(hardware.outputHeight());
            decision = "the GPU never reached the configured samples and bounces; lightest mode";
            message = Component.translatable("caustica.options.rt.recommended.slow.message",
                    Component.translatable("caustica.options.rt.dlssQuality." + quality));
        } else if (measurement == null) {
            decision = "no clean minute of play in time; kept the estimate";
            message = Component.translatable("caustica.options.rt.recommended.estimated.message");
        } else if (!profile.useDlss()) {
            decision = "measured; upscaler left as it was";
            message = Component.translatable("caustica.options.rt.recommended.doneNoDlss.message",
                    String.format(Locale.ROOT, "%.0f", result.frameMs()));
        } else if (!RtRecommendedProfile.measuredNearTarget(measurement, hardware.outputWidth(),
                hardware.outputHeight())) {
            decision = "measured in a window much smaller than the screen; kept the estimate";
            message = Component.translatable("caustica.options.rt.recommended.estimated.message");
        } else {
            quality = RtRecommendedProfile.calibratedQuality(measurement, hardware.outputWidth(),
                    hardware.outputHeight(), fgForBudget);
            decision = "measured";
            message = Component.translatable("caustica.options.rt.recommended.done.message",
                    Component.translatable("caustica.options.rt.dlssQuality." + quality),
                    String.format(Locale.ROOT, "%.0f", result.frameMs()));
        }
        if (profile.useDlss()) {
            CausticaConfig.Rt.DlssRr.QUALITY.set(quality);
        }
        if (frameGeneration) {
            CausticaConfig.Rt.Fg.ENABLED.set(true);
        }
        CausticaConfig.Rt.Recommended.PENDING.set(false);
        CausticaConfig.save();

        long physical = physicalMemoryBytes();
        long heapMax = Runtime.getRuntime().maxMemory();
        int heapAdvice = result == null ? 0 : RtRecommendedProfile.suggestedHeapGib(result.gcShare(),
                result.heapAfterGcShare(), heapMax, physical);
        CausticaMod.LOGGER.info("Recommended settings ({}): {}. GPU '{}', SMs={}, videoMemory={} MiB, cpuThreads={}, "
                        + "memory={} MiB, javaHeapMax={} MiB; measured at {} {}x{} for target {}x{}: {}; skipped "
                        + "frames: {} -> dlssQuality={}, frameGeneration={}{}",
                reason, decision, RtDeviceBringup.gpuName(), RtDeviceBringup.gpuSmCount(),
                RtDeviceBringup.gpuDeviceLocalBytes() >> 20, Runtime.getRuntime().availableProcessors(),
                physical >> 20, heapMax >> 20, RtRecommendedProfile.qualityName(measuredQuality), runningWidth,
                runningHeight, hardware.outputWidth(), hardware.outputHeight(),
                result == null ? "no result" : String.format(Locale.ROOT,
                        "%d frames, GPU %.1f ms (75th percentile, BLAS builds excluded, trace passes %.0f%%), "
                                + "frame interval %.1f ms, GC pauses %.1f%%, heap after GC %s",
                        result.samples(), result.frameMs(), result.traceShare() * 100.0, result.intervalMs(),
                        result.gcShare() * 100.0, Double.isNaN(result.heapAfterGcShare()) ? "unknown"
                                : String.format(Locale.ROOT, "%.0f%%", result.heapAfterGcShare() * 100.0)),
                result != null ? result.skipped() : RtCalibration.INSTANCE.skippedSummary(),
                profile.useDlss() ? RtRecommendedProfile.qualityName(quality) : "unchanged",
                CausticaConfig.Rt.Fg.ENABLED.value(),
                heapAdvice > 0 ? ", suggest a Java heap of " + heapAdvice + " GiB" : "");

        toast(minecraft, Component.translatable("caustica.options.rt.recommended.done.title"), message);
        if (heapAdvice > 0) {
            toast(minecraft, Component.translatable("caustica.options.rt.recommended.memory.title"),
                    Component.translatable("caustica.options.rt.recommended.memory.message",
                            Integer.toString(heapAdvice)));
        }
    }

    private static long physicalMemoryBytes() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                return os.getTotalMemorySize();
            }
        } catch (Throwable ignored) {
            // Unknown: no memory advice.
        }
        return 0L;
    }

    private static void toast(Minecraft minecraft, Component title, Component message) {
        SystemToast.add(minecraft.gui.toastManager(), new SystemToast.SystemToastId(TOAST_MILLIS), title, message);
    }
}
