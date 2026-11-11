package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.compat.VoxyCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every path that creates Voxy's OpenGL render system first asks {@code VoxyConfig.isRenderingEnabled()}.
 * Answering "no" on the Vulkan backend keeps that renderer from ever being constructed, whatever the
 * saved Voxy config or its settings screen says, while leaving Voxy's own stored preference untouched
 * for the next OpenGL session. Chunk ingest and LOD storage are separate switches and keep running.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.config.VoxyConfig", remap = false)
public abstract class VoxyConfigMixin {
    @Inject(method = "isRenderingEnabled", at = @At("HEAD"), cancellable = true, require = 0)
    private void caustica$noOpenGlRendererOnVulkan(CallbackInfoReturnable<Boolean> cir) {
        if (VoxyCompat.vulkanDeviceActive()) {
            cir.setReturnValue(false);
        }
    }
}
