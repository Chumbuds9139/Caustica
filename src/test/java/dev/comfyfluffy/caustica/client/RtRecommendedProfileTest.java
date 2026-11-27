package dev.comfyfluffy.caustica.client;

import org.junit.jupiter.api.Test;

import static dev.comfyfluffy.caustica.client.RtRecommendedProfile.BALANCED;
import static dev.comfyfluffy.caustica.client.RtRecommendedProfile.PERFORMANCE;
import static dev.comfyfluffy.caustica.client.RtRecommendedProfile.PRESET_F;
import static dev.comfyfluffy.caustica.client.RtRecommendedProfile.QUALITY;
import static dev.comfyfluffy.caustica.client.RtRecommendedProfile.ULTRA_PERFORMANCE;
import static dev.comfyfluffy.caustica.client.RtRecommendedProfile.UNCHANGED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtRecommendedProfileTest {
    private static RtRecommendedProfile.Hardware rtx40(int sms, boolean laptop, int w, int h) {
        return new RtRecommendedProfile.Hardware(true, true, true, sms, laptop, w, h);
    }

    private static RtRecommendedProfile.Hardware rtx30(int sms, boolean laptop, int w, int h) {
        return new RtRecommendedProfile.Hardware(true, false, true, sms, laptop, w, h);
    }

    @Test
    void theReferenceLaptopGetsItsOwnSettings() {
        RtRecommendedProfile.Profile p = RtRecommendedProfile.recommend(rtx40(20, true, 1920, 1080));
        assertTrue(p.useDlss());
        assertEquals(PERFORMANCE, p.dlssQuality());
        assertEquals(PRESET_F, p.dlssPreset());
        assertTrue(p.frameGeneration());
        assertTrue(p.reflex());
        assertTrue(p.sharc());
        assertTrue(p.fog());
        assertTrue(p.volumetricClouds());
    }

    @Test
    void aSharperLaptopScreenTracesAboutTheSamePixels() {
        // 2560x1600 on the reference GPU: Performance would trace twice the pixels.
        assertEquals(ULTRA_PERFORMANCE, RtRecommendedProfile.recommend(rtx40(20, true, 2560, 1600)).dlssQuality());
    }

    @Test
    void ultraPerformanceIsNeverPickedBelow1440p() {
        assertEquals(PERFORMANCE, RtRecommendedProfile.recommend(rtx30(20, true, 1920, 1080)).dlssQuality());
        assertEquals(PERFORMANCE, RtRecommendedProfile.recommend(
                new RtRecommendedProfile.Hardware(true, false, false, 0, false, 1920, 1200)).dlssQuality());
    }

    @Test
    void fasterCardsGetSharperModes() {
        assertEquals(BALANCED, RtRecommendedProfile.recommend(rtx40(24, false, 1920, 1080)).dlssQuality());
        assertEquals(QUALITY, RtRecommendedProfile.recommend(rtx40(76, false, 1920, 1080)).dlssQuality());
        assertEquals(QUALITY, RtRecommendedProfile.recommend(rtx40(128, false, 2560, 1440)).dlssQuality());
        // Capped speed: even the largest card stays on Performance at 4K, NVIDIA's own 4K recommendation.
        assertEquals(PERFORMANCE, RtRecommendedProfile.recommend(rtx40(170, false, 3840, 2160)).dlssQuality());
    }

    @Test
    void smallerScreensUseSharperModes() {
        assertEquals(BALANCED, RtRecommendedProfile.recommend(rtx40(20, true, 1600, 900)).dlssQuality());
        assertEquals(QUALITY, RtRecommendedProfile.recommend(rtx40(20, true, 1280, 720)).dlssQuality());
    }

    @Test
    void olderRtxCardsGetTheLighterLook() {
        RtRecommendedProfile.Profile p = RtRecommendedProfile.recommend(rtx30(82, false, 2560, 1440));
        assertTrue(p.useDlss());
        assertEquals(BALANCED, p.dlssQuality());
        assertEquals(UNCHANGED, p.dlssPreset());
        assertFalse(p.frameGeneration());
        assertFalse(p.fog());
        assertFalse(p.volumetricClouds());
        assertTrue(p.sharc());
    }

    @Test
    void withoutRayReconstructionTheUpscalerIsLeftAlone() {
        RtRecommendedProfile.Profile p = RtRecommendedProfile.recommend(
                new RtRecommendedProfile.Hardware(false, false, false, 0, false, 2560, 1440));
        assertFalse(p.useDlss());
        assertEquals(UNCHANGED, p.dlssQuality());
        assertEquals(UNCHANGED, p.dlssPreset());
        assertFalse(p.frameGeneration());
        assertFalse(p.reflex());
        assertTrue(p.sharc());
    }

    @Test
    void anUnknownSmCountCountsAsTheReference() {
        assertEquals(PERFORMANCE, RtRecommendedProfile.recommend(rtx40(0, false, 1920, 1080)).dlssQuality());
    }

    @Test
    void laptopNames() {
        assertTrue(RtRecommendedProfile.looksLikeLaptop("NVIDIA GeForce RTX 4050 Laptop GPU"));
        assertTrue(RtRecommendedProfile.looksLikeLaptop("NVIDIA GeForce RTX 2060 with Max-Q Design"));
        assertFalse(RtRecommendedProfile.looksLikeLaptop("NVIDIA GeForce RTX 4090"));
        assertFalse(RtRecommendedProfile.looksLikeLaptop(null));
    }
}
