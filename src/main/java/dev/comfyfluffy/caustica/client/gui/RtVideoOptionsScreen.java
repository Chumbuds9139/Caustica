package dev.comfyfluffy.caustica.client.gui;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.client.RtVideoOptions;
import dev.comfyfluffy.caustica.compat.DistantHorizonsCompat;
import dev.comfyfluffy.caustica.compat.VoxyCompat;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * Dedicated Ray Tracing settings sub-screen, opened from Video Settings via a single
 * "Ray Tracing Settings..." button. Keeps Video Settings clean and groups all Caustica controls
 * in one organized place.
 *
 * <p>Layout: Quality / General / Effects / Parallax / Clouds / HDR / Debug / DH / Voxy / Lighting / Tonemapping
 * Each section is a header + small options, with big buttons for refresh actions.
 *
 * <p>Config persistence: on removed() we call CausticaConfig.save() (same as VideoSettingsScreenMixin did).
 */
public class RtVideoOptionsScreen extends OptionsSubScreen {

    // Title + section headers
    private static final Component TITLE = Component.translatable("caustica.options.rt.title");
    private static final Component QUALITY_HEADER = Component.translatable("caustica.options.rt.qualityHeader");
    private static final Component GENERAL_HEADER = Component.translatable("caustica.options.rt.generalHeader");
    private static final Component EFFECTS_HEADER = Component.translatable("caustica.options.rt.effectsHeader");
    private static final Component POM_HEADER = Component.translatable("caustica.options.rt.pomHeader");
    private static final Component CLOUDS_HEADER = Component.translatable("caustica.options.rt.cloudsHeader");
    private static final Component HDR_HEADER = Component.translatable("caustica.options.rt.hdrHeader");
    private static final Component DEBUG_HEADER = Component.translatable("caustica.options.rt.debugHeader");
    private static final Component DH_HEADER = Component.translatable("caustica.options.rt.dhHeader");
    private static final Component VOXY_HEADER = Component.translatable("caustica.options.voxy.header");
    private static final Component LIGHTS_HEADER = Component.translatable("caustica.options.rt.lightsHeader");
    private static final Component TONEMAP_HEADER = Component.translatable("caustica.options.rt.tonemapHeader");
    private static final Component EXPOSURE_HEADER = Component.translatable("caustica.options.rt.exposureHeader");

    public RtVideoOptionsScreen(Screen parent, Options options) {
        super(parent, options, TITLE);
    }

    @Override
    protected void addOptions() {
        if (!CausticaConfig.Rt.ENABLED.value()) {
            return;
        }
        OptionsList list = ((dev.comfyfluffy.caustica.mixin.OptionsSubScreenAccessor) (Object) this).getList();
        if (list == null) {
            return;
        }

        // --- Quality / Performance ---
        list.addHeader(QUALITY_HEADER);
        list.addSmall(RtVideoOptions.qualityOptions());

        // --- General ---
        list.addHeader(GENERAL_HEADER);
        list.addSmall(RtVideoOptions.generalOptions());

        // --- Effects ---
        list.addHeader(EFFECTS_HEADER);
        list.addSmall(RtVideoOptions.effectsOptions());

        // --- Parallax / POM ---
        list.addHeader(POM_HEADER);
        list.addSmall(RtVideoOptions.pomOptions());

        // --- Clouds ---
        list.addHeader(CLOUDS_HEADER);
        list.addSmall(RtVideoOptions.cloudOptions());

        // --- HDR ---
        list.addHeader(HDR_HEADER);
        list.addSmall(RtVideoOptions.hdrOptions());

        // --- Debug ---
        list.addHeader(DEBUG_HEADER);
        list.addSmall(RtVideoOptions.debugOptions());

        // --- Distant Horizons (if present) ---
        if (DistantHorizonsCompat.enabled()) {
            list.addHeader(DH_HEADER);
            list.addBig(RtVideoOptions.distantHorizonsRefreshButton());
        }

        // --- Voxy LODs (if present) ---
        if (VoxyCompat.enabled()) {
            list.addHeader(VOXY_HEADER);
            list.addSmall(RtVideoOptions.voxyOptions());
            list.addBig(RtVideoOptions.voxyRefreshButton());
        }

        // --- Lighting ---
        list.addHeader(LIGHTS_HEADER);
        list.addSmall(RtVideoOptions.lightOptions());

        // --- Exposure + Tonemapping ---
        list.addHeader(EXPOSURE_HEADER);
        list.addSmall(RtVideoOptions.exposureOptions());

        list.addHeader(TONEMAP_HEADER);
        list.addSmall(RtVideoOptions.tonemapOptions());
    }

    @Override
    public void removed() {
        CausticaConfig.save();
        super.removed();
    }
}
