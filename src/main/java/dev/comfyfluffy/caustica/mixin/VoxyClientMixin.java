package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.compat.VoxyCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Voxy's client initialisation probes OpenGL (capabilities, test shaders, a shared index buffer). On the
 * Vulkan backend there is no OpenGL context, so that method must not run; the only part of it Voxy's
 * storage/ingest engine depends on is done by {@link VoxyCompat#installHeadlessVoxy()} instead.
 * On OpenGL this hook does nothing and Voxy initialises normally.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.VoxyClient", remap = false)
public abstract class VoxyClientMixin {
    @Inject(method = "initVoxyClient", at = @At("HEAD"), cancellable = true, require = 0)
    private static void caustica$headlessInitOnVulkan(CallbackInfo ci) {
        if (VoxyCompat.vulkanDeviceActive()) {
            VoxyCompat.installHeadlessVoxy();
            ci.cancel();
        }
    }
}
