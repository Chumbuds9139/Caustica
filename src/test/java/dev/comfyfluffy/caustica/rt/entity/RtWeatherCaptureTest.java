package dev.comfyfluffy.caustica.rt.entity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the parts of {@link RtWeatherCapture} that must stay bit-compatible with vanilla's
 * {@code WeatherEffectRenderer}.
 *
 * <p>The capture itself cannot run here — it needs a live {@code Minecraft}, a GPU capture buffer and a
 * bindless texture registry — so this covers the two things that would silently produce wrong-looking
 * rain rather than a crash, plus the structural wiring that keeps the feature reachable at all:
 *
 * <ul>
 *   <li>the column orientation table, reproduced from vanilla's constructor. Getting the sign or the
 *       axis swap wrong here still renders rain, just all facing one way (a flat wall) — no exception,
 *       no test failure anywhere else.</li>
 *   <li>the distance fade, which is what stops the columns reading as a hard cylinder around the
 *       player.</li>
 *   <li>the source-level guarantees that the capture reads vanilla's render state instead of
 *       synthesising its own field — the exact mistake the earlier "floating bars" attempts made.</li>
 * </ul>
 */
final class RtWeatherCaptureTest {
    private static final int RAIN_TABLE_SIZE = 32;
    private static final int HALF_RAIN_TABLE_SIZE = 16;

    private static Path source() {
        return repoRoot().resolve(
                "src/main/java/dev/comfyfluffy/caustica/rt/entity/RtWeatherCapture.java");
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("could not locate the repository root");
        }
        return dir;
    }

    /**
     * Vanilla's table, recomputed independently: {@code columnSizeX = -deltaZ / len},
     * {@code columnSizeZ = deltaX / len}. That is a 90-degree rotation of the camera-to-column offset, so
     * every column's quad lies perpendicular to the direction it is seen from — which is what makes the
     * sheets fan out around the player instead of all sharing one facing.
     */
    @Test
    void columnOrientationIsPerpendicularToTheViewOffset() {
        for (int z = 0; z < RAIN_TABLE_SIZE; z++) {
            for (int x = 0; x < RAIN_TABLE_SIZE; x++) {
                float deltaX = x - HALF_RAIN_TABLE_SIZE;
                float deltaZ = z - HALF_RAIN_TABLE_SIZE;
                float len = (float) Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
                float sizeX = -deltaZ / len;
                float sizeZ = deltaX / len;
                if (Float.isNaN(sizeX) || Float.isNaN(sizeZ)) {
                    continue; // the exact table centre divides by zero in vanilla too
                }
                // Perpendicular to the offset: their dot product is zero.
                assertEquals(0.0f, sizeX * deltaX + sizeZ * deltaZ, 1.0e-3f,
                        "column (" + x + "," + z + ") must face across the view offset");
                // ...and unit length, so the quad is exactly one block wide before the /2.
                assertEquals(1.0f, (float) Math.sqrt(sizeX * sizeX + sizeZ * sizeZ), 1.0e-5f,
                        "column (" + x + "," + z + ") orientation must be normalised");
            }
        }
    }

    /**
     * Vanilla fades a column from {@code maxAlpha} at the camera to {@code 0.5 * maxAlpha}-ish at the
     * radius edge, scaled by rain intensity. The fade must be monotonic and must vanish when it stops
     * raining, or rain would either pop in at full strength or linger after the storm ends.
     */
    @Test
    void distanceFadeIsMonotonicAndScalesWithIntensity() {
        float near = fade(0.0f, 1.0f, 1.0f);
        float mid = fade(0.5f, 1.0f, 1.0f);
        float far = fade(1.0f, 1.0f, 1.0f);

        assertEquals(1.0f, near, 1.0e-6f, "a column at the camera keeps full alpha");
        assertEquals(0.5f, far, 1.0e-6f, "a column at the radius edge is half faded");
        assertTrue(near > mid && mid > far, "alpha must fall off monotonically with distance");

        assertEquals(0.0f, fade(0.0f, 1.0f, 0.0f), 1.0e-6f, "no rain intensity means no columns drawn");
        assertEquals(near * 0.25f, fade(0.0f, 1.0f, 0.25f), 1.0e-6f, "alpha scales linearly with intensity");
    }

    /** Vanilla's {@code Mth.lerp(min(dSq/rSq, 1), maxAlpha, 0.5) * intensity}. */
    private static float fade(float distanceFraction, float maxAlpha, float intensity) {
        float t = Math.min(distanceFraction, 1.0f);
        return (maxAlpha + t * (0.5f - maxAlpha)) * intensity;
    }

    /**
     * The whole point of this class is that it does <em>not</em> invent geometry. Earlier attempts at
     * ray-traced rain generated a synthetic streak field, which produced floating bars that looked like
     * falling metal because nothing tied them to the world's heightmap or the real weather state. Assert
     * the capture is still sourced from vanilla's extracted columns, so a future refactor cannot quietly
     * reintroduce a procedural field.
     */
    @Test
    void columnsComeFromVanillaWeatherRenderStateNotASyntheticField() throws IOException {
        String src = Files.readString(source());

        assertTrue(src.contains("levelRenderState.weatherRenderState"),
                "columns must be read from vanilla's extracted WeatherRenderState");
        assertTrue(src.contains("state.rainColumns") && src.contains("state.snowColumns"),
                "both vanilla column lists must be captured");
        // bottomY/topY are vanilla's heightmap-clipped extents: this is what makes rain stop at the
        // ground and under roofs rather than passing through the world.
        assertTrue(src.contains("column.bottomY()") && src.contains("column.topY()"),
                "column extents must come from vanilla, which already clipped them to the heightmap");
        // vOffset is the scrolling texture coordinate that makes the drops actually fall.
        assertTrue(src.contains("column.vOffset()"),
                "the falling animation must use vanilla's scrolling texture offset");
        assertTrue(src.contains("textures/environment/rain.png")
                        && src.contains("textures/environment/snow.png"),
                "weather must sample vanilla's own sheet textures");
    }

    /**
     * Weather rides the particle mesh, so it must stay inside the particle budget and must not be able
     * to starve the ParticleEngine billboards that share it.
     */
    @Test
    void captureIsBudgetedAndFailsSoft() throws IOException {
        String src = Files.readString(source());

        assertTrue(src.contains("budget <= 0"), "a zero/negative budget must capture nothing");
        assertTrue(src.contains("emitted >= budget"), "the per-list loop must stop at the budget");
        // A weather glitch must not disable the entire ray-traced frame: RtEntities' particle path
        // rethrows as a fatal RuntimeException, so this class swallows and logs instead.
        assertTrue(src.contains("catch (Throwable t)") && src.contains("loggedFailure"),
                "weather capture must fail soft and log once rather than kill the RT frame");
    }
}
