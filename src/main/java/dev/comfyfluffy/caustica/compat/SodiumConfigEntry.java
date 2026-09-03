package dev.comfyfluffy.caustica.compat;

import dev.comfyfluffy.caustica.client.gui.RtSharcOptionsScreen;
import dev.comfyfluffy.caustica.client.gui.RtVideoOptionsScreen;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Sodium Video Settings integration. Sodium replaces vanilla's video options screen, so the
 * mixin that appends a "Ray Tracing Settings..." button never runs. This entrypoint registers
 * an external Sodium page that opens Caustica's own settings hub.
 *
 * <p>Loaded only when Sodium is present via the {@code sodium:config_api_user} Fabric entrypoint.
 */
public final class SodiumConfigEntry implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        builder.registerOwnModOptions()
                .addPage(builder.createExternalPage()
                        .setName(Component.translatable("caustica.options.rt.title"))
                        .setScreenConsumer(current -> {
                            Minecraft minecraft = Minecraft.getInstance();
                            minecraft.gui.setScreen(new RtVideoOptionsScreen(current, minecraft.options));
                        }))
                .addPage(builder.createExternalPage()
                        .setName(Component.translatable("caustica.options.sharc.title"))
                        .setScreenConsumer(current -> {
                            Minecraft minecraft = Minecraft.getInstance();
                            minecraft.gui.setScreen(new RtSharcOptionsScreen(current, minecraft.options));
                        }));
    }
}
