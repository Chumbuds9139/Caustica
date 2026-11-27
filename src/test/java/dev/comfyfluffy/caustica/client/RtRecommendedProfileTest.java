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
    void theReferenceMeasurementsKeepPerformance() {
        // Two F8 baselines from the reference laptop at 1080p Performance, frame generation on.
        RtRecommendedProfile.Measurement before = new RtRecommendedProfile.Measurement(PERFORMANCE, 1920, 1080,
                37.65, (28.28 + 3.95) / 37.65);
        RtRecommendedProfile.Measurement after = new RtRecommendedProfile.Measurement(PERFORMANCE, 1920, 1080,
                28.72, (21.74 + 1.25) / 28.72);
        assertEquals(PERFORMANCE, RtRecommendedProfile.calibratedQuality(before, 1920, 1080, true));
        // Balanced would be about 37 ms here: inside the budget, but not with the room a sharper mode needs.
        assertEquals(PERFORMANCE, RtRecommendedProfile.calibratedQuality(after, 1920, 1080, true));
    }

    @Test
    void aFastMeasurementGetsASharperMode() {
        RtRecommendedProfile.Measurement fast = new RtRecommendedProfile.Measurement(PERFORMANCE, 1920, 1080,
                15.0, 0.85);
        assertEquals(QUALITY, RtRecommendedProfile.calibratedQuality(fast, 1920, 1080, true));
        // Without frame generation the budget is tighter.
        RtRecommendedProfile.Measurement medium = new RtRecommendedProfile.Measurement(PERFORMANCE, 1920, 1080,
                18.0, 0.85);
        assertEquals(BALANCED, RtRecommendedProfile.calibratedQuality(medium, 1920, 1080, false));
    }

    @Test
    void aSlowMeasurementDropsALevel() {
        RtRecommendedProfile.Measurement slow = new RtRecommendedProfile.Measurement(PERFORMANCE, 2560, 1440,
                50.0, 0.85);
        assertEquals(ULTRA_PERFORMANCE, RtRecommendedProfile.calibratedQuality(slow, 2560, 1440, true));
        // Below 1440p Performance is as light as it goes.
        RtRecommendedProfile.Measurement slow1080 = new RtRecommendedProfile.Measurement(QUALITY, 1920, 1080,
                80.0, 0.85);
        assertEquals(PERFORMANCE, RtRecommendedProfile.calibratedQuality(slow1080, 1920, 1080, true));
        assertEquals(PERFORMANCE, RtRecommendedProfile.lightestQuality(1080));
        assertEquals(ULTRA_PERFORMANCE, RtRecommendedProfile.lightestQuality(1440));
    }

    @Test
    void aMeasurementInASmallWindowKeepsItsMode() {
        // The default first-launch window on a 1440p screen: too small to predict the full screen from.
        RtRecommendedProfile.Measurement small = new RtRecommendedProfile.Measurement(BALANCED, 854, 480,
                10.0, 0.85);
        assertFalse(RtRecommendedProfile.measuredNearTarget(small, 2560, 1440));
        assertEquals(BALANCED, RtRecommendedProfile.calibratedQuality(small, 2560, 1440, true));
        // A maximised window a pixel off the monitor's size counts as the screen.
        RtRecommendedProfile.Measurement maximised = new RtRecommendedProfile.Measurement(PERFORMANCE, 1921, 1080,
                30.0, 0.85);
        assertTrue(RtRecommendedProfile.measuredNearTarget(maximised, 1920, 1080));
    }

    @Test
    void heapAdvice() {
        long gib = RtRecommendedProfile.GIB;
        // The launcher's default 2 GiB with GC busy: at least 4 GiB.
        assertEquals(4, RtRecommendedProfile.suggestedHeapGib(0.08, 0.5, 2 * gib, 16 * gib));
        // Half again, when the heap after GC stays nearly full.
        assertEquals(6, RtRecommendedProfile.suggestedHeapGib(0.01, 0.9, 4 * gib, 16 * gib));
        // Never more than half the computer's memory, so nothing when the heap is already there.
        assertEquals(0, RtRecommendedProfile.suggestedHeapGib(0.08, 0.9, 8 * gib, 16 * gib));
        assertEquals(4, RtRecommendedProfile.suggestedHeapGib(0.08, 0.9, 2 * gib, 8 * gib));
        // No shortage, or unknown sizes: no advice.
        assertEquals(0, RtRecommendedProfile.suggestedHeapGib(0.01, 0.5, 2 * gib, 16 * gib));
        assertEquals(0, RtRecommendedProfile.suggestedHeapGib(0.01, Double.NaN, 2 * gib, 16 * gib));
        assertEquals(0, RtRecommendedProfile.suggestedHeapGib(0.08, 0.9, 2 * gib, 0L));
    }

    @Test
    void laptopNames() {
        assertTrue(RtRecommendedProfile.looksLikeLaptop("NVIDIA GeForce RTX 4050 Laptop GPU"));
        assertTrue(RtRecommendedProfile.looksLikeLaptop("NVIDIA GeForce RTX 2060 with Max-Q Design"));
        assertFalse(RtRecommendedProfile.looksLikeLaptop("NVIDIA GeForce RTX 4090"));
        assertFalse(RtRecommendedProfile.looksLikeLaptop(null));
    }
}
