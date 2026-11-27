package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtCalibrationTest {
    @Test
    void percentileOfTheFilledPart() {
        double[] values = {40.0, 10.0, 30.0, 20.0, 99.0, 99.0};
        // Only the first four count; the rest is unused space in the buffer.
        assertEquals(10.0, RtCalibration.percentile(values, 4, 0.0));
        assertEquals(30.0, RtCalibration.percentile(values, 4, 0.5));
        assertEquals(30.0, RtCalibration.percentile(values, 4, 0.75));
        assertEquals(40.0, RtCalibration.percentile(values, 4, 1.0));
        assertEquals(10.0, values[1], "the buffer is not sorted in place");
    }

    @Test
    void noValuesHaveNoPercentile() {
        assertTrue(Double.isNaN(RtCalibration.percentile(new double[4], 0, 0.5)));
    }
}
