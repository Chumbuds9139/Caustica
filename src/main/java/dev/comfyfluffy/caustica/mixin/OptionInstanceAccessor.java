package dev.comfyfluffy.caustica.mixin;

import java.util.function.Function;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only view of an option's caption and value formatter, so the same {@link OptionInstance}s that
 * build the vanilla-style Ray Tracing screens can also be presented by Sodium's settings screen.
 */
@Mixin(OptionInstance.class)
public interface OptionInstanceAccessor<T> {
    @Accessor("caption")
    Component caustica$caption();

    @Accessor("toString")
    Function<T, Component> caustica$valueLabel();
}
