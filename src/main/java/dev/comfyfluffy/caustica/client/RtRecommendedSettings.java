package dev.comfyfluffy.caustica.client;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.ngx.NgxLibrary;
import dev.comfyfluffy.caustica.ngx.NgxRuntime;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * Finds out what this machine supports, asks {@link RtRecommendedProfile} for the settings and applies them.
 *
 * <p>Two ways in: the "Apply Recommended Settings" button, and a brand-new config file, which the first
 * world applies by itself ({@link CausticaConfig.Rt.Recommended#PENDING}). The automatic one waits a few
 * seconds into the world so that DLSS Ray Reconstruction has brought NGX up and the driver itself has said
 * whether Ray Reconstruction and Frame Generation run here; the button, which can be pressed on the title
 * screen before NGX is up, falls back on the GPU's name for those two, like the Frame Generation toggle.
 */
public final class RtRecommendedSettings {
    /** Ticks in a world before the automatic apply looks (about 5 s). */
    private static final int SETTLE_TICKS = 100;
    /** On an NVIDIA GPU, how long to wait for NGX before deciding without it (about 30 s). */
    private static final int NGX_WAIT_TICKS = 600;
    private static final long TOAST_ID_MILLIS = 8000L;

    private static int worldTicks;

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
        int[] output = outputSize(minecraft);
        return new RtRecommendedProfile.Hardware(rayReconstruction, frameGeneration, RtDeviceBringup.reflexEnabled(),
                RtDeviceBringup.gpuSmCount(), RtRecommendedProfile.looksLikeLaptop(RtDeviceBringup.gpuName()),
                output[0], output[1]);
    }

    /**
     * The size the game is likely to be played at: the larger of the window and the monitor it is on, so a
     * small window on the first launch does not pick settings that are too heavy once it is maximised.
     */
    private static int[] outputSize(Minecraft minecraft) {
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

    /** Detects, applies and saves; returns what was applied. */
    public static RtRecommendedProfile.Profile applyNow(Minecraft minecraft, String reason) {
        RtRecommendedProfile.Hardware hardware = detect(minecraft);
        RtRecommendedProfile.Profile profile = RtRecommendedProfile.recommend(hardware);
        apply(profile);
        CausticaConfig.Rt.Recommended.PENDING.set(false);
        CausticaConfig.save();
        CausticaMod.LOGGER.info("Recommended settings applied ({}): GPU '{}', SMs={}, laptop={}, output {}x{}, "
                        + "rayReconstruction={}, dlssFrameGeneration={}, reflex={} -> {}",
                reason, RtDeviceBringup.gpuName(), hardware.smCount(), hardware.laptop(), hardware.outputWidth(),
                hardware.outputHeight(), hardware.rayReconstruction(), hardware.dlssFrameGeneration(),
                hardware.reflex(), profile.describe());
        return profile;
    }

    /** Sets the values the way the option widgets do: each of these settings is read live every frame. */
    static void apply(RtRecommendedProfile.Profile profile) {
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
        CausticaConfig.Rt.Fg.ENABLED.set(profile.frameGeneration());
        if (profile.frameGeneration()) {
            CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT.set(1);
        }
        CausticaConfig.Rt.Reflex.ENABLED.set(profile.reflex());
        CausticaConfig.Rt.Sharc.ENABLED.set(profile.sharc());
        CausticaConfig.Rt.Composite.FOG_ENABLED.set(profile.fog());
        CausticaConfig.Rt.Composite.CLOUD_STYLE.set(profile.volumetricClouds() ? "volumetric" : "classic");
    }

    /**
     * The automatic apply for a brand-new config. Call once per client tick; does nothing unless the
     * config was created this launch and the game is in a ray-traced world.
     */
    public static void tick(Minecraft minecraft) {
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
        applyNow(minecraft, "first run");
        SystemToast.add(minecraft.gui.toastManager(), new SystemToast.SystemToastId(TOAST_ID_MILLIS),
                Component.translatable("caustica.options.rt.recommended.toast.title"),
                Component.translatable("caustica.options.rt.recommended.toast.message"));
    }
}
