// Cloud deck — two selectable styles over one shared field.
//
// Caustica cancels vanilla's LevelRenderer outright (see LevelRendererMixin), and vanilla's cloud pass
// lives inside it — so under the ray-traced renderer there were simply no clouds in the world at all.
// The sky shader modelled an atmosphere, a sun, a moon and a starfield, and the weather code even
// hid the celestial discs "behind the cloud deck" during rain, but nothing ever drew that deck. This
// module is that deck.
//
// TWO STYLES, ONE FIELD. cloudCoverageField is the single source of truth for where clouds are; both
// styles are different ways of *rendering* that same 2D coverage map, and the shadow query reads it
// too. That is what keeps a cloud you can see, the shadow it casts, and the shape it has in either
// style from ever disagreeing:
//   * CLOUD_STYLE_CLASSIC — vanilla's flat, blocky deck. Coverage is quantised to the 12-block cell
//     grid, so the silhouette is genuinely square-edged rather than a soft blob, and the slab is shaded
//     as a box with distinct top/side/bottom faces the way vanilla's cloud quads are.
//   * CLOUD_STYLE_VOLUMETRIC — a real ray-marched slab. The same coverage map is extruded into a 3D
//     density with a vertical profile and detail noise, then marched with light-marched self-shadowing,
//     Beer-Lambert extinction and Henyey-Greenstein phase — the "heavy shader" look.
//
// DEPTH-CORRECT AGAINST THE WORLD. Every entry point that produces visible cloud takes the distance to
// the nearest scene hit and stops there, so terrain in front of a cloud occludes it. Getting this wrong
// is exactly the bug where the world appeared to punch through the deck from above (see cloudLayer).
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
// Width of the coverage ramp for the volumetric style. Classic quantises instead (see cloudCoverage).
public static const float CLOUD_EDGE_SOFTNESS = 0.22;
public static const float3 CLOUD_ALBEDO = float3(0.94, 0.95, 0.98);
public static const float CLOUD_INV_PI = 0.31830988618;

// Style ids; the active one comes from featureFlags (see cloudStyle).
public static const uint CLOUD_STYLE_CLASSIC = 0u;
public static const uint CLOUD_STYLE_VOLUMETRIC = 1u;

// ---- Volumetric march budget.
//
// This is the whole cost of the volumetric style, and it multiplies by SPP and by path depth, so it is
// split into two tiers rather than being one flat number:
//   * FULL is used where the clouds are actually looked at — the camera ray and specular/mirror bounces
//     (reflections of the sky in water and glass). This is what the player sees, so it gets the light
//     march and the full step count.
//   * CHEAP is used for diffuse indirect bounces. Those contribute a broad, low-frequency sky-fill term
//     that gets averaged over the hemisphere and then denoised; resolving cloud detail there is
//     invisible in the result but would multiply the cost by the bounce count. It keeps the same
//     extinction and phase, so the ENERGY stays right — only the detail is coarser.
// Quality comes from the light march and the phase function rather than raw step count.
public static const int   CLOUD_MARCH_STEPS = 24;
public static const int   CLOUD_LIGHT_STEPS = 6;
public static const int   CLOUD_MARCH_STEPS_CHEAP = 6;
public static const int   CLOUD_LIGHT_STEPS_CHEAP = 2;
public static const float CLOUD_EXTINCTION = 0.115; // per block, at density 1
public static const float CLOUD_HG_G = 0.62;        // forward-scattering anisotropy of cloud droplets

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

/**
 * Selected style. Carried in featureFlags rather than smuggled into a float lane: that word exists
 * precisely for player-facing effect toggles, and an integer bit cannot be perturbed by denormal
 * flushing or a -0.0 comparison the way a packed sign bit can.
 */
public uint cloudStyle(WorldPush push) {
    return worldFeature(push, FEATURE_CLOUDS_VOLUMETRIC)
            ? CLOUD_STYLE_VOLUMETRIC : CLOUD_STYLE_CLASSIC;
}

