package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import dev.comfyfluffy.caustica.rt.GpuWatchdog;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Do not stretch vanilla's 5s wait. If that wait fires, the recorded command buffer (Caustica's
 * composite plus vanilla work on the same queue) already overran what Minecraft considers a hang.
 * Retrying the next frame on a busy or lost device is what turns the timeout into
 * {@code VK_ERROR_DEVICE_LOST}. Disable RT instead.
 */
@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanCommandEncoderMixin {
    @WrapMethod(method = "submit")
    private void caustica$stopRtAfterSubmitTimeout(Operation<Void> original) {
        try {
            original.call();
        } catch (IllegalStateException exception) {
            String message = exception.getMessage();
            if (message != null && message.contains("timeout") && message.contains("semaphore")) {
                GpuWatchdog.INSTANCE.onSubmitTimeout(message);
                return;
            }
            throw exception;
        }
    }
}
