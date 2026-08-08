package dev.comfyfluffy.caustica.rt.pipeline;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import dev.comfyfluffy.caustica.nrd.NrdLibrary;
import dev.comfyfluffy.caustica.nrd.NrdRuntime;
import dev.comfyfluffy.caustica.rt.accel.RtImage;

import org.joml.Matrix4f;
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

    // nrd::HitDistanceReconstructionMode::AREA_5X5. The tracer selects ONE lobe per pixel at SPP 1
    // (specular with probability ps clamped to [0.1, 0.9]), so the other lobe's hit distance arrives
    // as 0 = "no data" every frame; REBLUR must reconstruct it from neighbours or its history
    // rejection misfires (the camera-move smearing). AREA_5X5 is the mode whose required probability
    // range [1/16, 15/16] contains our [0.1, 0.9] clamp; AREA_3X3 would need [0.25, 0.75] + Bayer.
    private static final int HIT_DIST_RECONSTRUCTION_AREA_5X5 = 2;
    // Camera moves a handful of blocks per frame at most; a bigger offset jump means the terrain
    // rebase anchor shifted or the player teleported — the world coordinates NRD accumulates against
    // just moved under its feet, so its history must be thrown away that frame.
    private static final float REBASE_JUMP_BLOCKS = 32.0f;

    private boolean failed;
    private boolean hasPrev;
    private boolean reblurSettingsSent;
    private final float[] prevViewToClip = new float[16];
    private final float[] prevWorldToView = new float[16];
    private float prevOffsetX;
    private float prevOffsetY;
    private float prevOffsetZ;

    private RtNrdDenoiser() {
    }

    /**
     * Record one NRD denoise into {@code cmd}: consumes the trace's per-lobe signals + guides and
     * writes the denoised outputs. {@code viewRotation} is the rotation-only view matrix and
     * {@code cameraOffsetX/Y/Z} the camera position in the rebased terrain coordinates the signals
     * live in — NRD derives camera motion from the worldToView translation, so the matrix is built
     * here as rotation x translate(-offset). A rotation-only matrix made NRD compute a zero camera
     * delta, breaking its previous-position reconstruction (the error grows radially from the screen
     * centre — the "giant stable circle with noisy edges" artifact). {@code jitterPixelsX/Y} are the
     * raw sub-pixel jitter values in PIXEL units: NRD validates cameraJitter against [-0.5, 0.5] and
     * does the UV conversion itself. Returns false (caller falls back to the undenoised image) when
     * the runtime is unavailable or the call fails.
     */
    public boolean denoise(long cmd, int renderWidth, int renderHeight,
                           RtImage motion, RtImage normalRoughness, RtImage viewZ,
                           RtImage diffIn, RtImage specIn, RtImage diffOut, RtImage specOut,
                           Matrix4fc viewToClip, Matrix4fc viewRotation,
                           float cameraOffsetX, float cameraOffsetY, float cameraOffsetZ,
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
            if (!reblurSettingsSent) {
                lib.setReblurSettings(HIT_DIST_RECONSTRUCTION_AREA_5X5, 0, 0);
                reblurSettingsSent = true;
            }
            lib.newFrame();

            // worldToView with the camera translation in the rebased terrain space: NRD pulls the
            // camera position out of this matrix (and its prev counterpart) to get the per-frame
            // camera delta its reconstruction needs. The terrain space is static between rebase
            // anchor rebuilds, which is exactly the "static world, moving camera" model NRD assumes.
            Matrix4f worldToView = new Matrix4f(viewRotation)
                    .translate(-cameraOffsetX, -cameraOffsetY, -cameraOffsetZ);
            // NRD's reconstruction assumes a +Z-forward view space matching IN_VIEWZ (positive linear
            // depth: ReconstructViewPosition sets p.z = viewZ). Minecraft's view space is the GL
            // convention (-Z forward), so flip the view-space Z axis; NRD detects the resulting
            // handedness from the matrix itself. IN_VIEWZ stays as-is (it is the +dist value this
            // flipped space expects).
            worldToView = new Matrix4f().scaling(1f, 1f, -1f).mul(worldToView);

            // Rebase anchor shift or teleport: the terrain coordinates NRD accumulates against just
            // jumped; the history is unrecoverable, so clear it this frame and start over.
            boolean jumped = false;
            if (hasPrev) {
                float dx = cameraOffsetX - prevOffsetX;
                float dy = cameraOffsetY - prevOffsetY;
                float dz = cameraOffsetZ - prevOffsetZ;
                jumped = dx * dx + dy * dy + dz * dz > REBASE_JUMP_BLOCKS * REBASE_JUMP_BLOCKS;
            }

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment v2c = arena.allocate(ValueLayout.JAVA_FLOAT, 16);
                MemorySegment v2cPrev = arena.allocate(ValueLayout.JAVA_FLOAT, 16);
                MemorySegment w2v = arena.allocate(ValueLayout.JAVA_FLOAT, 16);
                MemorySegment w2vPrev = arena.allocate(ValueLayout.JAVA_FLOAT, 16);
                // JOML's get(float[]) is column-major, exactly NRD's "vector is a column" layout.
                viewToClip.get(v2c.asByteBuffer().asFloatBuffer());
                worldToView.get(w2v.asByteBuffer().asFloatBuffer());
                if (hasPrev && !jumped) {
                    prevViewToClip(v2cPrev);
                    prevWorldToView(w2vPrev);
                } else {
                    // First frame after (re)creation or a world-coordinate jump: "previous" equals
                    // current, so NRD does not reproject history that is invalid or does not exist.
                    viewToClip.get(v2cPrev.asByteBuffer().asFloatBuffer());
                    worldToView.get(w2vPrev.asByteBuffer().asFloatBuffer());
                }
                // cameraJitter is the raw sub-pixel jitter in PIXEL units (NRD's [-0.5, 0.5]
                // contract); NRD itself converts it when de-jittering the input samples. The MV scale
                // converts the renderer's render-pixel MVs to the UV space NRD reprojects with.
                int rc = lib.setSettings(v2c, v2cPrev, w2v, w2vPrev,
                        jitterPixelsX, jitterPixelsY, jitterPixelsX, jitterPixelsY,
                        1.0f / renderWidth, 1.0f / renderHeight,
                        frameIndex, (hasPrev && !jumped) ? 0 : 1);
                if (rc != 0) {
                    throw new IllegalStateException("nrdshim_set_settings failed: " + rc + " last=" + lib.lastResult());
                }
            }
            prevOffsetX = cameraOffsetX;
            prevOffsetY = cameraOffsetY;
            prevOffsetZ = cameraOffsetZ;
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
        // The shim's static tuning state dies with the unloaded DLL; resend it on the next session.
        reblurSettingsSent = false;
    }
}
