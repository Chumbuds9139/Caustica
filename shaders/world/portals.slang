// Procedural portal surfaces for world.rchit: the Nether portal's animated swirl and the End
// portal's cosmic abyss (deep void purple + starfield). Kept fully self-contained (no imports) so
// world.rmiss can keep its own endSky/stars copies without an import cycle; the two starfields
// intentionally share their constants and layout, so retuning one should be mirrored in the other.

// Dave Hoskins' hash13 (the same hash world.rmiss uses for its starfields).
float portalHash13(float3 p3) {
    p3 = frac(p3 * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return frac((p3.x + p3.y) * p3.z);
}

static const float PORTAL_STAR_CELLS     = 21.0;
static const float PORTAL_STAR_THRESHOLD = 0.86;
static const float PORTAL_STAR_HALF      = 0.045;

// Same palette as world.rmiss's END_SKY_DEEP / END_SKY_BAND / END_STAR_BRIGHTNESS.
static const float3 END_SKY_DEEP  = float3(0.020, 0.010, 0.035);
static const float3 END_SKY_BAND  = float3(0.055, 0.028, 0.080);
static const float  END_STAR_BRIGHTNESS = 0.55;
// The End's own ambient fog tint (RtComposite.ambientFog for DIMENSION_END), so the abyss reads
// identically from every dimension instead of taking the overworld's (zeroed) fog lane.
static const float3 PORTAL_END_FOG = float3(0.010, 0.0055, 0.016);

// Deep void purple with a dense, static starfield — a mirror of world.rmiss's endSky().
float3 endPortalSky(float3 dir, float3 fogColor) {
    float band = 1.0 - abs(dir.y);
    float3 col = lerp(END_SKY_DEEP, END_SKY_BAND, band * band);
    col = lerp(col, fogColor * 0.35, 0.35);
    float3 a = abs(dir);
    float2 uv;
    float face;
    if (a.x >= a.y && a.x >= a.z)      { uv = dir.yz / a.x; face = dir.x > 0.0 ? 0.0 : 1.0; }
    else if (a.y >= a.z)               { uv = dir.xz / a.y; face = dir.y > 0.0 ? 2.0 : 3.0; }
    else                               { uv = dir.xy / a.z; face = dir.z > 0.0 ? 4.0 : 5.0; }
    float2 g = uv * PORTAL_STAR_CELLS;
    float3 id = float3(floor(g), face);
    if (portalHash13(id) >= PORTAL_STAR_THRESHOLD) {
        float sz = PORTAL_STAR_HALF * (0.6 + 0.8 * portalHash13(id + 53.0));
        float inset = sz * 1.5;
        float2 center = inset + (1.0 - 2.0 * inset) * float2(portalHash13(id + 11.0), portalHash13(id + 23.0));
        float2 q = frac(g) - center;
        float box = max(abs(q.x), abs(q.y));
        float star = 1.0 - smoothstep(sz * 0.6, sz, box);
        if (star > 0.0) {
            // End stars are cold: a violet-white ramp rather than the Overworld's warm/blue mix.
            float3 starCol = lerp(float3(0.62, 0.55, 0.95), float3(0.90, 0.86, 1.0), portalHash13(id + 137.0));
            col += 0.2 * star * starCol * END_STAR_BRIGHTNESS * (0.55 + 0.45 * portalHash13(id + 71.0));
        }
    }
    return col;
}

// The End portal's abyss: the End sky sampled along the (view-parallax) ray, drifting slowly so the
// starfield is alive the way vanilla's end-portal shader is ("bigger on the inside").
float3 endPortalAbyss(float3 rayDir, float time) {
    float3 drift = float3(time * 0.004, -time * 0.006, time * 0.002);
    return endPortalSky(normalize(rayDir + drift), PORTAL_END_FOG);
}

// Nether portal swirl scroll, in texture widths per second. The block atlas's own animated frames
// play on top of this, so the swirl is guaranteed to move either way.
static const float NETHER_PORTAL_SCROLL_U = 0.012;
static const float NETHER_PORTAL_SCROLL_V = 0.008;
// Self-lit glow: the swirl's own colour radiates at luminance * this multiplier.
static const float NETHER_PORTAL_EMISSION = 6.0;
// Abyss brightness: albedo boost and emission multiplier so the portal reads as a bright starry
// void even in a dark stronghold.
static const float END_PORTAL_BRIGHTNESS = 10.0;
static const float END_PORTAL_EMISSION = 8.0;
