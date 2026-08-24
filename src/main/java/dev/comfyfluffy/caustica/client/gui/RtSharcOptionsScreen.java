package dev.comfyfluffy.caustica.client.gui;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.client.RtVideoOptions;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * Dedicated experimental SHaRC settings sub-screen, opened from the main Ray Tracing Settings screen
 * by the "SHaRC Settings..." button (the same nested-sub-screen pattern Video Settings uses to open
 * Ray Tracing Settings).
 *
 * <p>Layout: an experimental enable toggle, then all the cache tuning rows in one group, plus a big
 * reset button that clears the radiance cache. Every control writes straight into
 * {@link CausticaConfig.Rt.Sharc} and applies on the next frame; the cache buffer itself is
 * allocated/cleared by {@code RtComposite.syncSharcResources}.
 */
public final class RtSharcOptionsScreen extends OptionsSubScreen {
    private static final Component TITLE = Component.translatable("caustica.options.sharc.title");
    private static final Component HEADER = Component.translatable("caustica.options.sharc.header");
    private static final Component CACHE_HEADER = Component.translatable("caustica.options.sharc.cacheHeader");

    public RtSharcOptionsScreen(Screen parent, Options options) {
        super(parent, options, TITLE);
    }

    @Override
    protected void addOptions() {
        OptionsList list = ((dev.comfyfluffy.caustica.mixin.OptionsSubScreenAccessor) (Object) this).getList();
        if (list == null) {
            return;
        }

        list.addHeader(HEADER);
        list.addBig(RtVideoOptions.sharcToggleButton());

        list.addHeader(CACHE_HEADER);
        list.addSmall(RtVideoOptions.sharcOptions());

        list.addBig(RtVideoOptions.sharcResetButton());
    }

    @Override
    public void removed() {
        CausticaConfig.save();
        super.removed();
    }
}
