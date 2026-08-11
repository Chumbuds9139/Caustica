package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression guards for stable waterfall interfaces in both wavefront passes and their guides. */
final class RtWaterShaderRegressionTest {
    private static final Path REPO_ROOT = repoRoot();
    private static final Path WATER = REPO_ROOT.resolve("shaders/world/water.slang");
    private static final Path PRIMARY = REPO_ROOT.resolve("shaders/world/world_primary.rgen.slang");
    private static final Path WORLD = REPO_ROOT.resolve("shaders/world/world.rgen.slang");

    @Test
    void verticalVoxelClosureFacesTransmitInsteadOfBecomingDeltaMirrors() throws IOException {
        String water = Files.readString(WATER);
        String helper = slice(water, "public float stabilizeFallingWaterFresnel", "// ---- Water caustics.");

        assertInOrder(helper,
                "if (dot(transmittedDir, transmittedDir) <= 0.0)",
                "return 1.0;",
                "float freeSurface = smoothstep(0.45, 0.75, abs(nGeo.y));",
                "return fresnel * freeSurface;");
        assertTrue(water.contains("if (abs(nGeo.y) < 0.5) return nGeo;"),
                "block-aligned waterfall faces must remain identifiable by their geometric normal");
    }

    @Test
    void primaryPassStabilizesWaterBeforeReflectionGuidesAndBranchSplit() throws IOException {
        String primary = Files.readString(PRIMARY);
        String interfaceHandler = slice(primary,
                "float etaI = medium.current.ior;", "return continuation;");

        assertInOrder(interfaceHandler,
                "float F = fresnelDielectric",
                "float3 transmittedDir = refract",
                "if (isWater)",
                "F = stabilizeFallingWaterFresnel(F, geometricNormal, transmittedDir);",
                "gv_spec = makeSpecSurface",
                "float3(F, F, F)",
                "bool splitEligible",
                "throughput * F");
        assertTrue(interfaceHandler.contains("if (isWater) {\n"
                        + "            F = stabilizeFallingWaterFresnel"),
                "glass and ice must retain their physical dielectric Fresnel");
    }

    @Test
    void indirectPassUsesTheSameStableFresnelBeforeStochasticChoice() throws IOException {
        String world = Files.readString(WORLD);
        String interfaceHandler = slice(world,
                "if (material == MATERIAL_WATER || material == MATERIAL_DIELECTRIC)",
                "// A dielectric interface IS the specular event");

        assertInOrder(interfaceHandler,
                "float F = fresnelDielectric",
                "float3 transmittedDir = refract",
                "if (isWater)",
                "F = stabilizeFallingWaterFresnel(F, geometricNormal, transmittedDir);",
                "bool chooseReflection = rndf(seed) < F;");
        assertTrue(interfaceHandler.contains("if (isWater) {\n"
                        + "                F = stabilizeFallingWaterFresnel"),
                "secondary glass and ice paths must not receive the waterfall-only stabilization");
    }

    private static String slice(String source, String startNeedle, String endNeedle) {
        int start = source.indexOf(startNeedle);
        assertTrue(start >= 0, "missing shader snippet start: " + startNeedle);
        int end = source.indexOf(endNeedle, start);
        assertTrue(end > start, "missing shader snippet end: " + endNeedle);
        return source.substring(start, end);
    }

    private static void assertInOrder(String source, String... needles) {
        int at = -1;
        for (String needle : needles) {
            int next = source.indexOf(needle, at + 1);
            assertTrue(next > at, "expected snippet after index " + at + ": " + needle);
            at = next;
        }
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (Path candidate = dir; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("shaders/world"))
                    && Files.isDirectory(candidate.resolve("src/main/java"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not locate the repository root from " + dir);
    }
}
