package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the denoiser contracts that span two files and would otherwise break silently.
 *
 * <p>Nothing here re-implements the maths; each test pins an invariant that a compiler cannot see:
 * the NRD material demodulation is split across a Slang shader (which divides) and a GLSL shader
 * (which multiplies back), and SVGF's temporal and spatial stages agree on where the accumulated
 * frame count lives. A drift in either place still compiles and still runs — it just produces a
 * wrong image, which is exactly the class of bug this suite exists to catch.
 */
final class RtDenoiserShaderRegressionTest {
    private static final Path REPO_ROOT = repoRoot();
    private static final Path WORLD_CORE = REPO_ROOT.resolve("shaders/world/world_core.slang");
    private static final Path WORLD_RGEN = REPO_ROOT.resolve("shaders/world/world.rgen.slang");
    private static final Path NRD_COMBINE = REPO_ROOT.resolve("shaders/display/nrd_combine.comp");
    private static final Path SVGF_REPROJECT = REPO_ROOT.resolve("shaders/display/svgf_reproject.comp");
    private static final Path SVGF_ATROUS = REPO_ROOT.resolve("shaders/display/svgf_atrous.comp");
    private static final Path NRD_DENOISER =
            REPO_ROOT.resolve("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtNrdDenoiser.java");

    /**
     * The demodulation floors must agree between the shader that divides the material out and the
     * one that multiplies it back. If they drift, the round trip stops being the identity and the
     * denoised image comes out with the wrong albedo — subtly, and only on the NRD path.
     */
    @Test
    void nrdMaterialFactorConstantsMatchBetweenSlangAndGlsl() throws IOException {
        Map<String, String> slang = scan(Files.readString(WORLD_CORE),
                Pattern.compile("public\\s+static\\s+const\\s+float\\s+(NRD_\\w*FACTOR_MIN_SCALE)\\s*=\\s*([\\d.]+)\\s*;"));
        Map<String, String> glsl = scan(Files.readString(NRD_COMBINE),
                Pattern.compile("const\\s+float\\s+(NRD_\\w*FACTOR_MIN_SCALE)\\s*=\\s*([\\d.]+)\\s*;"));

        assertFalse(slang.isEmpty(), "no NRD material factor constants found in " + WORLD_CORE);
        assertEquals(slang, glsl,
                "NRD demodulation floors differ between world_core.slang and nrd_combine.comp");
    }

    /**
     * Demodulation and re-modulation must be derived from the SAME guide textures. Reading a value
     * on one side that the other cannot see (anything internal to the tracer) is what would make
     * the round trip inexact, so both sides are pinned to the albedo/specular-albedo/roughness
     * guides.
     */
    @Test
    void nrdSignalsAreDemodulatedByTheTracerAndRemodulatedByTheCombinePass() throws IOException {
        String raygen = Files.readString(WORLD_RGEN);
        String combine = Files.readString(NRD_COMBINE);

        assertTrue(raygen.contains("nrdMaterialFactors(albedoGuide, specAlbedoGuide, rough, diffFactor, specFactor)"),
                "the tracer must build the demodulation factors from the guide textures");
        assertTrue(raygen.contains("diffRad /= diffFactor;") && raygen.contains("specRad /= specFactor;"),
                "the tracer must divide the per-lobe radiance by the material factors");
        assertTrue(combine.contains("diffRadiance * diffFactor + specRadiance * specFactor"),
                "the combine pass must multiply the material factors back after denoising");
        assertTrue(combine.contains("void nrdMaterialFactors("),
                "the combine pass must mirror nrdMaterialFactors rather than approximating it");
    }

    /**
     * NRD is strict about its projection contract: the matrices must arrive non-jittered and
     * unflipped, because {@code DecomposeProjection} performs its own handedness conversion.
     * Re-introducing the old Y-flip is the single easiest way to break REBLUR's reprojection, and
     * it fails invisibly (history reprojects to a mirrored position), so pin its absence.
     */
    @Test
    void nrdProjectionIsNotHandednessFlippedByTheHost() throws IOException {
        String denoiser = Files.readString(NRD_DENOISER);

        assertFalse(denoiser.contains("nrdViewToClip.m11(-"),
                "NRD converts handedness itself; pre-flipping the projection makes it flip twice");
        assertTrue(denoiser.contains("nrdViewToClip.m22(0f)")
                        && denoiser.contains("nrdViewToClip.m23(NRD_PROJECTION_NEAR)"),
                "the degenerate float-Z depth row must still be sanitized for NRD's world-space scales");
    }

