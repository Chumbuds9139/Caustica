// Procedural portal surfaces for world.rchit: the Nether portal's animated swirl and the End
// portal's cosmic abyss (deep black void with a fine starfield). Kept fully self-contained (no
// imports) so world.rmiss can keep its own endSky/stars copies without an import cycle.

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

// ---- End portal abyss. A deep-black void with a fine, discrete starfield and a faint nebula
// hint — a real starry sky behind the portal. All values are kept dark: the albedo is used as-is
// (END_PORTAL_BRIGHTNESS is ~1), so the void stays black and only the star points carry light.
static const float  PORTAL_STAR_CELLS     = 64.0;  // 64x64 cells per cube face
static const float  PORTAL_STAR_THRESHOLD = 0.80;  // 20% of cells carry a star
static const float  PORTAL_STAR_HALF      = 0.09;  // small points (~0.15% of the face), like real stars
static const float3 PORTAL_VOID_DEEP      = float3(0.0025, 0.0012, 0.0065); // near-black void
static const float3 PORTAL_VOID_BAND      = float3(0.0070, 0.0035, 0.0140); // faint horizontal band
static const float  PORTAL_NEBULA_STRENGTH = 0.10; // very subtle color wash

float3 endPortalAbyss(float3 rayDir, float time) {
    // Gentle drift — a tiny fraction of the (unit) ray direction, so the starfield slowly creeps
    // without ever pinning all portal pixels to one direction (which made a single giant star).
    // sin/cos keep the offset bounded forever instead of growing linearly with the wrapped clock.
    float3 drift = float3(sin(time * 0.05) * 0.015, cos(time * 0.037) * 0.015, sin(time * 0.023) * 0.015);
    float3 dir = normalize(rayDir + drift);
    float face;
    float2 uv = portalCubeUv(dir, face);

    // Deep void gradient, near black; the horizontal band is only slightly lighter.
    float band = 1.0 - abs(dir.y);
    float3 col = lerp(PORTAL_VOID_DEEP, PORTAL_VOID_BAND, band * band);

    // Faint single-octave nebula: a soft, dark color wash (never a bright explosion).
    {
        float2 g = uv * 5.0;
        float3 nid = float3(floor(g), face);
        float h = portalHash13(nid);
        float3 tint = lerp(float3(0.06, 0.12, 0.17), float3(0.16, 0.06, 0.17), h);
        float2 f = frac(g) - 0.5;
        float core = 1.0 - smoothstep(0.15, 0.55, length(f));
        col += tint * core * PORTAL_NEBULA_STRENGTH * (0.4 + 0.6 * h);
    }

    // Fine star points: small, round, dim-to-moderate so they read as discrete stars on black.
    float2 g = uv * PORTAL_STAR_CELLS;
    float3 id = float3(floor(g), face);
    if (portalHash13(id) >= PORTAL_STAR_THRESHOLD) {
        float sz = PORTAL_STAR_HALF * (0.5 + 0.5 * portalHash13(id + 53.0));
        float inset = sz * 0.8;
        float2 center = inset + (1.0 - 2.0 * inset)
                * float2(portalHash13(id + 11.0), portalHash13(id + 23.0));
        float2 q = frac(g) - center;
        float star = 1.0 - smoothstep(sz * 0.6, sz, length(q)); // round point, not a box
        if (star > 0.0) {
            float3 starCol = lerp(float3(0.65, 0.72, 1.00), float3(1.0, 0.92, 0.98), portalHash13(id + 137.0));
            float bright = 0.5 + 0.5 * portalHash13(id + 71.0);
            col += star * starCol * (0.10 + 0.22 * bright);
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
// Abyss brightness: the void is used nearly as-is (dark), with a modest emission so the portal
// glows faintly instead of reading as a black hole.
static const float END_PORTAL_BRIGHTNESS = 1.0;
static const float END_PORTAL_EMISSION = 2.0;
