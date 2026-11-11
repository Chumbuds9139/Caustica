package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;

import java.util.function.LongPredicate;
import java.util.function.LongSupplier;

/**
 * Patience policy for the two places Minecraft's Vulkan backend gives the GPU a fixed five seconds:
 * the wait for an earlier frame's submission inside {@code VulkanCommandEncoder.submit()} and the
 * swapchain acquire in {@code VulkanGpuSurface.acquireNextTexture()}.
 *
 * <p>Both vanilla call sites turn a timeout straight into a crash ("5s timeout reached when waiting for
 * VK semaphore" / "GPU timeout attempting to acquire next frame"). For a rasteriser that is a reasonable
 * hang detector. A path tracer legitimately exceeds it while the device is perfectly healthy: video
 * memory oversubscription pages textures over the bus, a driver compiles a ray-tracing pipeline or
 * rebuilds a large BVH, the window is occluded, or the OS briefly hands the GPU to something else.
 *
 * <p>The wait is therefore repeated in vanilla-sized slices up to a configurable budget. Nothing about
 * a real failure is hidden: the underlying Vulkan call still runs each slice, so {@code
 * VK_ERROR_DEVICE_LOST} surfaces on the very next slice through the normal crash path, and once the
 * budget is spent the vanilla exception is thrown exactly as before.
 */
public final class RtGpuWatchdog {
    /** One vanilla-sized slice; also the threshold at or below which the setting means "vanilla". */
    public static final long VANILLA_TIMEOUT_NANOS = 5_000_000_000L;

    private RtGpuWatchdog() {
    }

    /** Total nanoseconds a single wait may take, never less than what vanilla itself asked for. */
    public static long budgetNanos(long vanillaTimeoutNanos) {
        long configured;
        try {
            configured = CausticaConfig.Rt.Stability.GPU_TIMEOUT_SECONDS.value() * 1_000_000_000L;
        } catch (Throwable t) {
            // Config class-init failure must never be able to break the frame loop.
            configured = vanillaTimeoutNanos;
        }
        return Math.max(vanillaTimeoutNanos, configured);
    }

    /**
     * Repeat {@code attempt} until it reports completion or the budget is spent.
     *
     * @param attempt             performs one bounded wait of the given length; true once complete
     * @param vanillaTimeoutNanos the timeout the vanilla call site passed
     * @param what                short description for the log
     * @return whether the wait completed; false hands control back to vanilla's own timeout handling
     */
    public static boolean await(LongPredicate attempt, long vanillaTimeoutNanos, String what) {
        return await(attempt, vanillaTimeoutNanos, budgetNanos(vanillaTimeoutNanos), System::nanoTime, what);
    }

    /** Testable core of {@link #await(LongPredicate, long, String)}. */
    static boolean await(LongPredicate attempt, long sliceNanos, long budgetNanos, LongSupplier clock,
                         String what) {
        if (sliceNanos <= 0L) {
            // A poll (timeout 0) or an unbounded wait is not a watchdog; pass it through untouched.
            return attempt.test(sliceNanos);
        }
        long start = clock.getAsLong();
        long waited = 0L;
        while (true) {
            long slice = Math.min(sliceNanos, Math.max(1L, budgetNanos - waited));
            if (attempt.test(slice)) {
                if (waited >= sliceNanos) {
                    CausticaMod.LOGGER.warn("GPU caught up after {} ms waiting for {}; continuing",
                            waited / 1_000_000L, what);
                }
                return true;
            }
            waited = clock.getAsLong() - start;
            if (waited >= budgetNanos) {
                CausticaMod.LOGGER.error("GPU still busy after {} ms waiting for {}; giving up",
                        waited / 1_000_000L, what);
                logStateQuietly();
                return false;
            }
            CausticaMod.LOGGER.warn(
                    "GPU has not finished {} after {} ms (vanilla would crash at {} ms); still waiting, "
                            + "budget {} ms. Lower the render scale, samples or LOD distance if this repeats.",
                    what, waited / 1_000_000L, sliceNanos / 1_000_000L, budgetNanos / 1_000_000L);
            logStateQuietly();
        }
    }

    private static void logStateQuietly() {
        try {
            VulkanDiagnostics.logStallSnapshot();
        } catch (Throwable ignored) {
            // Diagnostics are best-effort and must not turn a recoverable stall into a failure.
        }
    }
}
