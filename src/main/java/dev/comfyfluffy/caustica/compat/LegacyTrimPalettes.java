package dev.comfyfluffy.caustica.compat;

import java.util.ArrayList;
import java.util.List;

/**
 * Where an armor-trim palette named by a pre-26.x resource pack lives today.
 *
 * <p>Up to 1.21 the item and armor-trim atlases named their palettes by texture path, for example
 * {@code minecraft:trims/color_palettes/trim_palette} for the key and
 * {@code minecraft:trims/color_palettes/iron_darker} for a material, loaded from
 * {@code textures/<path>.png}. Minecraft 26.x moved palettes to their own folder,
 * {@code textures/palettes/<id>.png}, and renamed them to {@code trim_base} and {@code trim/<material>}.
 *
 * <p>A resource pack (very often a server pack) that still overrides {@code atlases/items.json} with the
 * old names makes every trimmed armor icon fail to load: vanilla logs "Failed to load palette image
 * minecraft:trims/color_palettes/trim_palette" once per texture and permutation, and the icons render
 * as the missing texture. The palette format itself did not change (a strip of pixels), so the old name
 * can simply be looked up where the image is now.
 */
public final class LegacyTrimPalettes {
    static final String LEGACY_FOLDER = "trims/color_palettes/";
    static final String LEGACY_KEY = "trim_palette";
    static final String MODERN_KEY = "trim_base";
    static final String MODERN_FOLDER = "trim/";

    private LegacyTrimPalettes() {
    }

    /**
     * Resource paths (namespace-relative, with extension) to try, in order, for a palette id that the
     * game could not find at its modern location. Empty when the id is not a legacy trim palette.
     *
     * <ol>
     *   <li>The pack's own image at the old location, so a pack that ships custom palettes keeps them.</li>
     *   <li>The image that replaced it in 26.x, so a pack that only overrides the atlas definition gets the
     *       vanilla colours.</li>
     * </ol>
     */
    public static List<String> fallbackPaths(String palettePath) {
        if (palettePath == null || !palettePath.startsWith(LEGACY_FOLDER)) {
            return List.of();
        }
        String name = palettePath.substring(LEGACY_FOLDER.length());
        if (name.isEmpty() || name.indexOf('/') >= 0) {
            return List.of();
        }
        List<String> paths = new ArrayList<>(2);
        paths.add("textures/" + palettePath + ".png");
        String modern = name.equals(LEGACY_KEY) ? MODERN_KEY : MODERN_FOLDER + name;
        paths.add("textures/palettes/" + modern + ".png");
        return paths;
    }
}