    /**
     * REBLUR's history must survive a terrain rebase. The denoiser therefore takes the camera in
     * absolute world coordinates plus the current anchor and re-expresses the previous frame
     * against that anchor; passing anchor-relative coordinates alone (what the retired integration
     * did) makes every rebase look like a teleport and drops the history mid-motion.
     */
    @Test
    void nrdCompensatesTerrainRebaseInsteadOfResettingOnCameraMotion() throws IOException {
        String denoiser = Files.readString(NRD_DENOISER);
        String composite = Files.readString(
                REPO_ROOT.resolve("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java"));

        assertTrue(denoiser.contains("double camWorldX, double camWorldY, double camWorldZ,")
                        && denoiser.contains("double anchorX, double anchorY, double anchorZ,"),
                "the denoiser needs both the absolute camera and the anchor to compensate a rebase");
        assertTrue(composite.contains("camX, camY, camZ,")
                        && composite.contains("terrain.blockX, terrain.blockY, terrain.blockZ,"),
                "RtComposite must pass the absolute camera position and the terrain anchor");
        assertTrue(denoiser.contains("TELEPORT_JUMP_BLOCKS"),
                "only a real teleport may drop REBLUR's history");
    }

    /**
     * SVGF's accumulated frame count lives in the moments texture, NOT in the history's alpha,
     * because the à-trous feedback overwrites the whole history image. Moving it back into the
     * history alpha would make the count read as variance (and vice versa) with no compile error.
     */
    @Test
    void svgfFrameCountLivesInTheMomentsTexture() throws IOException {
        String reproject = Files.readString(SVGF_REPROJECT);
        String atrous = Files.readString(SVGF_ATROUS);

        assertTrue(reproject.contains("imageStore(momentsOut, pix, vec4(moments, frames, 0.0));"),
                "the reprojection must store the frame count in the moments texture");
        assertTrue(atrous.contains("float frames = max(imageLoad(moments, pix).z, 0.0);"),
                "the wavelet must read the frame count from the moments texture");
        assertFalse(atrous.contains("histLen"),
                "the frame count no longer travels in the history image's alpha channel");
    }

    /**
     * The two properties that make SVGF stable rather than smeary: history is rejected on GEOMETRY
     * (depth + normal from the previous frame) instead of clamped on colour, and the wavelet's
     * luminance edge-stop is scaled by the estimated standard deviation. Losing either turns the
     * filter back into the fixed-sigma blur + colour-clamped TAA pair this replaced.
     */
    @Test
    void svgfValidatesHistoryOnGeometryAndDrivesTheFilterWithVariance() throws IOException {
        String reproject = Files.readString(SVGF_REPROJECT);
        String atrous = Files.readString(SVGF_ATROUS);

        assertTrue(reproject.contains("bool reprojectionValid(")
                        && reproject.contains("imageLoad(prevViewZ, prevPix).r")
                        && reproject.contains("imageLoad(prevNormal, prevPix).xyz"),
                "history must be validated against the previous frame's depth and normal");
        assertTrue(reproject.contains("moments.y - moments.x * moments.x"),
                "variance must come from the accumulated luminance moments");
        assertTrue(atrous.contains("pc.phiLuminance * sqrt(max(filteredVar, 1.0e-8))"),
                "the luminance edge-stop must be scaled by the estimated standard deviation");
        assertTrue(atrous.contains("varianceSum += w * w * vt;"),
                "variance must propagate through the squared filter weights");
    }

    private static Map<String, String> scan(String source, Pattern pattern) {
        Map<String, String> found = new LinkedHashMap<>();
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            found.put(matcher.group(1), matcher.group(2));
        }
        return found;
    }

    /** Same root discovery as the sibling shader tests, kept local to avoid coupling them. */
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