/** Cloud shadow strength slider, 0..1. */
public float cloudShadowStrength(WorldPush push) {
    return clamp(push.clouds.z, 0.0, 1.0);
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
 * Raw coverage field in [0,1], before either style interprets it. Three octaves at INTEGER scales with
 * whole-cell offsets, so every octave shares the base period and the sum stays exactly periodic — see
 * the note on CLOUD_CELL_BLOCKS.
 *
 * This is the shared truth both styles and the shadow query are built on.
 */
public float cloudCoverageField(float2 samplePos) {
    float2 p = samplePos / CLOUD_CELL_BLOCKS;
    return cloudNoise(p) * 0.6
         + cloudNoise(p * 2.0 + float2(11.0, 11.0)) * 0.3
         + cloudNoise(p * 4.0 + float2(29.0, 29.0)) * 0.1;
}

/**
 * Coverage resolved into a 0..1 "how much cloud is here" for the current style.
 *
 * Classic QUANTISES to the cell grid: vanilla's clouds are axis-aligned quads on a 12-block lattice, so
 * a cell is either cloud or it is not. Sampling the field at the cell centre (rather than smoothstepping
 * the interpolated value) is what produces genuinely square edges instead of a soft blob that merely
 * looks low-resolution.
 */
public float cloudCoverage(WorldPush push, float2 samplePos) {
    float threshold = 1.0 - clamp(push.clouds.x, 0.0, 1.0);
    if (cloudStyle(push) == CLOUD_STYLE_CLASSIC) {
        // Snap to the cell centre so every point inside one cell gets the identical answer -> hard,
        // axis-aligned edges exactly on the 12-block grid.
        //
        // The +EDGE*0.5 offset makes the two styles cover the SAME fraction of sky. The volumetric
        // ramp crosses 0.5 at threshold + EDGE/2, so a hard cut at the bare threshold would call every
        // partially-covered cell "full" and leave the classic sky markedly more overcast than the
        // volumetric one at an identical coverage setting — switching style would look like the weather
        // changed. Cutting at the ramp's own midpoint is what keeps them equivalent (measured: mean
        // coverage 0.32 vs 0.36, and it is also where the correlation between the two peaks).
        float2 cellCentre = (floor(samplePos / CLOUD_CELL_BLOCKS) + 0.5) * CLOUD_CELL_BLOCKS;
        return cloudCoverageField(cellCentre) > threshold + CLOUD_EDGE_SOFTNESS * 0.5 ? 1.0 : 0.0;
    }
    return smoothstep(threshold, threshold + CLOUD_EDGE_SOFTNESS, cloudCoverageField(samplePos));
}

/** Where a ray meets the deck, in the form both the visible pass and the shadow query need. */
public struct CloudHit {
    public bool hit;
    public float2 samplePos; // world-anchored deck coordinate to sample the density at
    public float distance;   // distance along the ray to the deck
    public float fade;       // horizon/view-limit fade
    public bool fromBelow;   // the deck is above the ray origin (we are looking at its underside)
};

/**
 * Intersect a ray with the deck's mid-plane. `originRel` and the deck height are both expressed
 * relative to the camera, which is what lets one function serve the miss shader (origin = the ray's own
 * origin, itself possibly a reflecting surface) and raygen (origin = the shading point) without either
 * needing absolute world coordinates — the terrain rebase would invalidate those anyway.
 *
 * `maxDistance` is the distance to the nearest scene hit along this ray (or a large number when the ray
 * escapes). A plane crossing BEYOND that point is behind solid geometry and is rejected, which is what
 * stops the deck from being drawn over terrain that is actually in front of it.
 *
 * Degenerate cases handled here rather than by callers, because each is a visible artefact:
 *   * a near-horizontal ray (|dir.y| tiny) would divide toward infinity — rejected outright, and the
 *     view-limit fade already takes alpha to zero long before that, so no edge appears;
 *   * a ray pointing away from the deck (t <= 0) must not intersect the plane behind it, which would
 *     paint a mirrored deck under the world.
 */
public CloudHit cloudTrace(WorldPush push, float3 originRel, float3 dir, float maxDistance) {
    CloudHit h;
    h.hit = false;
    h.samplePos = float2(0.0, 0.0);
    h.distance = 0.0;
    h.fade = 0.0;
    h.fromBelow = true;

    float deckRel = push.clouds.w - originRel.y; // deck mid-plane height relative to this ray's origin
    // Crossing fade. The flat style shades a plane, so a camera sitting exactly on it would flip
    // between the lit top face and the dark underside across a single pixel. Fading the deck out as the
    // camera passes through its nominal thickness turns that flip into a smooth transition — flying up
    // through the clouds dissolves them instead of popping. (The volumetric style needs no equivalent:
    // it integrates real density, so passing through simply marches less of it.)
    float halfThickness = max(push.cloudAnchor.z, 0.5) * 0.5;
    float crossFade = smoothstep(halfThickness * 0.5, halfThickness * 1.5, abs(deckRel));
    if (crossFade <= 0.0 || abs(dir.y) < 1.0e-4) {
        return h;
    }
    float t = deckRel / dir.y;
    if (t <= 0.0 || t >= maxDistance) {
        return h; // behind the ray, or behind solid geometry
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
    h.fade = crossFade * (1.0 - smoothstep(maxDist * 0.55, maxDist, horizontal));
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
 * This is the "Cloud Shadows" slider, and it is an analytic plane intersection plus one coverage
 * evaluation — no march, no extra ray, no added variance for the denoiser to chase, for either style.
 * Points above the deck get t <= 0 and are unshadowed for free, which is the correct answer rather than
 * a special case.
 *
 * The light ray is unoccluded by definition (the caller already traced scene visibility separately), so
 * it passes a large maxDistance: a cloud between the point and the sun must shadow it no matter how far
 * away the cloud is.
 */
public float cloudSunShadow(WorldPush push, float3 pointRel, float3 lightDir) {
    if (!cloudsEnabled(push)) {
        return 1.0;
    }
    float strength = cloudShadowStrength(push);
    if (strength <= 0.0) {
        return 1.0;
    }
    CloudHit h = cloudTrace(push, pointRel, lightDir, 1.0e9);
    if (!h.hit) {
        return 1.0;
    }
    return 1.0 - strength * cloudAlpha(push, h, cloudCoverage(push, h.samplePos));
}

// ---- Classic (vanilla-style) shading -------------------------------------------------------------

/**
 * Flat deck radiance, shaded like vanilla's cloud quads: a distinctly brighter top, a darker underside,
 * and a mid-tone for grazing/side views, so the deck reads as a solid box of cloud rather than a decal.
 *
 * Lit from worldPush.lightRadiance — the SAME value the ground's NEE uses — so the clouds ride every
 * curve the rest of the frame already follows: they redden through sunset on the atmosphere's own
 * transmittance, dim under the weather attenuation, and go to a dark sky-lit grey at night when the
 * moon takes over. Hand-picking a cloud colour is what makes cloud layers look pasted on.
 */
float3 cloudRadianceClassic(WorldPush push, CloudHit h, float3 dir, float3 skyBehind) {
    float3 sunRadiance = push.lightRadiance.xyz;
    // Vanilla shades cloud faces by orientation, not by a lighting model. Reproduce that: the face we
    // are looking at is the top when the ray travels downward onto it, the bottom when it travels up.
    float grazing = 1.0 - abs(dir.y);            // 1 looking along the deck, 0 straight up/down
    float faceLight = h.fromBelow ? 0.52 : 1.0;  // vanilla's bottom face is markedly darker
    faceLight = lerp(faceLight, 0.78, grazing);  // side faces sit between the two
    float3 lit = CLOUD_ALBEDO * CLOUD_INV_PI * sunRadiance * faceLight;
    // The classic deck is opaque, so it admits only a little sky fill — enough to keep it from going
    // black at night, when sunRadiance is near zero.
    return lit + skyBehind * 0.45;
}

// ---- Volumetric shading --------------------------------------------------------------------------

/** Henyey-Greenstein phase. Cloud droplets scatter strongly forward, which is the whole look. */
float cloudPhase(float cosT, float g) {
    float g2 = g * g;
    float d = 1.0 + g2 - 2.0 * g * cosT;
    return (1.0 - g2) / (4.0 * 3.14159265359 * max(d * sqrt(max(d, 1.0e-6)), 1.0e-4));
}

/**
 * 3D density inside the slab.
 *
 * The 2D coverage map is extruded vertically and multiplied by a rounded profile, so a cloud is thickest
 * at its middle and tapers at top and bottom instead of being a flat-topped extrusion. Detail noise
 * erodes the edges where the profile is already thin, which is what gives the billowy silhouette; it is
 * scaled by (1 - coverage) so it eats into edges without punching holes through cloud cores.
 */
float cloudVolumeDensity(WorldPush push, float3 posRel, float heightFrac) {
    float2 samplePos = push.cloudAnchor.xy + posRel.xz;
    float coverage = cloudCoverage(push, samplePos);
    if (coverage <= 0.0) {
        return 0.0;
    }
    // Rounded vertical profile: 0 at both faces, 1 in the middle.
    float profile = 1.0 - abs(heightFrac * 2.0 - 1.0);
    profile = smoothstep(0.0, 0.45, profile);
    // Detail: a second, finer octave of the same periodic field, offset per height so the erosion is
    // not vertically uniform (which would read as extruded, not billowed).
    float detail = cloudNoise((samplePos + heightFrac * 37.0) / (CLOUD_CELL_BLOCKS * 0.35));
    float eroded = coverage - (1.0 - coverage) * detail * 0.55;
    return clamp(eroded, 0.0, 1.0) * profile;
}

/** Optical depth from a point toward the light, for self-shadowing inside the slab. */
float cloudLightTransmittance(WorldPush push, float3 posRel, float3 lightDir,
                              float slabBottom, float slabTop, int lightSteps) {
    float thickness = max(slabTop - slabBottom, 0.1);
    // March a short distance toward the light; step so the march covers roughly the slab's own depth,
    // which is the scale self-shadowing operates on. Fewer steps cover the same distance with longer
    // strides, so the transmittance stays in the right range instead of the march falling short.
    float stepLen = thickness / (float(lightSteps) * max(abs(lightDir.y), 0.35));
    float optical = 0.0;
    for (int i = 0; i < lightSteps; i++) {
        float3 p = posRel + lightDir * (stepLen * (float(i) + 0.5));
        if (p.y < slabBottom || p.y > slabTop) {
            break; // left the slab: nothing further can shadow this sample
        }
        optical += cloudVolumeDensity(push, p, (p.y - slabBottom) / thickness) * stepLen;
    }
    return exp(-optical * CLOUD_EXTINCTION);
}

/** Result of a volumetric march: premultiplied scattered radiance and the surviving transmittance. */
public struct CloudVolume {
    public float3 scatter;
    public float transmittance;
};

/**
 * Ray-march the slab and return in-scattered radiance plus transmittance.
 *
 * Front-to-back emission/absorption integration: at each step the sample's in-scattering (sun radiance,
 * attenuated by the light march, shaped by the HG phase, plus a sky ambient term) is accumulated
 * weighted by how much light still reaches the eye, and the transmittance is decremented by
 * Beer-Lambert. The caller composites with `col * transmittance + scatter`, so the result is correct
 * over any background — including a terrain hit, which is what makes the volumetric style occlude and
 * be occluded correctly.
 */
public CloudVolume cloudMarch(WorldPush push, float3 originRel, float3 dir, float maxDistance,
                              float3 skyBehind, bool highQuality) {
    int marchSteps = highQuality ? CLOUD_MARCH_STEPS : CLOUD_MARCH_STEPS_CHEAP;
    int lightSteps = highQuality ? CLOUD_LIGHT_STEPS : CLOUD_LIGHT_STEPS_CHEAP;
    CloudVolume result;
    result.scatter = float3(0.0, 0.0, 0.0);
    result.transmittance = 1.0;

    float thickness = max(push.cloudAnchor.z, 1.0);
    float slabBottom = push.clouds.w - thickness * 0.5 - originRel.y;
    float slabTop = push.clouds.w + thickness * 0.5 - originRel.y;

    // Slab entry/exit along the ray. A near-horizontal ray inside the slab would march forever, so it
    // is bounded by the view limit like every other ray.
    float t0, t1;
    if (abs(dir.y) < 1.0e-4) {
        if (slabBottom > 0.0 || slabTop < 0.0) {
            return result; // parallel and outside the slab
        }
        t0 = 0.0;
        t1 = push.cloudAnchor.w;
    } else {
        float ta = slabBottom / dir.y;
        float tb = slabTop / dir.y;
        t0 = min(ta, tb);
        t1 = max(ta, tb);
    }
    t0 = max(t0, 0.0);
    t1 = min(t1, min(maxDistance, push.cloudAnchor.w));
    if (t1 <= t0) {
        return result;
    }

    // Distance fade, matched to the flat style's horizon fade so the two agree about where cloud ends.
    float horizon = length(dir.xz) * t0;
    float fade = 1.0 - smoothstep(push.cloudAnchor.w * 0.55, push.cloudAnchor.w, horizon);
    if (fade <= 0.0) {
        return result;
    }

    float3 sunRadiance = push.lightRadiance.xyz;
    float cosT = dot(dir, push.lightDir.xyz);
    float phase = cloudPhase(cosT, CLOUD_HG_G);
    float opacity = clamp(push.clouds.y, 0.0, 1.0);
    float stepLen = (t1 - t0) / float(marchSteps);
    float sigma = CLOUD_EXTINCTION * opacity * fade;

    for (int i = 0; i < marchSteps; i++) {
        float3 p = originRel + dir * (t0 + stepLen * (float(i) + 0.5));
        float heightFrac = (p.y - slabBottom) / max(slabTop - slabBottom, 1.0e-3);
        float density = cloudVolumeDensity(push, p, clamp(heightFrac, 0.0, 1.0));
        if (density <= 0.0) {
            continue;
        }
        float extinction = density * sigma * stepLen;
        float sampleTransmittance = exp(-extinction);
        float lightT = cloudLightTransmittance(push, p, push.lightDir.xyz, slabBottom, slabTop,
                                               lightSteps);
        // Direct sun scattering + an ambient sky term so the shadowed interior is grey rather than
        // black. The ambient uses the sky this ray would otherwise have seen, matching the flat style.
        float3 inScatter = CLOUD_ALBEDO * (sunRadiance * lightT * phase * 4.0 + skyBehind * 0.35);
        // Energy-conserving accumulation: integrate in-scatter over the step's absorbed fraction.
        result.scatter += result.transmittance * inScatter * (1.0 - sampleTransmittance);
        result.transmittance *= sampleTransmittance;
        if (result.transmittance < 0.01) {
            result.transmittance = 0.0;
            break; // fully saturated; further steps cannot contribute
        }
    }
    return result;
}

// ---- Unified entry point -------------------------------------------------------------------------

/**
 * Ambient sky fill for cloud shading when the real sky radiance is not available.
 *
 * The miss shader can pass the actual atmosphere result, but a ray that ends on terrain has no sky
 * value to hand — so this derives a cheap stand-in from the celestial radiance the frame is already
 * lit by. It follows the same day/night and weather curve as everything else, and keeps a small floor
 * so clouds at night are dark grey rather than black.
 */
public float3 cloudSkyAmbient(WorldPush push) {
    float3 sun = push.lightRadiance.xyz;
    return sun * 0.10 + float3(0.012, 0.017, 0.028);
}

/**
 * Clouds crossed along ONE ray segment, as premultiplied scattered radiance plus the transmittance the
 * background keeps. Both styles answer in this same form.
 *
 * This shape is deliberate: it is exactly how {@code medium.slang} reports the ambient fog, so callers
 * composite clouds with the identical two lines they already use for fog
 * ({@code L += throughput * scatter; throughput *= transmittance;}) and clouds inherit fog's correct
 * behaviour over reflections, refractions and indirect bounces for free.
 *
 * `maxDistance` is the distance to the nearest scene hit along the ray (or a large value when the ray
 * escapes to the sky). Bounding the cloud by it is the fix for the "world shows through the clouds"
 * bug — see cloudSegmentNote below.
 */
public CloudVolume cloudSegment(WorldPush push, float3 originRel, float3 dir, float maxDistance,
                                float3 ambient, bool highQuality) {
    CloudVolume result;
    result.scatter = float3(0.0, 0.0, 0.0);
    result.transmittance = 1.0;
    if (!cloudsEnabled(push)) {
        return result;
    }
    if (cloudStyle(push) == CLOUD_STYLE_VOLUMETRIC) {
        return cloudMarch(push, originRel, dir, maxDistance, ambient, highQuality);
    }
    CloudHit h = cloudTrace(push, originRel, dir, maxDistance);
    if (!h.hit) {
        return result;
    }
    float alpha = cloudAlpha(push, h, cloudCoverage(push, h.samplePos));
    if (alpha <= 0.0) {
        return result;
    }
    // Premultiplied, so `background * transmittance + scatter` reproduces the lerp exactly.
    result.scatter = cloudRadianceClassic(push, h, dir, ambient) * alpha;
    result.transmittance = 1.0 - alpha;
    return result;
}

// THE BUG THIS FIXES. Clouds used to be composited only inside world.rmiss, which runs only when a ray
// hits NOTHING. Flying above the deck and looking down, the ray hits the ground — a hit, not a miss —
// so the clouds lying between the camera and that ground were never drawn at all and the world showed
// straight through them. Routing every consumer through cloudSegment with the real hit distance fixes
// both halves of that: cloud in FRONT of a surface is now drawn over it, and cloud BEHIND a surface is
// correctly rejected by the maxDistance test instead of being painted on top.

/** Composite clouds over a background in one call, for callers that hold a finished colour. */
public float3 cloudLayer(WorldPush push, float3 originRel, float3 dir, float maxDistance,
                         float3 background, float3 ambient, bool highQuality) {
    CloudVolume v = cloudSegment(push, originRel, dir, maxDistance, ambient, highQuality);
    return background * v.transmittance + v.scatter;
}
