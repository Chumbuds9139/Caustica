package dev.comfyfluffy.caustica.rt.entity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    /** Mirrors {@code RtWeatherCapture.RAIN_ONSET}. */
    private static final float RAIN_ONSET = 0.18f;

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
                if (len < 1.0e-4f) {
                    continue; // the table centre is the degenerate cell — covered by its own test below
                }
                float sizeX = -deltaZ / len;
                float sizeZ = deltaX / len;
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
     * The table's exact centre is the column the camera stands in, where vanilla's {@code -deltaZ/dist}
     * is 0/0.
     *
     * <p>Vanilla tolerates the resulting NaN because the rasteriser silently drops a NaN vertex. Caustica
     * feeds these quads to a BLAS build instead, where a NaN corner poisons the acceleration structure
     * node and every ray tested against it degenerates — which is what produced the thin vertical sliver
     * that appeared after standing still long enough for the camera to settle into one cell. Assert the
     * capture substitutes a finite direction rather than inheriting vanilla's NaN.
     */
    @Test
    void tableCentreIsFiniteRatherThanVanillasNaN() throws IOException {
        // The raw vanilla expression really is NaN at the centre — this is the hazard being guarded.
        float degenerate = -0.0f / 0.0f;
        assertTrue(Float.isNaN(degenerate), "0/0 must be NaN, or this test is not testing anything");

        String src = Files.readString(source());
        assertTrue(src.contains("distance < 1.0e-4f"),
                "the degenerate centre cell must be detected before dividing");
        // A NaN reaching the BLAS is the actual failure mode, so make the reason unmissable in-source.
        assertTrue(src.contains("NaN"), "the centre-cell guard must explain the NaN/BLAS hazard");
    }

    /**
     * Rain must not appear while the sky still looks clear, nor linger once it has cleared.
     *
     * <p>Vanilla ramps {@code rainLevel} linearly and drives both the sky's overcast blend and the drops
     * from it, but the two have very different perceptual responses: at rain 0.05 the sky is blended only
     * 5% grey (invisible) while thin bright streaks are already unmistakable. Feeding both the same
     * number is what made rain start before the sky greyed and stop after it had gone blue again.
     */
    @Test
    void dropsAreHeldBackUntilTheSkyHasVisiblyGreyed() {
        assertEquals(0.0f, visualIntensity(0.0f), 1.0e-6f, "no rain means no drops");
        assertEquals(0.0f, visualIntensity(0.05f), 1.0e-6f,
                "a barely-started ramp must draw nothing: the sky is still blue here");
        assertEquals(0.0f, visualIntensity(RAIN_ONSET), 1.0e-6f, "the onset itself is still dry");
        assertEquals(1.0f, visualIntensity(1.0f), 1.0e-6f, "a full storm is at full strength");

        // Monotonic in between, so the ramp never flickers.
        float previous = -1.0f;
        for (int i = 0; i <= 100; i++) {
            float value = visualIntensity(i / 100.0f);
            assertTrue(value >= previous, "visual intensity must be monotonic in the rain level");
            previous = value;
        }
        // ...and genuinely eased rather than a hard switch at the onset.
        assertTrue(visualIntensity(RAIN_ONSET + 0.02f) < 0.05f,
                "drops must fade in smoothly just past the onset, not pop to full");
    }

    /** Mirrors {@code RtWeatherCapture.visualIntensity}. */
    private static float visualIntensity(float rainLevel) {
        float t = Math.max(0.0f, Math.min(1.0f, (rainLevel - RAIN_ONSET) / (1.0f - RAIN_ONSET)));
        return t * t * (3.0f - 2.0f * t);
    }

    /**
     * Distance fade must ride the ALPHA lane only.
     *
     * <p>An earlier version also scaled the vertex RGB by the fade to reproduce vanilla's falloff. But
     * tint is not opacity: {@code world.rchit} multiplies it straight into the albedo, so far columns
     * were rendered *darker* rather than thinner, and the step between two adjacent fade values showed up
     * as a visible seam — one patch of rain brighter than the rest. Pin the white RGB so this cannot
     * regress.
     */
    @Test
    void distanceFadeUsesCoverageNotTint() throws IOException {
        String src = Files.readString(source());

        assertTrue(src.contains("ARGB.white(alpha)"),
                "column colour must stay white: the fade belongs in alpha/coverage, not in the tint");
        assertFalse(src.contains("ARGB.color(level, level, level, level)"),
                "scaling RGB by the fade darkens distant rain instead of thinning it");
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
     * For particles and weather, zero coverage must mean invisible — not opaque.
     *
     * <p>{@code world.rahit} used to read the aux0 coverage lane as {@code aux0 == 0 ? 1.0 : aux0} for
     * every kind of geometry. That is right for model geometry, whose alpha byte comes from a vertex
     * colour some submit paths use 0 in as a "no tint" sentinel, but wrong for particles and weather,
     * whose alpha is vanilla's own blend weight and genuinely means transparency.
     *
     * <p>For rain the shared reading inverted the storm's own ramp. Coverage is {@code columnAlpha *
     * visualIntensity * rainDensity}, so as weather starts the multiplier passes through the
     * sub-representable range where the 8-bit lane rounds to zero; every column in it rendered at FULL
     * opacity, blanking the screen with a wall of sheets for about a second before the ramp climbed
     * clear of it — then again in reverse as the storm ended.
     */
    @Test
    void zeroParticleCoverageIsTransparentRatherThanOpaque() throws IOException {
        String rahit = Files.readString(anyHitShader());

        assertTrue(rahit.contains("bool particleKind = instanceKind == PARTICLE_BIT;"),
                "the coverage reading must distinguish particle/weather prims from model geometry");
        assertTrue(rahit.contains("if (!particleKind && epr.aux0 == 0u)"),
                "the zero-means-opaque sentinel must be confined to model geometry");
        assertFalse(rahit.contains("float primCoverage = epr.aux0 == 0u ? 1.0"),
                "reading a particle's zero coverage as opaque makes fading rain flash fully solid");
    }

    /**
     * The rain-density option (and the distance fade) must actually thin the streaks.
     *
     * <p>Both are fractional coverage multipliers, which only means anything against a stochastic test.
     * Under the fixed {@code ENTITY_ALPHA_CUTOFF} the fraction is all-or-nothing per texel: a drop core
     * has texture alpha ~1, so at 15% density it still evaluated {@code 1.0 * 0.15 > 0.1} and drew
     * exactly as it does in a full downpour — the reported "still shows up even configured to 15%" —
     * until the multiplier finally crossed the cutoff and the whole sheet vanished at once.
     */
    @Test
    void weatherCoverageIsDitheredSoDensityAndDistanceThinTheStreaks() throws IOException {
        String rahit = Files.readString(anyHitShader());

        assertTrue(rahit.contains("(epr.flags & PRIM_WEATHER) != 0u"),
                "weather sheets must be recognised in the any-hit alpha path");
        assertTrue(rahit.contains("stochasticAlpha = true;"),
                "weather coverage must route through the stochastic dither, not the fixed cutout");

        // The fraction must survive into the dither comparison as a multiplier on the texel alpha.
        assertTrue(rahit.contains("alphaDitherThreshold(salt) >= clamp(texel.a * primCoverage, 0.0, 1.0)"),
                "the dither must weigh the texel alpha by the primitive coverage");
    }

    /**
     * A column whose alpha rounds to zero in the 8-bit colour channel must not be emitted at all.
     *
     * <p>{@code ARGB.white} packs the fade into a byte, so anything below one channel step becomes
     * coverage 0 — geometry built, budgeted into the particle limit, uploaded and traced, only for every
     * ray to discard it. The guard is written against the channel step rather than {@code > 0} because
     * the intensity ramp spends its first moments inside exactly that sub-representable band.
     */
    @Test
    void columnsBelowOneAlphaStepAreNotEmitted() throws IOException {
        String src = Files.readString(source());

        assertTrue(src.contains("MIN_VISIBLE_ALPHA = 1.0f / 255.0f"),
                "the cull threshold must be one step of the 8-bit alpha channel the fade is packed into");
        assertTrue(src.contains("alpha < MIN_VISIBLE_ALPHA"),
                "columns quantising to zero coverage must be skipped before they reach the BLAS");
        assertFalse(src.contains("if (alpha <= 0.0f) {"),
                "a plain > 0 test still admits columns whose coverage byte rounds to zero");
    }

    private static Path anyHitShader() {
        return repoRoot().resolve("shaders/world/world.rahit.slang");
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
