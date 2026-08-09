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
 * <p>Matrix conventions, verified against NRD v4.17.3 sources (InstanceImpl + mathlib):
 * Minecraft 26.x view space is already the +Z-forward LEFT-handed space NRD works in, so the
 * rotation x translate(-cameraOffset) worldToView is passed UNMODIFIED — DecomposeProjection
 * detects the handedness from viewToClip and converts RH inputs itself (negating the view Z
 * column/row); pre-flipping Z here made NRD flip a second time, mirroring reconstructed depth
 * about the camera (the "giant circle" of misreprojected history). The projection itself must
 * have Vulkan's NDC-y-flip UNDONE before handing it over: NRD assumes y-up NDC (STYLE_D3D) and
 * applies the top-left-origin flip itself in GetScreenUv. IN_VIEWZ is positive linear distance,
 * IN_MV is previous-minus-current in render pixels (scaled to UV via motionVectorScale).
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
    // Near plane value written into the sanitized depth row of the projection handed to NRD (see
    // denoise): Minecraft's own depth row carries a degenerate effective near (~4.77e-6) that
    // collapsed every NRD world-space scale derived from it; 0.05 is vanilla's real near plane.
    private static final float NRD_PROJECTION_NEAR = 0.05f;

    private boolean failed;
    private boolean hasPrev;
    private boolean reblurSettingsSent;
    private final float[] prevViewToClip = new float[16];
    private final float[] prevWorldToView = new float[16];
    private float prevOffsetX;
    private float prevOffsetY;
    private float prevOffsetZ;
    private int lastWidth;
    private int lastHeight;

    private RtNrdDenoiser() {
    }

    /**
     * Record one NRD denoise into {@code cmd}: consumes the trace's per-lobe signals + guides and
     * writes the denoised outputs. {@code viewRotation} is the rotation-only view matrix and
     * {@code cameraOffsetX/Y/Z} the camera position in the rebased terrain coordinates the signals
     * live in — NRD derives camera motion from the worldToView translation, so the matrix is built
     * here as rotation x translate(-offset). {@code jitterPixelsX/Y} are the raw sub-pixel jitter
     * values in PIXEL units: NRD validates cameraJitter against [-0.5, 0.5] and does the UV
     * conversion itself. {@code projectionChanged} marks FOV/projection jumps (sprint, fly) that
     * warp the reprojection space the temporal accumulation lives in. Returns false (caller falls
     * back to the undenoised image) when the runtime is unavailable or the call fails.
     */
    public boolean denoise(long cmd, int renderWidth, int renderHeight,
                           RtImage motion, RtImage normalRoughness, RtImage viewZ,
                           RtImage diffIn, RtImage specIn, RtImage diffOut, RtImage specOut,
                           RtImage validation,
                           Matrix4fc viewToClip, Matrix4fc viewRotation,
                           float cameraOffsetX, float cameraOffsetY, float cameraOffsetZ,
                           float jitterPixelsX, float jitterPixelsY, int frameIndex,
                           boolean projectionChanged) {
        if (failed || !enabled()) {
            return false;
        }
        boolean validationOn = validation != null && CausticaConfig.Rt.Nrd.VALIDATION.value();
        if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device)) {
            return false;
        }
        NrdLibrary lib = NrdRuntime.INSTANCE.acquire(device, renderWidth, renderHeight);
        if (lib == null) {
            return false;
        }
        try {
            if (!reblurSettingsSent) {
                // REBLUR is the single temporal denoiser on the NRD path (the renderer TAA steps
                // aside when NRD runs — see RtComposite — so REBLUR's own accumulation is the only
                // motion-compensated history before FSR, mirroring how DLSS-RR stays stable by
                // denoising in one coherent pass instead of stacking temporal stages). 60 accumulated
                // frames gives strong convergence at SPP 1; AREA_5X5 reconstructs the lobe the
                // probabilistic single-lobe sample missed this frame.
                //
                // Tuning toward DLSS-RR-like stability, targeting the reported symptoms:
                //  - anti-lag stays at NRD defaults (luminanceSigmaScale 2.0, luminanceSensitivity
                //    3.0): an earlier attempt to make it release history sooner (2.5) reduced
                //    smearing but let too much raw noise through, so the cure was worse than the
                //    disease. Defaults are the better balance for this SPP-1 signal.
                //  - flicker in bright/dark: fireflySuppressorMinRelativeScale 2.0 (default) -> 1.75,
                //    a slightly stronger suppression of sporadic fireflies (the cost is a little bias).
                lib.setReblurSettings(HIT_DIST_RECONSTRUCTION_AREA_5X5, 60, 6,
                        0.0f, 0.0f, 1.75f);
                reblurSettingsSent = true;
            }
            lib.newFrame();

            // Minecraft's Vulkan clip space has NDC y pointing DOWN; NRD assumes y-UP NDC and does
            // the top-left-origin flip itself (GetScreenUv). Feeding the flipped projection made
            // REBLUR reconstruct every view position vertically mirrored, so its reprojection
            // sampled history from the wrong half of the screen. Undo the flip on a private copy —
            // the renderer keeps the original matrix for everything else.
            Matrix4f nrdViewToClip = new Matrix4f(viewToClip);
            nrdViewToClip.m11(-viewToClip.m11());
            // Sanitize the depth row too. NRD derives ALL of its world-space scales from it
            // (unproject = C0*a23/a32 in DecomposeProjection -> gUnproject -> frustumSize ->
            // disocclusion thresholds, hit-distance factors, blur clamps). Minecraft's float-Z
            // reverse-Z projection carries a degenerate effective near (a23 ~ 4.77e-6), which made
            // every threshold ~40x tighter than NRD's design point: any reprojection error above a
            // microscopic fraction of depth failed the disocclusion test, so temporal history only
            // survived near the screen centre (minimum error) — the visible "circle", with everything
            // outside it never accumulating ("weird/buggy"). A clean reverse-Z infinite row with the
            // vanilla near plane restores NRD's intended scale. Nothing else changes: REBLUR never
            // reads a depth buffer (only IN_VIEWZ) and GetScreenUv consumes clip.xy/w alone.
            nrdViewToClip.m22(0f);
            nrdViewToClip.m23(NRD_PROJECTION_NEAR);
            nrdViewToClip.m32(1f);
            nrdViewToClip.m33(0f);

            // worldToView with the camera translation in the rebased terrain space, passed
            // UNMODIFIED: Minecraft's view space is already the +Z-forward LH space NRD works in
            // (see class header). The terrain space is static between rebase anchor rebuilds,
            // which is exactly the "static world, moving camera" model NRD assumes.
            Matrix4f worldToView = new Matrix4f(viewRotation)
                    .translate(-cameraOffsetX, -cameraOffsetY, -cameraOffsetZ);

            // Rebase anchor shift or teleport: the terrain coordinates NRD accumulates against just
            // jumped; the history is unrecoverable, so clear it this frame and start over. Same for
            // a resolution change (the shim recreated the integration, its pools are empty) and a
            // projection change (FOV lerp warped the reprojection space).
            boolean jumped = projectionChanged || lastWidth != renderWidth || lastHeight != renderHeight;
            if (hasPrev && !jumped) {
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
                nrdViewToClip.get(v2c.asByteBuffer().asFloatBuffer());
                worldToView.get(w2v.asByteBuffer().asFloatBuffer());
                if (hasPrev && !jumped) {
                    prevViewToClip(v2cPrev);
                    prevWorldToView(w2vPrev);
                } else {
                    // First frame after (re)creation or a world-coordinate jump: "previous" equals
                    // current, so NRD does not reproject history that is invalid or does not exist.
                    nrdViewToClip.get(v2cPrev.asByteBuffer().asFloatBuffer());
                    worldToView.get(w2vPrev.asByteBuffer().asFloatBuffer());
                }
                // cameraJitter is the raw sub-pixel jitter in PIXEL units (NRD's [-0.5, 0.5]
                // contract); NRD itself converts it when de-jittering the input samples. The MV scale
                // converts the renderer's render-pixel MVs to the UV space NRD reprojects with.
                int rc = lib.setSettings(v2c, v2cPrev, w2v, w2vPrev,
                        jitterPixelsX, jitterPixelsY, jitterPixelsX, jitterPixelsY,
                        1.0f / renderWidth, 1.0f / renderHeight,
                        frameIndex, (hasPrev && !jumped) ? 0 : 1, validationOn ? 1 : 0);
                if (rc != 0) {
                    throw new IllegalStateException("nrdshim_set_settings failed: " + rc + " last=" + lib.lastResult());
                }
            }
            prevOffsetX = cameraOffsetX;
            prevOffsetY = cameraOffsetY;
            prevOffsetZ = cameraOffsetZ;
            nrdViewToClip.get(prevViewToClip);
            worldToView.get(prevWorldToView);
            lastWidth = renderWidth;
            lastHeight = renderHeight;
            hasPrev = true;

            int rc = lib.denoise(cmd,
                    motion.image, VK10.VK_FORMAT_R16G16_SFLOAT,
                    normalRoughness.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    viewZ.image, VK10.VK_FORMAT_R32_SFLOAT,
                    diffIn.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    specIn.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    diffOut.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    specOut.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    validationOn ? validation.image : 0L, VK10.VK_FORMAT_R8G8B8A8_UNORM);
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
