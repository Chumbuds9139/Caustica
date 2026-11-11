package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The TDR guard's policy: never submit an unaffordable frame at full cost, shed load when the GPU falls
 * behind, and never punish image quality for a slow frame the GPU was not responsible for.
 */
final class RtLoadGovernorTest {
    private static final int TARGET = 350;

    /** Simulated GPU whose frame time is proportional to samples x bounces. */
    private static float cost(RtLoadGovernor g, float msPerUnit) {
        return g.spp() * g.bounces() * msPerUnit;
    }

    @Test
    void startsAtTheMinimumAndClimbsToTheConfiguredQualityOnAFastGpu() {
        RtLoadGovernor g = RtLoadGovernor.createForTest();
        g.update(true, 0.0f, 0.0f, TARGET, 4, 6);
        assertEquals(1, g.spp());
        assertEquals(2, g.bounces());
        for (int frame = 0; frame < 200; frame++) {
            float ms = cost(g, 0.5f); // 12 ms at 4 spp x 6 bounces
            g.update(true, ms, ms, TARGET, 4, 6);
            assertTrue(g.spp() <= 4 && g.bounces() <= 6, "never above the player's settings");
        }
        assertEquals(4, g.spp());
        assertEquals(6, g.bounces());
    }

    @Test
    void unaffordableSettingsAreNeverSubmittedAtFullCost() {
        // 8 spp x 8 bounces would cost 64 x 40 ms = 2.56 s: a guaranteed TDR without the guard.
        RtLoadGovernor g = RtLoadGovernor.createForTest();
        g.update(true, 0.0f, 0.0f, TARGET, 8, 8);
        float worst = 0.0f;
        for (int frame = 0; frame < 2000; frame++) {
            float ms = cost(g, 40.0f);
            worst = Math.max(worst, ms);
            g.update(true, ms, ms, TARGET, 8, 8);
        }
        assertTrue(worst <= TARGET, "the slow start must settle below the target, peak was " + worst + " ms");
        assertTrue(cost(g, 40.0f) <= TARGET * RtLoadGovernor.GROW_HEADROOM + 0.001f);
    }

    @Test
    void gpuBoundSlowFramesShedSamplesFirstThenBounces() {
        RtLoadGovernor g = RtLoadGovernor.createForTest();
        g.update(false, 0.0f, 0.0f, TARGET, 4, 4); // adopt the configured values
        g.update(true, 500.0f, 480.0f, TARGET, 4, 4);
        assertEquals(2, g.spp());
        assertEquals(4, g.bounces());
        g.update(true, 500.0f, 480.0f, TARGET, 4, 4);
        assertEquals(1, g.spp());
        g.update(true, 500.0f, 480.0f, TARGET, 4, 4);
        assertEquals(1, g.spp());
        assertEquals(3, g.bounces());
    }

    @Test
    void anEmergencyFrameDropsStraightToTheMinimum() {
        RtLoadGovernor g = RtLoadGovernor.createForTest();
        g.update(false, 0.0f, 0.0f, TARGET, 8, 8);
        g.update(true, 1500.0f, 1400.0f, TARGET, 8, 8);
        assertEquals(RtLoadGovernor.MIN_SPP, g.spp());
        assertEquals(RtLoadGovernor.MIN_BOUNCES, g.bounces());
    }

    @Test
    void cpuHitchesAndPausesDoNotCostQuality() {
        RtLoadGovernor g = RtLoadGovernor.createForTest();
        g.update(false, 0.0f, 0.0f, TARGET, 4, 6);
        // A 3 s gap (menu, chunk-load hitch) during which the render thread barely waited on the GPU.
        g.update(true, 3000.0f, 5.0f, TARGET, 4, 6);
        assertEquals(4, g.spp());
        assertEquals(6, g.bounces());
    }

    @Test
    void loweringASettingAppliesImmediatelyAndDisablingTheGuardRestoresTheConfiguredValues() {
        RtLoadGovernor g = RtLoadGovernor.createForTest();
        g.update(false, 0.0f, 0.0f, TARGET, 8, 8);
        g.update(true, 10.0f, 10.0f, TARGET, 2, 3);
        assertEquals(2, g.spp());
        assertEquals(3, g.bounces());
        g.update(true, 1500.0f, 1400.0f, TARGET, 2, 3);
        assertEquals(1, g.spp());
        g.update(false, 1500.0f, 1400.0f, TARGET, 2, 3);
        assertEquals(2, g.spp());
        assertEquals(3, g.bounces());
    }

    @Test
    void recoversAfterTheLoadEases() {
        RtLoadGovernor g = RtLoadGovernor.createForTest();
        g.update(false, 0.0f, 0.0f, TARGET, 2, 4);
        g.update(true, 1500.0f, 1400.0f, TARGET, 2, 4);
        assertEquals(1, g.spp());
        for (int frame = 0; frame < 200; frame++) {
            g.update(true, 16.0f, 12.0f, TARGET, 2, 4);
        }
        assertEquals(2, g.spp());
        assertEquals(4, g.bounces());
    }
}
