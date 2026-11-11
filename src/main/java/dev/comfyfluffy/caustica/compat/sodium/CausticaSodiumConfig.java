package dev.comfyfluffy.caustica.compat.sodium;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.client.RtVideoOptions;
import dev.comfyfluffy.caustica.client.gui.RtVideoOptionsScreen;
import dev.comfyfluffy.caustica.compat.VoxyCompat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.PageBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Registers Caustica with Sodium's settings screen ({@code sodium:config_api_user} entrypoint).
 *
 * <p>Sodium replaces the vanilla Video Settings screen, which is where Caustica's "Ray Tracing
 * Settings..." button lives, so without this the renderer's options are unreachable once Sodium is
 * installed. Caustica then shows up as its own entry in Sodium's mod list with:
 * <ul>
 *   <li>a first page that opens the complete Ray Tracing hub (every sub-screen, the per-page and global
 *       "Reset to Defaults", frame generation, SHaRC and the Distant Horizons / Voxy tools);</li>
 *   <li>native Sodium pages built from the very same option rows those screens use, so a setting
 *       changed here behaves exactly as if it had been changed there.</li>
 * </ul>
 *
 * <p>This class is only ever loaded by Sodium (Fabric instantiates entrypoints lazily), so the Sodium
 * API types it references never need to exist when Sodium is absent.
 *
 * <p>Sodium treats an exception thrown while registering as fatal and crashes the game. Every row and
 * page is therefore built defensively: a row that cannot be represented is skipped, a page is only
 * handed to Sodium once it is known to contain at least one row, and the always-valid hub page is added
 * first so the mod entry can never be empty.
 */
public final class CausticaSodiumConfig implements ConfigEntryPoint {
    private static final Runnable NO_REOPEN = () -> { };

    private record Group(String headerKey, Supplier<OptionInstance<?>[]> rows) {
    }

    private record Page(String headerKey, List<Group> groups) {
    }

    private static List<Page> pages() {
        List<Group> advanced = new ArrayList<>();
        advanced.add(new Group("caustica.options.rt.streamingHeader", RtVideoOptions::streamingOptions));
        advanced.add(new Group("caustica.options.rt.debugHeader", RtVideoOptions::debugOptions));
        if (VoxyCompat.enabled()) {
            // Only offered when a Voxy provider can actually deliver LODs in this session.
            advanced.add(new Group("caustica.options.voxy.header", RtVideoOptions::voxyOptions));
        }
        return List.of(
                new Page("caustica.options.rt.imageMenuHeader", List.of(
                        new Group("caustica.options.rt.qualityHeader", () -> RtVideoOptions.qualityOptions(NO_REOPEN)),
                        new Group("caustica.options.rt.upscalingHeader", () -> RtVideoOptions.upscalingOptions(NO_REOPEN)),
                        new Group("caustica.options.rt.lightsHeader", RtVideoOptions::lightOptions),
                        new Group("caustica.options.rt.reflexHeader", RtVideoOptions::reflexOptions),
                        new Group("caustica.options.sharc.header", RtVideoOptions::sharcOptions))),
                new Page("caustica.options.rt.displayMenuHeader", List.of(
                        new Group("caustica.options.rt.exposureHeader", RtVideoOptions::exposureOptions),
                        new Group("caustica.options.rt.tonemapHeader", RtVideoOptions::tonemapOptions),
                        new Group("caustica.options.rt.bloomHeader", RtVideoOptions::bloomOptions),
                        new Group("caustica.options.rt.dofHeader", RtVideoOptions::dofOptions),
                        new Group("caustica.options.rt.hdrHeader", RtVideoOptions::hdrOptions))),
                new Page("caustica.options.rt.worldMenuHeader", List.of(
                        new Group("caustica.options.rt.skyHeader", RtVideoOptions::skyOptions),
                        new Group("caustica.options.rt.worldHeader", RtVideoOptions::worldOptions),
                        new Group("caustica.options.rt.effectsHeader", RtVideoOptions::effectsOptions),
                        new Group("caustica.options.rt.waterHeader", RtVideoOptions::waterOptions),
                        new Group("caustica.options.rt.pomHeader", RtVideoOptions::pomOptions),
                        new Group("caustica.options.rt.cloudsHeader", () -> RtVideoOptions.cloudOptions(NO_REOPEN)),
                        new Group("caustica.options.rt.fogHeader", RtVideoOptions::fogOptions),
                        new Group("caustica.options.rt.wetnessHeader", RtVideoOptions::groundWetnessOptions),
                        new Group("caustica.options.rt.rainOpticsHeader", RtVideoOptions::rainOpticsOptions),
                        new Group("caustica.options.rt.underwaterHeader", RtVideoOptions::underwaterOptions),
                        new Group("caustica.options.rt.lightningHeader", RtVideoOptions::lightningOptions))),
                new Page("caustica.options.rt.advancedMenuHeader", List.copyOf(advanced)));
    }

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        List<PageBuilder> ready = new ArrayList<>();
        PageBuilder hub;
        try {
            hub = builder.createExternalPage()
                    .setName(Component.translatable("caustica.options.rt.settingsButton"))
                    .setScreenConsumer(CausticaSodiumConfig::openHub);
            if (CausticaConfig.Rt.ENABLED.value()) {
                Set<String> usedIds = new HashSet<>();
                for (Page page : pages()) {
                    PageBuilder built = buildPage(builder, page, usedIds);
                    if (built != null) ready.add(built);
                }
            }
        } catch (Throwable t) {
            // Nothing has been registered with Sodium yet, so backing out here leaves its screen intact.
            CausticaMod.LOGGER.warn("Caustica could not prepare its Sodium settings pages; "
                    + "use the Ray Tracing Settings screen from vanilla Video Settings instead", t);
            return;
        }

