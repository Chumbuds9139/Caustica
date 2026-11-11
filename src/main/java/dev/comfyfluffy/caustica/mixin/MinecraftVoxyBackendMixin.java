package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import dev.comfyfluffy.caustica.compat.VoxyCompat;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps the Vulkan backend available when Voxy is installed.
 *
 * <p>Voxy wraps this same {@code GpuBackend.createDevice} call and throws {@code "Voxy does not
 * presently support vulkan"} for the Vulkan backend, which makes Minecraft fall back to OpenGL, where
 * Caustica cannot run at all. That veto protects Voxy's OpenGL renderer; with Caustica's bridge the
 * renderer is never created on Vulkan (see {@code VoxyClientMixin} / {@code VoxyConfigMixin}), so the
 * veto has nothing left to protect.
 *
 * <p>The mixin's priority makes it apply after Voxy's, which puts this wrapper outside Voxy's: for the
 * Vulkan backend it calls the backend directly instead of descending into the wrapped chain. Should the
 * ordering ever be the other way round, Voxy's wrapper simply throws first and the game behaves exactly
 * as it does without this mixin. Any other backend, and every case where the bridge is not requested,
 * goes through {@code original} untouched.
 */
@Mixin(value = Minecraft.class, priority = 1500)
public abstract class MinecraftVoxyBackendMixin {
    @WrapOperation(
            method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/api/device/GpuBackend;createDevice(Lcom/mojang/renderpearl/api/device/GpuDebugOptions;)Lcom/mojang/renderpearl/api/device/GpuDevice;"),
            require = 0)
    private static GpuDevice caustica$keepVulkanWithVoxy(GpuBackend backend, GpuDebugOptions debugOptions,
                                                         Operation<GpuDevice> original)
            throws BackendCreationException {
        if (backend instanceof VulkanBackend && VoxyCompat.vulkanBridgeRequested()) {
            // A genuine Vulkan failure still throws BackendCreationException from here and Minecraft
            // falls back exactly as it would have; the flag is only set once the device really exists.
            GpuDevice device = backend.createDevice(debugOptions);
            VoxyCompat.markVulkanDeviceActive();
            return device;
        }
        return original.call(backend, debugOptions);
    }
}
