// Procedural portal surfaces for world.rchit: the Nether portal's animated swirl and the End
// portal's night sky (a deep midnight-blue dome under the overworld's own starfield). Kept fully
// self-contained (no imports) so world.rmiss can keep its own endSky/stars copies without an
// import cycle.

// Dave Hoskins' hash13 (the same hash world.rmiss uses for its starfields).
float portalHash13(float3 p3) {
    p3 = frac(p3 * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return frac((p3.x + p3.y) * p3.z);
}

// Cube-face projection shared by the starfield layers (same layout as world.rmiss's stars()).
float2 portalCubeUv(float3 dir, out float face) {
    float3 a = abs(dir);
    float2 uv;
    if (a.x >= a.y && a.x >= a.z)      { uv = dir.yz / a.x; face = dir.x > 0.0 ? 0.0 : 1.0; }
    else if (a.y >= a.z)               { uv = dir.xz / a.y; face = dir.y > 0.0 ? 2.0 : 3.0; }
    else                               { uv = dir.xy / a.z; face = dir.z > 0.0 ? 4.0 : 5.0; }
    return uv;
}

// ---- End portal night sky. A window into the overworld's night: a deep midnight-blue gradient
// (darkest overhead, lighter indigo toward the rim) under the SAME starfield the real night sky
// shows — identical per-cell placement, cube projection and celestial-axis rotation as
// world.rmiss's stars(), so the portal mirrors the sky's actual star pattern and wheels with the
// world's star angle. The old deep-purple void read as a flat magenta slab; the night sky is a
// visible blue dome with soft, self-lit stars. The background keeps the interpolated value noise
// (bilinear hash interpolation), which is continuous and never repeats in a tile/grid geometry.
static const float3 PORTAL_NIGHT_ZENITH  = float3(0.012, 0.030, 0.085); // deep midnight blue
static const float3 PORTAL_NIGHT_HORIZON = float3(0.050, 0.095, 0.190); // lighter indigo at the rim
// Star parameters match world.rmiss's STAR_CELLS / STAR_THRESHOLD / STAR_HALF, so each star lands
// exactly where the overworld sky puts it.
static const float  PORTAL_STAR_CELLS     = 21.0;
static const float  PORTAL_STAR_THRESHOLD = 0.86;
static const float  PORTAL_STAR_HALF      = 0.045;

// Smooth interpolated value noise over a coarse grid — the corners are hashed, the interior is
// bilinearly smoothed, so the result is organic mottling with no visible cells or dots.
float portalValueNoise(float2 p, float face) {
    float2 g = floor(p);
    float2 f = frac(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float n00 = portalHash13(float3(g, face));
    float n10 = portalHash13(float3(g + float2(1.0, 0.0), face));
    float n01 = portalHash13(float3(g + float2(0.0, 1.0), face));
    float n11 = portalHash13(float3(g + float2(1.0, 1.0), face));
    return lerp(lerp(n00, n10, u.x), lerp(n01, n11, u.x), u.y);
}

// Same rotation helper world.rmiss uses to wheel the sky starfield about the celestial pole.
float3 portalRotateAxis(float3 v, float3 axis, float ang) {
    float c = cos(ang), s = sin(ang);
    return v * c + cross(axis, v) * s + axis * dot(axis, v) * (1.0 - c);
}

float3 endPortalNightSky(float3 rayDir, float time, float4 celestial) {
    // Night gradient mirrors the overworld's ambient ramp (darker overhead, brighter at the rim).
    // `band` peaks when the portal is viewed edge-on, so the surface reads deepest when looked at
    // steeply and glows softly toward the rim — a dome, not a flat fill.
    float band = 1.0 - abs(rayDir.y);
    float3 col = lerp(PORTAL_NIGHT_ZENITH, PORTAL_NIGHT_HORIZON, band * band);

    // Faint blue mottling (three octaves of interpolated value noise, anchored to the cube face
    // like the original abyss) so the surface has organic depth without any grid or dot pattern.
    float face;
    float2 uv = portalCubeUv(rayDir, face);
    {
        float n = 0.0;
        float amp = 0.6;
        float2 np = uv * 2.5 + float2(face * 3.7, face * 1.3);
        [unroll]
        for (int octave = 0; octave < 3; octave++) {
            n += portalValueNoise(np, face + float(octave) * 7.31) * amp;
            np = np * 2.13 + 1.71;
            amp *= 0.5;
        }
        // n in roughly [0, 1.05]; scale into a very subtle blue depth wash.
        n = saturate(n);
        col += (0.004 + 0.012 * n) * float3(0.62, 0.78, 1.00);
    }

    // The overworld night starfield: same cells, placement, rotation and colours as world.rmiss's
    // stars(), so the portal shows the real sky's stars. Brighter than the sky copy (the sky dims
    // its stars by 0.2 * starBrightness) and given a slow per-star twinkle so the portal stays
    // alive without breaking the sky alignment with a drift.
    float3 sdir = portalRotateAxis(rayDir, normalize(celestial.xyz), -celestial.w);
    float starFace;
    float2 starUv = portalCubeUv(sdir, starFace);
    float2 g = starUv * PORTAL_STAR_CELLS;
    float3 id = float3(floor(g), starFace);
    if (portalHash13(id) < PORTAL_STAR_THRESHOLD) {
        float sz = PORTAL_STAR_HALF * (0.6 + 0.8 * portalHash13(id + 53.0));
        float inset = sz * 1.5;
        float2 center = inset + (1.0 - 2.0 * inset)
                * float2(portalHash13(id + 11.0), portalHash13(id + 23.0));
        float2 q = frac(g) - center;
        float ang = portalHash13(id + 91.0) * 6.2831853;
        float cs = cos(ang), sn = sin(ang);
        q = float2(cs * q.x - sn * q.y, sn * q.x + cs * q.y);
        float box = max(abs(q.x), abs(q.y));
        float star = 1.0 - smoothstep(sz * 0.6, sz, box);
        if (star > 0.0) {
            float bright = 0.55 + 0.45 * portalHash13(id + 71.0);
            float twinkle = 0.85 + 0.15 * sin(time * 1.6 + portalHash13(id + 173.0) * 6.2831853);
            float3 starCol = lerp(float3(0.78, 0.60, 0.42), float3(0.50, 0.62, 0.95),
                    portalHash13(id + 137.0));
            col += star * starCol * (0.55 + 0.55 * bright) * twinkle;
        }
    }
    return col;
}

// Nether portal swirl scroll, in texture widths per second. The block atlas's own animated frames
// play on top of this, so the swirl is guaranteed to move either way.
static const float NETHER_PORTAL_SCROLL_U = 0.012;
static const float NETHER_PORTAL_SCROLL_V = 0.008;
// Self-lit glow: the swirl's own colour radiates at luminance * this multiplier.
static const float NETHER_PORTAL_EMISSION = 6.0;
// Night-sky brightness: the gradient is used as-is; the emission carries the soft glow so the
// portal reads as a gently luminous starry dome instead of a black hole (or a flat magenta slab).
static const float END_PORTAL_BRIGHTNESS = 1.0;
static const float END_PORTAL_EMISSION = 2.5;
