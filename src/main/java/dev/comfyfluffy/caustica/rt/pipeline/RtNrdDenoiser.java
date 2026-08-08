package dev.comfyfluffy.caustica.rt.pipeline;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import dev.comfyfluffy.caustica.nrd.NrdLibrary;
import dev.comfyfluffy.caustica.nrd.NrdRuntime;
import dev.comfyfluffy.caustica.rt.accel.RtImage;

import org.joml.Matrix4fc;
import org.lwjgl.vulkan.VK10;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * NRD (REBLUR_DIFFUSE_SPECULAR) denoiser backend for the RT renderer (EXPERIMENTAL). Denoises the
 * per-lobe radiance signals the tracer emits ({@code gNrdDiff}/{@code gNrdSpec}, YCoCg-packed with
 * REBLUR-normalized hit distances) at render resolution; {@code RtNrdCombinePipeline} then decodes
 * and sums the two outputs into the radiance image the upscale stage consumes.
 *
 * <p>Settings contract with the shim (mirrors NRD's CommonSettings): non-jittered column-major
 * matrices, jitter in UV units, motion-vector scale converting the renderer's render-pixel MVs to
 * UV space, and the renderer's frame counter as NRD's frame index.
 */
public final class RtNrdDenoiser {
    public static final RtNrdDenoiser INSTANCE = new RtNrdDenoiser();

    public static boolean enabled() {
        return CausticaConfig.Rt.Nrd.ENABLED.value() && NrdRuntime.platformSupported();
    }

    private boolean failed;
    private boolean hasPrev;
    private final float[] prevViewToClip = new float[16];
    private final float[] prevWorldToView = new float[16];
    private float prevJitterX;
    private float prevJitterY;

    private RtNrdDenoiser() {
    }

    /**
     * Record one NRD denoise into {@code cmd}: consumes the trace's per-lobe signals + guides and
     * writes the denoised outputs. Returns false (caller falls back to the undenoised image) when
     * the runtime is unavailable or the call fails.
     */
    public boolean denoise(long cmd, int renderWidth, int renderHeight,
                           RtImage motion, RtImage normalRoughness, RtImage viewZ,
                           RtImage diffIn, RtImage specIn, RtImage diffOut, RtImage specOut,
                           Matrix4fc viewToClip, Matrix4fc worldToView,
                           float jitterPixelsX, float jitterPixelsY, int frameIndex) {
        if (failed || !enabled()) {
            return false;
        }
        if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device)) {
            return false;
        }
        NrdLibrary lib = NrdRuntime.INSTANCE.acquire(device, renderWidth, renderHeight);
        if (lib == null) {
            return false;
        }
        try {
            lib.newFrame();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment v2c = arena.allocate(ValueLayout.JAVA_FLOAT, 16);
                MemorySegment v2cPrev = arena.allocate(ValueLayout.JAVA_FLOAT, 16);
                MemorySegment w2v = arena.allocate(ValueLayout.JAVA_FLOAT, 16);
                MemorySegment w2vPrev = arena.allocate(ValueLayout.JAVA_FLOAT, 16);
                // JOML's get(float[]) is column-major, exactly NRD's "vector is a column" layout.
                viewToClip.get(v2c.asByteBuffer().asFloatBuffer());
                worldToView.get(w2v.asByteBuffer().asFloatBuffer());
                if (hasPrev) {
                    prevViewToClip(v2cPrev);
                    prevWorldToView(w2vPrev);
                } else {
                    // First frame after (re)creation: "previous" equals current, so NRD does not
                    // reproject history that does not exist yet.
                    viewToClip.get(v2cPrev.asByteBuffer().asFloatBuffer());
                    worldToView.get(w2vPrev.asByteBuffer().asFloatBuffer());
                }
                // Jitter in UV units (NRD: sampleUv = pixelUv + cameraJitter); MV scale converts the
                // renderer's render-pixel MVs to the UV space NRD reprojects with (same pixel->UV
                // lesson as the FSR integration).
                float jitterUvX = jitterPixelsX / renderWidth;
                float jitterUvY = jitterPixelsY / renderHeight;
                int rc = lib.setSettings(v2c, v2cPrev, w2v, w2vPrev,
                        jitterUvX, jitterUvY, hasPrev ? prevJitterX : jitterUvX, hasPrev ? prevJitterY : jitterUvY,
                        1.0f / renderWidth, 1.0f / renderHeight,
                        frameIndex, hasPrev ? 0 : 1);
                if (rc != 0) {
                    throw new IllegalStateException("nrdshim_set_settings failed: " + rc + " last=" + lib.lastResult());
                }
            }
            prevJitterX = jitterPixelsX / renderWidth;
            prevJitterY = jitterPixelsY / renderHeight;
            viewToClip.get(prevViewToClip);
            worldToView.get(prevWorldToView);
            hasPrev = true;

            int rc = lib.denoise(cmd,
                    motion.image, VK10.VK_FORMAT_R16G16_SFLOAT,
                    normalRoughness.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    viewZ.image, VK10.VK_FORMAT_R32_SFLOAT,
                    diffIn.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    specIn.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    diffOut.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    specOut.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT);
            if (rc != 0) {
                throw new IllegalStateException("nrdshim_denoise failed: " + rc + " last=" + lib.lastResult());
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("NRD denoise failed; RT composite continues without it", t);
            return false;
        }
    }

    private void prevViewToClip(MemorySegment dst) {
        for (int i = 0; i < 16; i++) {
            dst.setAtIndex(ValueLayout.JAVA_FLOAT, i, prevViewToClip[i]);
        }
    }

    private void prevWorldToView(MemorySegment dst) {
        for (int i = 0; i < 16; i++) {
            dst.setAtIndex(ValueLayout.JAVA_FLOAT, i, prevWorldToView[i]);
        }
    }

    public void destroy() {
        NrdRuntime.INSTANCE.shutdown();
        failed = false;
        hasPrev = false;
    }
}
