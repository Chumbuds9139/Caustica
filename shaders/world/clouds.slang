// Flat, vanilla-style cloud deck.
//
// Caustica cancels vanilla's LevelRenderer outright (see LevelRendererMixin), and vanilla's cloud pass
// lives inside it — so under the ray-traced renderer there were simply no clouds in the world at all.
// The sky shader modelled an atmosphere, a sun, a moon and a starfield, and the weather code even
// hid the celestial discs "behind the cloud deck" during rain, but nothing ever drew that deck. This
// module is that missing deck.
//
// It is deliberately ONE module shared by two very different consumers:
//   * world.rmiss draws the deck the camera sees (and that reflections/refractions see, since every
//     escaping ray lands in the miss shader);
//   * world.rgen asks the same field whether the sun/moon is occluded at a shading point, which is what
//     makes clouds cast shadows on the ground.
// Because both read the identical density function, a cloud you can see and the shadow it casts can
// never disagree — the same invariant the renderer already keeps between the sun disc and the NEE sun.
//
// Only world_common is imported: world.rmiss must NOT pull in world_core (that module declares the
// raygen bindings), so every entry point here takes the WorldPush explicitly, exactly like
// world_common's own worldFeature/worldFlag accessors.

import world_common;

// ---- Pattern geometry. The field is EXACTLY periodic over CLOUD_CELL_BLOCKS * CLOUD_PERIOD_CELLS
// blocks (6144), because cloudHash wraps its cell index to that period. That is load-bearing, not
// decoration: RtComposite pushes a sample anchor already reduced modulo the same 6144 (both to fold in
// the wind scroll, which grows without bound with world time, and to keep the anchor a small float far
// from a Minecraft world border's 30M-block coordinates). Wrapping an aperiodic noise would teleport
// the whole sky the moment the anchor rolled over; wrapping a periodic one is seamless. The repeat is
// four times the deck's own view limit, so it is never visible in one frame.
public static const float CLOUD_CELL_BLOCKS = 12.0; // vanilla's cloud cell size
public static const int   CLOUD_PERIOD_CELLS = 512; // power of two, so the wrap below is a mask
public static const int   CLOUD_CELL_MASK = CLOUD_PERIOD_CELLS - 1;
// Width of the coverage ramp. Vanilla clouds are hard-edged quads; a small but non-zero ramp keeps the
// blocky read while giving the edge a pixel or two to resolve in, which is what stops distant cloud
// borders from crawling as the camera moves.
public static const float CLOUD_EDGE_SOFTNESS = 0.22;
public static const float3 CLOUD_ALBEDO = float3(0.94, 0.95, 0.98);
public static const float CLOUD_INV_PI = 0.31830988618;

/**
 * Whether this frame draws clouds at all. Coverage or opacity at zero answers false, so the player
 * sliders bottoming out costs one comparison rather than a wasted intersection, and every consumer
 * short-circuits to the exact pre-cloud result (bit-identical output when the feature is off).
 */
public bool cloudsEnabled(WorldPush push) {
    return push.dimension == DIMENSION_OVERWORLD
        && worldFeature(push, FEATURE_CLOUDS)
        && push.clouds.x > 0.0
        && push.clouds.y > 0.0;
}

// Integer cell hash. Integer mixing rather than the sky's float hash13: the sample coordinate here is
// a world position that can be thousands of blocks from the anchor, where a float hash starts losing
// mantissa bits and the pattern degenerates into stripes. The mask is what makes the field periodic.
float cloudHash(int2 cell) {
    uint2 c = uint2(uint(cell.x & CLOUD_CELL_MASK), uint(cell.y & CLOUD_CELL_MASK));
    uint h = c.x * 374761393u + c.y * 668265263u;
    h = (h ^ (h >> 13u)) * 1274126177u;
    h = h ^ (h >> 16u);
    return float(h & 0x00ffffffu) * (1.0 / 16777216.0);
}

