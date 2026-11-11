package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import dev.comfyfluffy.caustica.rt.RtGpuWatchdog;
import dev.comfyfluffy.caustica.rt.RtLoadGovernor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Replaces vanilla's fixed five-second frame watchdog with {@link RtGpuWatchdog}'s configurable budget.
 *
 * <p>{@code submit()} waits for the submission two frames back and throws "5s timeout reached when
 * waiting for VK semaphore" when it has not finished. Only that one call is wrapped; the wait itself is
 * still vanilla's {@code awaitSubmitCompletion}, so its device-loss handling and completed-index
 * bookkeeping are untouched and a real {@code VK_ERROR_DEVICE_LOST} is reported on the next slice.
 */
@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanCommandEncoderMixin {
    @WrapOperation(
            method = "submit",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;awaitSubmitCompletion(JJ)Z"))
    private boolean caustica$patientSubmitWait(VulkanCommandEncoder encoder, long submitIndex, long timeoutNanos,
                                               Operation<Boolean> original) {
        long started = System.nanoTime();
        try {
            return RtGpuWatchdog.await(slice -> original.call(encoder, submitIndex, slice), timeoutNanos,
                    "an earlier frame's GPU work");
        } finally {
            // How long the render thread was blocked on the GPU is what tells a GPU-bound slow frame
            // from a CPU hitch; the TDR guard only reduces the load for the former.
            RtLoadGovernor.INSTANCE.noteGpuWait(System.nanoTime() - started);
        }
    }
}
