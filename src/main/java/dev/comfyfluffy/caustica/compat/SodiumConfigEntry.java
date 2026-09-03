package dev.comfyfluffy.caustica.compat;

import dev.comfyfluffy.caustica.client.gui.RtSharcOptionsScreen;
import dev.comfyfluffy.caustica.client.gui.RtVideoOptionsScreen;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Sodium replaces vanilla Video Settings, so {@code VideoSettingsScreenMixin} never runs.
 * Register Caustica pages through Sodium's public Config API instead of mixing into Sodium.
 *
 * <p>Loaded only when Sodium is present, via the {@code sodium:config_api_user} entrypoint.
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