        ModOptionsBuilder mod = builder.registerOwnModOptions();
        // First and unconditional: with this page present the entry satisfies Sodium's "at least one
        // page" rule whatever happens to the optional native pages below.
        mod.addPage(hub);
        for (PageBuilder page : ready) {
            try {
                mod.addPage(page);
            } catch (Throwable t) {
                CausticaMod.LOGGER.warn("Skipping one Caustica settings page in Sodium's screen", t);
            }
        }
    }

    private static void openHub(Screen current) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.gui.setScreen(new RtVideoOptionsScreen(current, minecraft.options));
    }

    /** Build one page, or return null when none of its rows could be represented. */
    private static PageBuilder buildPage(ConfigBuilder builder, Page page, Set<String> usedIds) {
        OptionPageBuilder pageBuilder = builder.createOptionPage().setName(Component.translatable(page.headerKey()));
        int groups = 0;
        for (Group group : page.groups()) {
            OptionGroupBuilder groupBuilder = builder.createOptionGroup()
                    .setName(Component.translatable(group.headerKey()));
            int rows = 0;
            OptionInstance<?>[] instances;
            try {
                instances = group.rows().get();
            } catch (Throwable t) {
                CausticaMod.LOGGER.warn("Caustica settings group {} is unavailable in Sodium's screen",
                        group.headerKey(), t);
                continue;
            }
            if (instances == null) continue;
            for (OptionInstance<?> instance : instances) {
                try {
                    SodiumOptionAdapter adapter = SodiumOptionAdapter.describe(group.rows(), instance);
                    if (adapter == null) continue;
                    String path = SodiumOptionAdapter.identifierPath(adapter.key());
                    // The same row can appear in two groups (the upscaler selector heads both Quality
                    // and Upscaling); Sodium keys options by id, so only the first occurrence is listed.
                    if (!usedIds.add(path)) continue;
                    OptionBuilder option = buildOption(builder, Identifier.fromNamespaceAndPath("caustica", path),
                            adapter);
                    // addOption builds the option immediately; a rejected row throws here and is skipped
                    // without having been attached to the group.
                    groupBuilder.addOption(option);
                    rows++;
                } catch (Throwable t) {
                    CausticaMod.LOGGER.warn("Skipping one Caustica setting in Sodium's screen", t);
                }
            }
            if (rows > 0) {
                try {
                    pageBuilder.addOptionGroup(groupBuilder);
                    groups++;
                } catch (Throwable t) {
                    CausticaMod.LOGGER.warn("Skipping Caustica settings group {} in Sodium's screen",
                            group.headerKey(), t);
                }
            }
        }
        return groups > 0 ? pageBuilder : null;
    }

    private static OptionBuilder buildOption(ConfigBuilder builder, Identifier id, SodiumOptionAdapter adapter) {
        if (adapter.kind() == SodiumOptionAdapter.Kind.TOGGLE) {
            return builder.createBooleanOption(id)
                    .setName(adapter.caption())
                    .setTooltip(value -> adapter.tooltip())
                    .setStorageHandler(CausticaConfig::save)
                    // The value the game started with: Caustica's own screens own "factory defaults"
                    // (their Reset buttons know each setting's real default and unit).
                    .setDefaultValue(adapter.load() != 0)
                    .setBinding(value -> adapter.save(Boolean.TRUE.equals(value) ? 1 : 0),
                            () -> adapter.load() != 0);
        }
        return builder.createIntegerOption(id)
                .setName(adapter.caption())
                .setTooltip(value -> adapter.tooltip())
                .setStorageHandler(CausticaConfig::save)
                .setRange(new Range(adapter.min(), adapter.max(), 1))
                .setValueFormatter(adapter::valueText)
                .setDefaultValue(adapter.load())
                .setBinding(value -> adapter.save(value == null ? adapter.load() : value), adapter::load);
    }
}
