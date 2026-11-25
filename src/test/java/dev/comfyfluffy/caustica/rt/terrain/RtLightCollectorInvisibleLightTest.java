package dev.comfyfluffy.caustica.rt.terrain;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtLightCollectorInvisibleLightTest {
    private static final int STRIDE = RtLightCollector.FLOATS_PER_LIGHT;

    @Test
    void lightBlockBecomesAnOutwardCubeOfSixLights() {
        IntArrayList blocks = new IntArrayList();
        blocks.add(RtLightCollector.packInvisibleLight(3, 4, 5, 15));
        blocks.add(RtLightCollector.packInvisibleLight(9, 9, 9, 0)); // level 0 gives no light
        FloatArrayList out = new FloatArrayList();

        RtLightCollector.appendInvisibleLights(out, blocks);

        assertEquals(6 * STRIDE, out.size());
        float[] r = out.toFloatArray();
        float[] normalSum = new float[3];
        for (int k = 0; k < 6; k++) {
            int b = k * STRIDE;
            float nx = r[b + 4], ny = r[b + 5], nz = r[b + 6];
            assertEquals(1.0f, Math.abs(nx) + Math.abs(ny) + Math.abs(nz), 0.0f, "axis-aligned unit normal");
            normalSum[0] += nx;
            normalSum[1] += ny;
            normalSum[2] += nz;
            // Each face sits on the cube's surface, outward from the block centre.
            assertEquals(3.5f + 0.25f * nx, r[b], 0.0f);
            assertEquals(4.5f + 0.25f * ny, r[b + 1], 0.0f);
            assertEquals(5.5f + 0.25f * nz, r[b + 2], 0.0f);
            // The stored area must equal what the shader derives from the half axes: 4 * |U x V|.
            float ux = r[b + 8], uy = r[b + 9], uz = r[b + 10];
            float vx = r[b + 12], vy = r[b + 13], vz = r[b + 14];
            float cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx;
            assertEquals(4.0f * (float) Math.sqrt(cx * cx + cy * cy + cz * cz), r[b + 3], 0.0f);
            // Both axes lie in the face plane.
            assertEquals(0.0f, ux * nx + uy * ny + uz * nz, 0.0f);
            assertEquals(0.0f, vx * nx + vy * ny + vz * nz, 0.0f);
            assertTrue(r[b + 16] > 0.0f && r[b + 17] > 0.0f && r[b + 18] > 0.0f);
        }
        assertEquals(0.0f, normalSum[0], 0.0f);
        assertEquals(0.0f, normalSum[1], 0.0f);
        assertEquals(0.0f, normalSum[2], 0.0f);
    }

    @Test
    void radianceScalesWithTheLightLevel() {
        FloatArrayList full = new FloatArrayList();
        FloatArrayList low = new FloatArrayList();
        IntArrayList one = new IntArrayList();
        one.add(RtLightCollector.packInvisibleLight(0, 0, 0, 15));
        RtLightCollector.appendInvisibleLights(full, one);
        one.set(0, RtLightCollector.packInvisibleLight(15, 15, 15, 5));
        RtLightCollector.appendInvisibleLights(low, one);
        assertEquals(full.getFloat(16) * 5.0f / 15.0f, low.getFloat(16), 1.0e-6f);
        assertEquals(15.5f - 0.25f, low.getFloat(0), 0.0f); // first face is -X of block (15,15,15)
    }
}
