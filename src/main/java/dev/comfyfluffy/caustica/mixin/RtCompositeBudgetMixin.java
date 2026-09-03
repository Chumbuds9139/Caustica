package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.comfyfluffy.caustica.rt.GpuWatchdog;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Float4;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(RtComposite.class)
public abstract class RtCompositeBudgetMixin {
    private static final int FEATURE_CLOUDS_VOLUMETRIC = 16;

    @WrapMethod(method = "spp")
    private static int caustica$clampSpp(Operation<Integer> original) {
        return GpuWatchdog.INSTANCE.clampSpp(original.call());
    }

    @WrapMethod(method = "maxBounces")
    private static int caustica$clampBounces(Operation<Integer> original) {
        return GpuWatchdog.INSTANCE.clampBounces(original.call());
    }

    @WrapMethod(method = "featureFlags")
    private static int caustica$disableVolumetricCloudsWhileSettling(Operation<Integer> original) {
        int flags = original.call();
        if (!GpuWatchdog.INSTANCE.allowVolumetrics()) {
            flags &= ~FEATURE_CLOUDS_VOLUMETRIC;
        }
        return flags;
    }

    @WrapMethod(method = "fogParams")
    private static Float4 caustica$disableFogWhileSettling(Operation<Float4> original) {
        if (!GpuWatchdog.INSTANCE.allowVolumetrics()) {
            return new Float4(0.0f, 0.0f, 0.0f, 0.0f);
        }
        return original.call();
    }
}
