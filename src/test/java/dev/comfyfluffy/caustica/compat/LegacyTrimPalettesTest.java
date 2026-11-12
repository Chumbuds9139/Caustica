package dev.comfyfluffy.caustica.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LegacyTrimPalettesTest {
    @Test
    void legacyKeyMapsToTrimBase() {
        assertEquals(List.of("textures/trims/color_palettes/trim_palette.png", "textures/palettes/trim_base.png"),
                LegacyTrimPalettes.fallbackPaths("trims/color_palettes/trim_palette"));
    }

    @Test
    void legacyMaterialsMapIntoTheTrimFolder() {
        assertEquals(List.of("textures/trims/color_palettes/iron_darker.png", "textures/palettes/trim/iron_darker.png"),
                LegacyTrimPalettes.fallbackPaths("trims/color_palettes/iron_darker"));
        assertEquals("textures/palettes/trim/resin.png",
                LegacyTrimPalettes.fallbackPaths("trims/color_palettes/resin").get(1));
    }

    @Test
    void modernAndUnrelatedIdsAreLeftAlone() {
        assertTrue(LegacyTrimPalettes.fallbackPaths("trim_base").isEmpty());
        assertTrue(LegacyTrimPalettes.fallbackPaths("trim/iron").isEmpty());
        assertTrue(LegacyTrimPalettes.fallbackPaths("trims/color_palettes/").isEmpty());
        assertTrue(LegacyTrimPalettes.fallbackPaths("trims/color_palettes/a/b").isEmpty());
        assertTrue(LegacyTrimPalettes.fallbackPaths(null).isEmpty());
    }
}
