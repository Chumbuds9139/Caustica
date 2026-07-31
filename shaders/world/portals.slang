// Procedural portal surfaces for world.rchit: the Nether portal's animated swirl and the End
// portal's cosmic abyss (deep void purple + starfield). Kept fully self-contained (no imports) so
// world.rmiss can keep its own endSky/stars copies without an import cycle; the two starfields
// intentionally share their hash, so retuning one should be mirrored in the other.

// Dave Hoskins' hash13 (the same hash world.rmiss uses for its starfields).
float portalHash13(float3 p3) {
    p3 = frac(p3 * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return frac((p3.x + p3.y) * p3.z);
}

// The End's own ambient fog tint (RtComposite.ambientFog for DIMENSION_END), so the abyss reads
// identically from every dimension instead of taking the overworld's (zeroed) fog lane.
static const float3 PORTAL_END_FOG = float3(0.010, 0.0055, 0.016);

// Cube-face projection shared by the starfield layers (same layout as world.rmiss's stars()).
float2 portalCubeUv(float3 dir, out float face) {
    float3 a = abs(dir);
    float2 uv;
    if (a.x >= a.y && a.x >= a.z)      { uv = dir.yz / a.x; face = dir.x > 0.0 ? 0.0 : 1.0; }
    else if (a.y >= a.z)               { uv = dir.xz / a.y; face = dir.y > 0.0 ? 2.0 : 3.0; }
    else                               { uv = dir.xy / a.z; face = dir.z > 0.0 ? 4.0 : 5.0; }
    return uv;
}

// ---- End portal abyss. A 1x1 portal quad subtends only a few star cells of the sky field, so the
// sky's own sparse starfield would read as a flat purple plane. The portal therefore layers a much
// denser star field (four times the sky's cell count) plus soft cyan/magenta nebula blotches over
// the same deep void gradient — the vanilla end_portal.png look — and everything drifts slowly so
// the abyss is alive ("bigger on the inside") without ever repeating across the portal.
static const float  PORTAL_STAR_CELLS     = 84.0;
static const float  PORTAL_STAR_THRESHOLD = 0.72; // 28% of cells carry a star
static const float  PORTAL_STAR_HALF      = 0.35; // large relative to the cell, like the vanilla texture
static const float  PORTAL_NEBULA_CELLS   = 5.0;
static const float  PORTAL_NEBULA_DENSITY = 0.55;
static const float3 PORTAL_VOID_DEEP  = float3(0.055, 0.020, 0.095);
static const float3 PORTAL_VOID_BAND  = float3(0.115, 0.045, 0.175);
static const float3 PORTAL_NEBULA_CYAN    = float3(0.30, 0.75, 1.00);
static const float3 PORTAL_NEBULA_MAGENTA = float3(0.85, 0.30, 0.95);

float3 portalNebula(float2 uv, float face) {
    float3 col = float3(0.0, 0.0, 0.0);
    // Two octaves of soft hash blobs: large cyan/magenta sheets and smaller bright cores.
    [unroll]
    for (int octave = 0; octave < 2; octave++) {
        float scale = octave == 0 ? PORTAL_NEBULA_CELLS : PORTAL_NEBULA_CELLS * 2.3;
        float2 g = uv * scale;
        float3 id = float3(floor(g), face + float(octave) * 7.0);
        float2 f = frac(g) - 0.5;
        float h = portalHash13(id);
        float3 tint = octave == 0
                ? lerp(PORTAL_NEBULA_CYAN, PORTAL_NEBULA_MAGENTA, h)
                : float3(1.0, 0.9, 1.0);
        float core = 1.0 - smoothstep(0.05, 0.38, length(f));
        float density = octave == 0 ? PORTAL_NEBULA_DENSITY : 0.22;
        col += tint * core * (0.25 + 0.75 * h) * density;
    }
    return col;
}

float3 endPortalAbyss(float3 rayDir, float time) {
    // View-parallax drift: the starfield shifts with the ray direction and creeps slowly, so the
    // abyss reads as a real volume instead of a flat decal.
    float3 dir = normalize(rayDir + float3(time * 0.010, -time * 0.014, time * 0.006));
    float face;
    float2 uv = portalCubeUv(dir, face);

    // Deep void gradient, brighter toward the horizontal band like the End sky.
    float band = 1.0 - abs(dir.y);
    float3 col = lerp(PORTAL_VOID_DEEP, PORTAL_VOID_BAND, band * band);
    col = lerp(col, PORTAL_END_FOG * 0.35, 0.35);

    // Nebula sheets (before the stars so stars sit on top).
    col += portalNebula(uv, face);

    // Dense star field: 28% of 84x84 cells per face — a 1x1 portal quad at a few blocks shows tens
    // of stars, reading as the vanilla texture's glittering void rather than a solid plane.
    float2 g = uv * PORTAL_STAR_CELLS;
    float3 id = float3(floor(g), face);
    if (portalHash13(id) >= PORTAL_STAR_THRESHOLD) {
        float sz = PORTAL_STAR_HALF * (0.45 + 0.55 * portalHash13(id + 53.0));
        float inset = sz * 0.8;
        float2 center = inset + (1.0 - 2.0 * inset)
                * float2(portalHash13(id + 11.0), portalHash13(id + 23.0));
        float2 q = frac(g) - center;
        float box = max(abs(q.x), abs(q.y));
        float star = 1.0 - smoothstep(sz * 0.55, sz, box);
        if (star > 0.0) {
            float3 starCol = lerp(float3(0.75, 0.80, 1.00), float3(1.0, 0.95, 1.0), portalHash13(id + 137.0));
            float bright = 0.5 + 0.5 * portalHash13(id + 71.0);
            col += star * starCol * (0.35 + 0.65 * bright);
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
// Abyss brightness: albedo boost and emission multiplier so the portal reads as a bright starry
// void even in a dark stronghold.
static const float END_PORTAL_BRIGHTNESS = 4.0;
static const float END_PORTAL_EMISSION = 8.0;
