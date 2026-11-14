package dev.comfyfluffy.caustica.rt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RtGpuTimerTest {
    @Test
    void passLabelsMapToRegisteredStages() {
        String[] labels = {"composite frame", "BLAS builds 101", "frame TLAS 3873 instances build",
                "trace rays (raygen 0)", "trace rays (raygen 1)", "trace rays (raygen 2)", "DLSS-RR evaluate", "NRD denoise",
                "SVGF denoise", "FSR upscale", "XeSS upscale", "fallback upscale", "exposure", "rt bloom",
                "map RT to display"};
        for (String label : labels) {
            String stage = RtGpuTimer.stageFor(label);
            assertTrue(stage != null && RtFrameStats.frameStageNames().contains(stage),
                    label + " -> " + stage + " must be a registered frame stage");
        }
        assertEquals("gpu.traceIndirect", RtGpuTimer.stageFor("trace rays (raygen 1)"));
        assertEquals("gpu.traceIndirect", RtGpuTimer.stageFor("trace rays (raygen 2)"));
        assertNull(RtGpuTimer.stageFor("block outline mask"));
    }
}
