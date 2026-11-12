package dev.comfyfluffy.caustica.rt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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

    @Test
    void variantsAreComparedWithTheBaselinesAroundThemSoLinearDriftCancels() {
        // The GPU slows down by 2 ms per step; the two features really cost 5 ms and 1 ms.
        List<RtPerfSweep.Result> results = List.of(
                result("your settings (baseline)", true, 40.0),
                result("fog off", false, 42.0 - 5.0),
                result("your settings (baseline)", true, 44.0),
                result("clouds off", false, 46.0 - 1.0),
                result("your settings (baseline)", true, 48.0));
        String report = RtPerfSweep.report(results, false);
        assertTrue(report.contains("fog off") && lineOf(report, "fog off").contains("-5.00"), report);
        assertTrue(lineOf(report, "clouds off").contains("-1.00"), report);
        assertTrue(lineOf(report, "clouds off").contains("noisy"),
                "a 1 ms difference between baselines 4 ms apart is within the noise: " + report);
        assertTrue(report.contains("drift 8.00 ms"), report);
    }

    private static RtPerfSweep.Result result(String name, boolean baseline, double indirectMs) {
        return new RtPerfSweep.Result(name, baseline, indirectMs, 1.0, indirectMs + 5.0, 50.0, 120);
    }

    private static String lineOf(String report, String name) {
        return report.lines().filter(l -> l.contains(name)).findFirst().orElse("");
    }
}
