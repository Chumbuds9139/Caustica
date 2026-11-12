package dev.comfyfluffy.caustica.rt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RtPerfSweepTest {
    @Test
    void medianIgnoresOutliersAndUnusedTail() {
        double[] values = {30.0, 31.0, 900.0, 29.0, 0.0, 0.0};
        assertEquals(30.5, RtPerfSweep.median(values, 4), 1e-9, "only the first count values are samples");
        assertEquals(31.0, RtPerfSweep.median(values, 3), 1e-9, "odd count takes the middle value");
        assertEquals(0.0, RtPerfSweep.median(values, 0), 1e-9);
    }

    @Test
    void reportWithoutResultsIsStillReadable() {
        String report = RtPerfSweep.INSTANCE.report();
        assertEquals(true, report.startsWith("Caustica performance test"));
    }
}
