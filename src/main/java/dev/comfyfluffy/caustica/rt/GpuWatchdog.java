package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaMod;
import net.minecraft.client.multiplayer.ClientLevel;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cheapens work that shares vanilla's graphics submit. It must not turn ray tracing off.
 *
 * <p>Vanilla {@code VulkanCommandEncoder.submit} records Caustica's composite onto the same queue
 * it waits on for 5 seconds. Overrun causes a timeout or {@code VK_ERROR_DEVICE_LOST}. Stretching
 * that wait does not make the packet cheaper. Skipping the composite, or latching RT off after one
 * timeout, makes the world look like ray tracing vanished.
 *
 * <p>This watchdog only:
 * <ul>
 *   <li>clamps SPP / bounces and turns off volumetrics for a few frames after world join;</li>
 *   <li>caps brand-new entity/particle BLAS recorded into the vanilla command buffer;</li>
 *   <li>on timeout, cheapens the next frames and retries RT instead of disabling it.</li>
 * </ul>
 */
public final class GpuWatchdog {
    public static final GpuWatchdog INSTANCE = new GpuWatchdog();

    private static final int SETTLE_FRAMES = 45;
    private static final int MAX_NEW_BLAS_SETTLING = 24;
    private static final int MAX_NEW_BLAS_STEADY = 96;
    private static final int TIMEOUTS_BEFORE_LATCH = 3;

    private final AtomicInteger settleFrames = new AtomicInteger();
    private volatile boolean rtDisabled;
    private volatile ClientLevel lastLevel;
    private volatile int newBlasThisFrame;
    private volatile int timeoutCount;
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
        this.newBlasThisFrame = 0;
        if (level != this.lastLevel) {
            this.lastLevel = level;
            if (level != null) {
                this.rtDisabled = false;
                this.timeoutCount = 0;
                armSettle("level change");
            }
        }
        int left = this.settleFrames.get();
        if (left > 0) {
            this.settleFrames.set(left - 1);
        }
    }

    public void endComposite(boolean success) {
        if (!success && !rtDisabled) {
            armSettle("composite failed");
        }
    }

    /** Composite still runs. Settle only cheapens the packet. */
    public boolean allowTraceThisFrame() {
        return !rtDisabled;
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
        int cap = settling() ? MAX_NEW_BLAS_SETTLING : MAX_NEW_BLAS_STEADY;
        int next = this.newBlasThisFrame + 1;
        if (next > cap) {
            return false;
        }
        this.newBlasThisFrame = next;
        return true;
    }

    public void onSubmitTimeout(String detail) {
        this.timeoutCount++;
        this.lastReason = "submit timeout #" + this.timeoutCount + ": " + detail;
        CausticaMod.LOGGER.error(
                "Vanilla Vulkan submit timed out after 5s ({}). Cheapening RT for {} frames instead of disabling it.",
                this.lastReason, SETTLE_FRAMES);
        armSettle("submit timeout");
        if (this.timeoutCount >= TIMEOUTS_BEFORE_LATCH) {
            CausticaMod.LOGGER.error(
                    "Vulkan submit timed out {} times this session; latching RT off until the next world join.",
                    this.timeoutCount);
            this.rtDisabled = true;
        }
    }

    public void onDeviceLost(String detail) {
        this.lastReason = "device lost: " + detail;
        CausticaMod.LOGGER.error("VK_ERROR_DEVICE_LOST reported; latching RT off until the next world join. lastReason={}", this.lastReason);
        this.rtDisabled = true;
    }

    public String lastReason() {
        return lastReason;
    }

    private boolean settling() {
        return this.settleFrames.get() > 0;
    }

    private void armSettle(String reason) {
        this.lastReason = reason;
        int previous = this.settleFrames.getAndUpdate(v -> Math.max(v, SETTLE_FRAMES));
        if (previous == 0) {
            CausticaMod.LOGGER.warn(
                    "GPU budget: cheapening RT for {} frames ({})", SETTLE_FRAMES, reason);
        }
    }
}
