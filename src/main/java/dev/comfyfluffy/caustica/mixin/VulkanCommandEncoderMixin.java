package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.GpuWatchdog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Vanilla {@code VulkanCommandEncoder.submit} waits a hard-coded 5 seconds for the GPU
 * semaphore that marks the submitted command buffer complete. A path-traced frame that
 * runs longer than that throws
 * {@code IllegalStateException: 5s timeout reached when waiting for VK semaphore},
 * after which the next submit often surfaces {@code VK_ERROR_DEVICE_LOST}.
 *
 * <p>This mixin stretches that wait to the configured timeout and, if a timeout still
 * happens, drains the device and degrades RT quality instead of immediately crashing.
 */
@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanCommandEncoderMixin {
    @ModifyConstant(method = "submit", constant = @Constant(longValue = 5_000_000_000L), require = 0)
    private long caustica$stretchSubmitTimeoutNs(long original) {
        return GpuWatchdog.INSTANCE.submitTimeoutNanos();
    }

    @ModifyConstant(method = "submit", constant = @Constant(longValue = 5000L), require = 0)
    private long caustica$stretchSubmitTimeoutMs(long original) {
        return Math.max(5L, (long) CausticaConfig.Rt.GpuSafety.SUBMIT_TIMEOUT_SECONDS.value() * 1000L);
    }

    @WrapMethod(method = "submit")
    private void caustica$recoverSubmitTimeout(Operation<Void> original) {
        try {
            original.call();
        } catch (IllegalStateException exception) {
            String message = exception.getMessage();
            if (message != null && message.contains("timeout") && message.contains("semaphore")) {
                CausticaMod.LOGGER.error("Vulkan submit timed out waiting for a semaphore; draining GPU and degrading RT: {}", message);
                GpuWatchdog.INSTANCE.onSubmitTimeout(message);
                return;
            }
            throw exception;
        }
    }
}
