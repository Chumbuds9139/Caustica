package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaMod;
import net.minecraft.client.multiplayer.ClientLevel;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Budget for work that currently shares vanilla's graphics submit.
 *
 * <p>Vanilla {@code VulkanCommandEncoder.submit} records Caustica's composite command buffer onto
 * the same queue it later waits on for 5 seconds. That buffer is not "a present". It includes
 * per-entity / per-particle BLAS builds, a TLAS rebuild, {@code TraceRays}, optional volumetric
 * cloud/fog marches, and the denoise/upscale pass. If that packet overruns Windows TDR (~2s) the
 * wait returns {@code VK_ERROR_DEVICE_LOST}; if the GPU merely runs long, vanilla throws
 * {@code 5s timeout reached when waiting for VK semaphore}.
 *
 * <p>Stretching the 5s wait does not make the packet cheaper, and swallowing a timeout then
 * submitting again is how a hang becomes device-lost. This class instead:
 * <ul>
 *   <li>detects the expensive windows (world join, dimension change, resource reload) and skips
 *       RT for a few frames so terrain BLAS can finish on the compute queue;</li>
 *   <li>keeps volumetric clouds/fog off during that settle window;</li>
 *   <li>caps brand-new (non-refit) BLAS ops recorded into the vanilla command buffer;</li>
 *   <li>on a vanilla submit timeout, disables RT instead of retrying a dead queue.</li>
 * </ul>
 */
public final class GpuWatchdog {
    public static final GpuWatchdog INSTANCE = new GpuWatchdog();

    private static final int SETTLE_FRAMES = 45;
    private static final int MAX_NEW_BLAS_SETTLING = 24;
    private static final int MAX_NEW_BLAS_STEADY = 96;
    private static final long SLOW_CPU_NS = 400_000_000L;

    private final AtomicInteger settleFrames = new AtomicInteger();
    private volatile boolean rtDisabled;
    private volatile ClientLevel lastLevel;
    private volatile long compositeStartedNs;
    private volatile int newBlasThisFrame;
    private volatile String lastReason = "idle";

    private GpuWatchdog() {
    }

    public boolean rtEnabled() {
        return !rtDisabled;
    }

    public void onWorldOrAtlasInvalidated(String reason) {
        armSettle("invalidate: " + reason);
    }

    public void beginComposite(ClientLevel level) {
        this.compositeStartedNs = System.nanoTime();
        this.newBlasThisFrame = 0;
        if (rtDisabled) {
            return;
        }
        if (level != this.lastLevel) {
            this.lastLevel = level;
            if (level != null) {
                armSettle("level change");
            }
        }
        int left = this.settleFrames.get();
        if (left > 0) {
            this.settleFrames.set(left - 1);
        }
    }

    public void endComposite(boolean success) {
        long started = this.compositeStartedNs;
        this.compositeStartedNs = 0L;
        if (started == 0L || rtDisabled) {
            return;
        }
        long elapsed = System.nanoTime() - started;
        if (!success || elapsed >= SLOW_CPU_NS) {
            armSettle("slow composite " + (elapsed / 1_000_000L) + "ms success=" + success);
        }
    }

    /**
     * When false, WorldRenderScaler must not record an RT composite into vanilla's command encoder.
     * Terrain streaming still ran earlier in the tick.
     */
    public boolean allowTraceThisFrame() {
        if (rtDisabled) {
            return false;
        }
        return this.settleFrames.get() <= SETTLE_FRAMES / 3;
    }

    public boolean allowVolumetrics() {
        return !rtDisabled && this.settleFrames.get() == 0;
    }

    public int clampSpp(int requested) {
        return settling() ? 1 : Math.max(1, requested);
    }

    public int clampBounces(int requested) {
        return settling() ? Math.min(requested, 2) : requested;
    }

    public boolean tryConsumeNewBlas() {
        if (rtDisabled) {
            return false;
        }
        int cap = settling() ? MAX_NEW_BLAS_SETTLING : MAX_NEW_BLAS_STEADY;
        int next = this.newBlasThisFrame + 1;
        if (next > cap) {
            return false;
        }
        this.newBlasThisFrame = next;
        return true;
    }

    public void onSubmitTimeout(String detail) {
        this.lastReason = "submit timeout: " + detail;
        CausticaMod.LOGGER.error(
                "Vanilla Vulkan submit timed out after 5s. That wait is for the command buffer "
                        + "Caustica recorded into the Minecraft graphics queue (entity/particle BLAS + "
                        + "TLAS + TraceRays + denoise), not a generic present. RT is being disabled so "
                        + "the next submit does not hit a lost or still-busy device. lastReason={}",
                this.lastReason);
        this.rtDisabled = true;
        RtContext ctx = RtContext.currentOrNull();
        if (ctx != null) {
            try {
                ctx.waitIdle();
            } catch (Throwable t) {
                CausticaMod.LOGGER.warn("waitIdle after submit timeout failed: {}", t.toString());
            }
        }
    }

    public void onDeviceLost(String detail) {
        this.lastReason = "device lost: " + detail;
        CausticaMod.LOGGER.error("VK_ERROR_DEVICE_LOST reported; disabling RT. lastReason={}", this.lastReason);
        this.rtDisabled = true;
    }

    public String lastReason() {
        return lastReason;
    }

    private boolean settling() {
        return this.settleFrames.get() > 0;
    }

    private void armSettle(String reason) {
        if (rtDisabled) {
            return;
        }
        this.lastReason = reason;
        int previous = this.settleFrames.getAndUpdate(v -> Math.max(v, SETTLE_FRAMES));
        if (previous == 0) {
            CausticaMod.LOGGER.warn(
                    "GPU budget: skipping/cheapening RT for {} frames ({})", SETTLE_FRAMES, reason);
        }
    }
}
