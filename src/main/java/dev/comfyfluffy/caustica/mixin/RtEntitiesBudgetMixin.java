package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.comfyfluffy.caustica.rt.GpuWatchdog;
import dev.comfyfluffy.caustica.rt.entity.RtEntities;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(RtEntities.class)
public abstract class RtEntitiesBudgetMixin {
    @WrapMethod(method = "particlesEnabled")
    private static boolean caustica$skipParticleBlasWhileSettling(Operation<Boolean> original) {
        return original.call() && GpuWatchdog.INSTANCE.allowVolumetrics();
    }
}
