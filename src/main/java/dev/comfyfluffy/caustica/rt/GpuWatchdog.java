package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaMod;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Soft recovery for the two crash signatures this renderer hits most often:
 * <ul>
 *   <li>{@code IllegalStateException: 5s timeout reached when waiting for VK semaphore}
 *       from vanilla {@code VulkanCommandEncoder.submit}.</li>
 *   <li>{@code VK_ERROR_DEVICE_LOST}, which on Windows is usually TDR after a GPU job
 *       runs longer than the OS watchdog (default ~2s).</li>
 * </ul>
 *
 * <p>Settings are read from {@code -Dcaustica.rt.gpuSafety} and
 * {@code -Dcaustica.rt.submitTimeoutSeconds} so this class does not depend on a
 * CausticaConfig schema bump. Defaults: guard on, 15 second submit wait.
 */
public final class GpuWatchdog {
    public static final GpuWatchdog INSTANCE = new GpuWatchdog();

    private static final long SLOW_FRAME_NS = 750_000_000L;
    private static final int DEGRADE_FRAMES = 90;

    private final AtomicInteger degradeFrames = new AtomicInteger();
    private final AtomicLong lastCompositeNs = new AtomicLong();
    private volatile long compositeStartedNs;

    private GpuWatchdog() {
    }

    public boolean enabled() {
        return booleanProperty("caustica.rt.gpuSafety", true);
    }

    public int submitTimeoutSeconds() {
        int seconds = integerProperty("caustica.rt.submitTimeoutSeconds", 15);
        return Math.min(60, Math.max(5, seconds));
    }

    public void beginComposite() {
        this.compositeStartedNs = System.nanoTime();
    }

    public void endComposite(boolean success) {
        long started = this.compositeStartedNs;
        if (started == 0L) {
            return;
        }
        this.compositeStartedNs = 0L;
        long elapsed = System.nanoTime() - started;
        this.lastCompositeNs.set(elapsed);
        if (!enabled()) {
            return;
        }
        if (!success || elapsed >= SLOW_FRAME_NS) {
            armDegrade("composite " + (elapsed / 1_000_000L) + "ms success=" + success);
        } else if (this.degradeFrames.get() > 0) {
            this.degradeFrames.updateAndGet(v -> Math.max(0, v - 1));
        }
    }

    public void onSubmitTimeout(String detail) {
        armDegrade("submit timeout: " + detail);
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
        armDegrade("device lost: " + detail);
    }

    private void armDegrade(String reason) {
        if (!enabled()) {
            return;
        }
        int previous = this.degradeFrames.getAndUpdate(v -> Math.max(v, DEGRADE_FRAMES));
        if (previous == 0) {
            CausticaMod.LOGGER.warn("GPU safety: degrading RT quality for {} frames ({})", DEGRADE_FRAMES, reason);
        }
    }

    public boolean degrading() {
        return enabled() && this.degradeFrames.get() > 0;
    }

    public int clampSpp(int requested) {
        return degrading() ? 1 : Math.max(1, requested);
    }

    public int clampBounces(int requested) {
        return degrading() ? Math.min(requested, 2) : requested;
    }

    public boolean allowVolumetrics() {
        return !degrading();
    }

    public long submitTimeoutNanos() {
        return (long) submitTimeoutSeconds() * 1_000_000_000L;
    }

    private static boolean booleanProperty(String key, boolean fallback) {
        String value = System.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static int integerProperty(String key, int fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