// Value noise on the cell lattice, smoothstep-interpolated. floor/frac agree for negative coordinates
// (floor(-0.3) = -1, frac(-0.3) = 0.7), so the field is continuous across the origin.
float cloudNoise(float2 p) {
    int2 i = int2(floor(p));
    float2 f = frac(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = cloudHash(i);
    float b = cloudHash(i + int2(1, 0));
    float c = cloudHash(i + int2(0, 1));
    float d = cloudHash(i + int2(1, 1));
    return lerp(lerp(a, b, u.x), lerp(c, d, u.x), u.y);
}

/**
 * Cloud density in [0,1] at a world-anchored point on the deck.
 *
 * Three octaves give the puffy silhouette; the octave scales are INTEGER (2, 4) and the offsets are
 * whole cells, so every octave shares the base period and the whole sum stays exactly periodic — see
 * the note on CLOUD_CELL_BLOCKS. `coverage` slides the threshold: 0 leaves the sky clear, 1 closes it
 * over completely, and rain pushes it up on the Java side so a storm is genuinely overcast.
 */
public float cloudDensity(WorldPush push, float2 samplePos) {
    float2 p = samplePos / CLOUD_CELL_BLOCKS;
    float n = cloudNoise(p) * 0.6
            + cloudNoise(p * 2.0 + float2(11.0, 11.0)) * 0.3
            + cloudNoise(p * 4.0 + float2(29.0, 29.0)) * 0.1;
    float threshold = 1.0 - clamp(push.clouds.x, 0.0, 1.0);
    return smoothstep(threshold, threshold + CLOUD_EDGE_SOFTNESS, n);
}

/** Where a ray meets the deck, in the form both the visible pass and the shadow query need. */
public struct CloudHit {
    public bool hit;
    public float2 samplePos; // world-anchored deck coordinate to sample the density at
    public float distance;   // distance along the ray to the deck
    public float fade;       // slab + view-limit fade, already folded together
    public bool fromBelow;   // the deck is above the ray origin (we are looking at its underside)
};

/**
 * Intersect a ray with the deck plane. `originRel` and the deck height are both expressed relative to
 * the camera, which is what lets one function serve the miss shader (origin = the ray's own origin,
 * itself possibly a reflecting surface) and raygen (origin = the shading point) without either needing
 * absolute world coordinates — the terrain rebase would invalidate those anyway.
 *
 * Three degenerate cases are handled here rather than by the callers, because getting any of them
 * wrong is a visible artefact rather than a subtle error:
 *   * a near-horizontal ray (|dir.y| tiny) would divide toward infinity — rejected outright, and the
 *     view-limit fade below already takes the alpha to zero long before that, so no edge appears;
 *   * a ray pointing away from the deck (t <= 0) must not intersect the plane behind it, which is what
 *     would otherwise paint a mirrored cloud deck under the world;
 *   * a camera inside (or within half a deck of) the slab would flip between seeing the top face and
 *     the bottom face across one pixel, so the alpha is faded out through the crossing instead.
 */
public CloudHit cloudTrace(WorldPush push, float3 originRel, float3 dir) {
    CloudHit h;
    h.hit = false;
    h.samplePos = float2(0.0, 0.0);
    h.distance = 0.0;
    h.fade = 0.0;
    h.fromBelow = true;

    float deckRel = push.clouds.w - originRel.y; // deck height relative to this ray's origin
    float halfThickness = max(push.cloudAnchor.z, 0.5) * 0.5;
    float slab = smoothstep(halfThickness * 0.5, halfThickness * 1.5, abs(deckRel));
    if (slab <= 0.0 || abs(dir.y) < 1.0e-4) {
        return h;
    }
    float t = deckRel / dir.y;
    if (t <= 0.0) {
        return h;
    }
    float maxDist = max(push.cloudAnchor.w, 1.0);
    float horizontal = length(dir.xz) * t;
    if (horizontal >= maxDist) {
        return h;
    }
    h.hit = true;
    h.samplePos = push.cloudAnchor.xy + originRel.xz + dir.xz * t;
    h.distance = t;
    // The deck is a plane, so it reaches the horizon as an infinitely thin, infinitely dense band of
    // aliasing. Fading coverage out over the last 45% of the view limit replaces that band with open
    // sky, which is also how vanilla's finite cloud grid ends.
    h.fade = slab * (1.0 - smoothstep(maxDist * 0.55, maxDist, horizontal));
    h.fromBelow = deckRel > 0.0;
    return h;
}

/** Opacity the deck presents to a ray: density, the player's opacity slider, and the fades. */
public float cloudAlpha(WorldPush push, CloudHit h, float density) {
    if (!h.hit) {
        return 0.0;
    }
    return clamp(density * clamp(push.clouds.y, 0.0, 1.0) * h.fade, 0.0, 1.0);
}

/**
 * How much of the celestial (sun or moon) light survives the deck on its way to `pointRel`.
 *
 * This is the "Cloud Shadows" slider, and it is an analytic plane intersection plus one density
 * evaluation — no march, no extra ray, no added variance for the denoiser to chase. Points above the
 * deck get t <= 0 from cloudTrace and are therefore unshadowed for free, which is the correct answer
 * rather than a special case.
 */
public float cloudSunShadow(WorldPush push, float3 pointRel, float3 lightDir) {
    if (!cloudsEnabled(push)) {
        return 1.0;
    }
    float strength = clamp(push.clouds.z, 0.0, 1.0);
    if (strength <= 0.0) {
        return 1.0;
    }
    CloudHit h = cloudTrace(push, pointRel, lightDir);
    if (!h.hit) {
        return 1.0;
    }
    return 1.0 - strength * cloudAlpha(push, h, cloudDensity(push, h.samplePos));
}

/**
 * HDR radiance of the deck itself.
 *
 * Lit from worldPush.lightRadiance — the SAME value the ground's NEE uses — so the clouds ride every
 * curve the rest of the frame already follows: they redden through sunset on the atmosphere's own
 * transmittance, dim under the weather attenuation, and go to a dark sky-lit grey at night when the
 * moon takes over. Hand-picking a cloud colour instead is what makes cloud layers look pasted on.
 *
 * `skyBehind` is the sky radiance this ray would have returned with no clouds, reused as the ambient
 * fill: the underside of a cloud is mostly lit by the sky dome around it, so this is both cheaper and
 * better-behaved than a constant.
 */
public float3 cloudRadiance(WorldPush push, CloudHit h, float3 dir, float3 skyBehind, float density) {
    float3 sunRadiance = push.lightRadiance.xyz;
    float vdotl = clamp(dot(dir, push.lightDir.xyz), 0.0, 1.0);
    // Forward scattering: cloud water droplets scatter strongly toward the light, which is why the sky
    // around the sun stays bright and why thin cloud in front of it lights up.
    float forward = pow(vdotl, 8.0);
    float3 lit = CLOUD_ALBEDO * CLOUD_INV_PI * sunRadiance * (0.55 + 0.75 * forward);
    // Seen from below we are looking at the shadowed base; from above, at the sunlit top.
    float faceLight = h.fromBelow ? 0.45 : 1.0;
    // Thicker cloud transmits less, so the parts of the underside furthest from an edge read darkest.
    float depthDark = lerp(1.0, h.fromBelow ? 0.50 : 0.80, clamp(density, 0.0, 1.0));
    // Silver lining: thin cloud directly in front of the light. Gated on (1 - density) so it appears on
    // the edges, where the deck really is thin, instead of washing out the whole sunward half.
    float3 rim = CLOUD_ALBEDO * CLOUD_INV_PI * sunRadiance * pow(vdotl, 24.0) * (1.0 - density) * 0.8;
    return lit * faceLight * depthDark + skyBehind * 0.75 + rim;
}
