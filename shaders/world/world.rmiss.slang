// Ray miss = sky. Per-frame sky data comes from the WorldPush BDA buffer addressed by the 8-byte push
// constant. The shader writes full sky radiance (Rayleigh/Mie/ozone atmosphere + transmittance-tinted
// sun/moon discs + soft horizon + starfield) into payload.albedo; raygen consumes that value for both
// accumulated radiance and bounce-0 denoiser guides.
//
// Three skyboxes live here, selected by worldPush.dimension. Only the Overworld runs the atmosphere
// march and the celestial cycle; the Nether and the End have no sun or moon at all, so they answer with
// their own closed-form skybox (netherSky / endSky) and never touch sunDir, the starfield, or the
// celestials atlas. Splitting on the dimension rather than trying to fold the other two into the
// atmosphere model is deliberate: neither has an atmosphere, and both are lit ambiently by the sky
// dome itself, which is exactly what a miss shader returns.
//
// Overworld weather (rain/thunder) is applied to the atmosphere result rather than baked into the
// march: worldPush.weather.z is the single sky-darkening factor Java also derives its NEE light
// attenuation from, so the sky and the light on the ground dim together.
//
// Celestial discs (sun/moon) are gated by a packed payload flag, which raygen sets per-ray from the path
// state: true on the primary ray and after every specular/dielectric bounce (so the sun/moon show up in
// water/glass/metal reflections + refractions), false after a diffuse vertex did sun/moon NEE (so the
// disc isn't double-counted against the direct light → fireflies). The atmosphere gradient + stars are
// added on every miss (any bounce): stars aren't a NEE light, so they never double-count.
import world_common;
import clouds;

[[vk::push_constant]] WorldPushConstants pc;

// Vanilla celestials atlas (sun + moon-phase sprites), bound by RtComposite. Sampled with an explicit
// LOD (no derivatives in a miss shader). The sun/moon discs are drawn from its real texels.
[[vk::binding(9, 0)]] Sampler2D celestialsAtlas;

static const float PI = 3.14159265359;
float pow2(float x) { return x * x; }

// Near-white top-of-atmosphere disc radiances: the visible warm/red tint comes from multiplying by
// transmittanceToSpace(dir) at draw time — at noon the sun reads warm-white, at the horizon it reddens
// on exactly the sky's own sunset curve.
static const float3 SUN_DISC_RADIANCE  = float3(24.0, 23.0, 21.5);
static const float3 MOON_DISC_RADIANCE = float3(1.7, 1.85, 2.2) / 4.0; // lit-side moon (HDR, bright at night exposure)
// Vibrance applied to the atmosphere in-scatter only (not discs/stars/night ambient): luminance-
// preserving, so GI energy is unchanged. Soft-cinema: lowered from 1.2 to avoid "azul caneta" saturation.
static const float SKY_SATURATION = 0.88;
// VISIBLE disc half-angles, matched to vanilla: the sun/moon quads are drawn at half-width 30/20 at
// distance 100 → half-angle = atan(0.30) / atan(0.20). Decoupled from the NEE light radius (≈0.6°).
static const float SUN_DISC_HALF_ANGLE  = 0.29146; // atan(30/100), vanilla sun size
static const float MOON_DISC_HALF_ANGLE = 0.19740; // atan(20/100), vanilla moon size
// Faint night-sky ambient so the night isn't pure black where the atmosphere in-scatter falls to zero.
static const float3 NIGHT_ZENITH  = float3(0.004, 0.008, 0.022) / 5.0;
static const float3 NIGHT_HORIZON = float3(0.015, 0.022, 0.045) / 5.0;

// Soft-cinema sky palette requested: zenith pastel cyan ~ (0.42,0.62,0.82), horizon whitish desaturated.
static const float3 SKY_ZENITH_SOFT  = float3(0.42, 0.62, 0.82);
static const float3 SKY_HORIZON_SOFT = float3(0.78, 0.86, 0.94);

// ---- Physically-based atmosphere (Nishita / O'Neil single scattering + ozone absorption). A view ray
// is marched through the atmospheric shell; at each step the transmittance toward the sun is integrated
// (a second, shorter march), giving Rayleigh (blue-sky / red-sunset) + Mie (sun halo) in-scattered
// radiance. World up = +Y; the camera sits a couple km above a spherical planet. Output is HDR in the
// same units as tracePath.
//
// Soft Cinema tuning:
// - RAY_BETA blue reduced (22.4e-6 -> 15.5e-6) to desaturate "pen blue".
// - MIE_BETA increased for more horizon haze / Mie halo around sun.
// - Scale heights raised to lift zenith brightness and smooth horizon->zenith gradient.
// - MIE_G 0.758 -> 0.80 for broader forward scattering.
static const float  PLANET_R   = 6371000.0;
static const float  ATMOS_R    = 6471000.0;        // 100 km shell
static const float3 RAY_BETA   = float3(5.8e-6, 12.5e-6, 15.5e-6); // desaturated Rayleigh
static const float  MIE_BETA   = 38.0e-6;          // more Mie haze / whitish horizon
static const float  RAY_SCALE_H = 10000.0;         // taller Rayleigh -> brighter zenith
static const float  MIE_SCALE_H = 1650.0;          // taller Mie -> softer horizon
static const float  MIE_G      = 0.80;             // stronger forward scattering (sun halo)
// Ozone: absorption only (no scattering), tent-shaped density peaking at 25 km. Its green-heavy
// absorption keeps the zenith deep blue at noon and turns twilight purple-orange.
static const float3 OZONE_BETA = float3(0.650e-6, 1.881e-6, 0.085e-6);
static const float  OZONE_CENTER_H = 25000.0;
static const float  OZONE_WIDTH_H  = 15000.0;
static const float  SUN_INTENSITY = 22.0;
static const int    ATMOS_PRIMARY_STEPS = 16;
static const int    ATMOS_LIGHT_STEPS   = 8;

float ozoneDensity(float h) { return max(0.0, 1.0 - abs(h - OZONE_CENTER_H) / OZONE_WIDTH_H); }

// Smaller root of ray vs sphere of radius r centred at origin (no-hit handled by x > y).
float2 raySphere(float3 o, float3 d, float r) {
    float b = dot(o, d);
    float c = dot(o, o) - r * r;
    float disc = b * b - c;
    if (disc < 0.0) return float2(1.0e9, -1.0e9);
    disc = sqrt(disc);
    return float2(-b - disc, -b + disc);
}

float3 atmosphere(float3 dir, float3 sunDir) {
    float3 orig = float3(0.0, PLANET_R + 2000.0, 0.0);
    float2 t = raySphere(orig, dir, ATMOS_R);
    if (t.x > t.y) return float3(0.0, 0.0, 0.0);      // ray misses the atmosphere entirely
    float tStart = max(t.x, 0.0);
    float tEnd = t.y;
    float2 tGround = raySphere(orig, dir, PLANET_R);
    if (tGround.x > 0.0) tEnd = min(tEnd, tGround.x); // clip the march at the planet surface
    float segLen = (tEnd - tStart) / float(ATMOS_PRIMARY_STEPS);

    float mu = dot(dir, sunDir);
    float phaseR = 3.0 / (16.0 * PI) * (1.0 + mu * mu);
    float g = MIE_G;
    float phaseM = 3.0 / (8.0 * PI) * ((1.0 - g * g) * (1.0 + mu * mu))
                 / ((2.0 + g * g) * pow(max(1.0 + g * g - 2.0 * g * mu, 0.0), 1.5));

    float3 sumR = float3(0.0, 0.0, 0.0), sumM = float3(0.0, 0.0, 0.0);
    float odR = 0.0, odM = 0.0, odO = 0.0;            // view-ray optical depth accumulators
    float tCur = tStart;
    for (int i = 0; i < ATMOS_PRIMARY_STEPS; i++) {
        float3 p = orig + dir * (tCur + segLen * 0.5);
        float h = length(p) - PLANET_R;
        float hr = exp(-h / RAY_SCALE_H) * segLen;
        float hm = exp(-h / MIE_SCALE_H) * segLen;
        odR += hr; odM += hm; odO += ozoneDensity(h) * segLen;

        // March toward the sun to accumulate the light-ray optical depth (transmittance to space).
        float2 tl = raySphere(p, sunDir, ATMOS_R);
        float segLenL = tl.y / float(ATMOS_LIGHT_STEPS);
        float tlCur = 0.0, odRL = 0.0, odML = 0.0, odOL = 0.0;
        bool blocked = false;
        for (int j = 0; j < ATMOS_LIGHT_STEPS; j++) {
            float3 pl = p + sunDir * (tlCur + segLenL * 0.5);
            float hl = length(pl) - PLANET_R;
            if (hl < 0.0) { blocked = true; break; } // sun is below this point's horizon → in shadow
            odRL += exp(-hl / RAY_SCALE_H) * segLenL;
            odML += exp(-hl / MIE_SCALE_H) * segLenL;
            odOL += ozoneDensity(hl) * segLenL;
            tlCur += segLenL;
        }
        if (!blocked) {
            float3 tau = RAY_BETA * (odR + odRL) + MIE_BETA * 1.1 * (odM + odML)
                       + OZONE_BETA * (odO + odOL);
            float3 atten = exp(-tau);
            sumR += hr * atten;
            sumM += hm * atten;
        }
        tCur += segLen;
    }
    return SUN_INTENSITY * (sumR * RAY_BETA * phaseR + sumM * MIE_BETA * phaseM);
}

// Extinction from the camera to space along `dir` (Rayleigh + Mie + ozone; the sun-below-horizon case
// needs no explicit planet test — a grazing/downward path picks up enormous optical depth, so the
// transmittance rolls to zero smoothly on its own). Tints the sun/moon discs at draw time, and is ported
// verbatim to RtComposite.writeSky so the NEE sunlight on terrain follows the identical sunset curve —
// the disc, the sky, and the light can never disagree.
float3 transmittanceToSpace(float3 dir) {
    float3 orig = float3(0.0, PLANET_R + 2000.0, 0.0);
    float2 t = raySphere(orig, dir, ATMOS_R);
    if (t.y <= 0.0) return float3(1.0, 1.0, 1.0);
    float segLen = t.y / float(ATMOS_LIGHT_STEPS);
    float odR = 0.0, odM = 0.0, odO = 0.0;
    for (int i = 0; i < ATMOS_LIGHT_STEPS; i++) {
        float3 p = orig + dir * (segLen * (float(i) + 0.5));
        float h = length(p) - PLANET_R;
        odR += exp(-h / RAY_SCALE_H) * segLen;
        odM += exp(-h / MIE_SCALE_H) * segLen;
        odO += ozoneDensity(h) * segLen;
    }
    return exp(-(RAY_BETA * odR + MIE_BETA * 1.1 * odM + OZONE_BETA * odO));
}

// Build the celestial body's square tangent frame: `right` along the arc-travel direction, `up`
// perpendicular within the sky. Shared by the visible disc and the NEE square sampling in raygen.
void celestialFrame(float3 dir, float3 celestialAxis, out float3 right, out float3 up) {
    right = normalize(cross(dir, celestialAxis));
    up = cross(right, dir);
}

// Gnomonic projection of `dir` into the body's tangent square, in units of the half-angle. local ∈
// [-1,1]² is inside the square. Returns 0 behind the body. The square (not a cone) matches MC's quad.
float squareBody(float3 dir, float3 bodyDir, float halfAngle, float3 celestialAxis, out float2 local) {
    float c = dot(dir, bodyDir);
    if (c <= 1.0e-3) { local = float2(2.0, 2.0); return 0.0; }
    float3 d = dir / c;                  // d·bodyDir == 1 (point on the tangent plane)
    float3 r, u;
    celestialFrame(bodyDir, celestialAxis, r, u);
    float t = tan(halfAngle);
    local = float2(dot(d, r), dot(d, u)) / t;
    float m = max(abs(local.x), abs(local.y));
    return 1.0 - smoothstep(0.90, 1.0, m); // soft square edge
}

// Dave Hoskins' hash13 (https://www.shadertoy.com/view/4djSRW): well-distributed and pattern-free.
float hash13(float3 p3) {
    p3 = frac(p3 * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return frac((p3.x + p3.y) * p3.z);
}

// Rotate v about a unit axis by angle (Rodrigues). Used to wheel the starfield about the celestial pole.
float3 rotateAxis(float3 v, float3 axis, float ang) {
    float c = cos(ang), s = sin(ang);
    return v * c + cross(axis, v) * s + axis * dot(axis, v) * (1.0 - c);
}

// Procedural starfield, a faithful port of vanilla's buildStars(): ~1500 discrete stars at random
// sphere directions, each a small SQUARE billboard of random size + random rotation. A cube-map grid is
// the spatial accelerator — at most one star per cell, placed at a hashed position INSET from the cell
// edges (so its square never crosses into a neighbour), drawn as a hash-rotated square of hashed size.
// The lookup direction is rotated about the celestial pole by -STAR_ANGLE so the field wheels with
// world time like the real sky.
static const float STAR_CELLS     = 21.0;  // cube-face grid resolution; with the threshold gives ~1500 stars
static const float STAR_THRESHOLD = 0.86;  // cell occupancy cutoff (higher = fewer stars)
static const float STAR_HALF      = 0.045; // star half-size as a fraction of a cell (≈ vanilla 0.11° at center)
float3 stars(float3 dir, float starBrightness, float VdotS, float4 celestial) {
    if (starBrightness <= 0.0 || dir.y <= 0.0) return float3(0.0, 0.0, 0.0);
    // Negative angle: rotating the LOOKUP direction is the inverse of rotating star geometry, so
    // -starAngle makes the field wheel the SAME way as the sun/moon (whose world dirs are
    // forward-rotated on the CPU).
    float3 sdir = rotateAxis(dir, normalize(celestial.xyz), -celestial.w);
    // Cube-map face + 2D gnomonic uv → uniform square cells, no pole/equator distortion.
    float3 a = abs(sdir);
    float2 uv;
    float face;
    if (a.x >= a.y && a.x >= a.z)      { uv = sdir.yz / a.x; face = sdir.x > 0.0 ? 0.0 : 1.0; }
    else if (a.y >= a.z)               { uv = sdir.xz / a.y; face = sdir.y > 0.0 ? 2.0 : 3.0; }
    else                               { uv = sdir.xy / a.z; face = sdir.z > 0.0 ? 4.0 : 5.0; }
    float2 g = uv * STAR_CELLS;
    float3 id = float3(floor(g), face);
    if (hash13(id) < STAR_THRESHOLD) return float3(0.0, 0.0, 0.0); // no star in this cell
    // Per-star randoms: size, centre (inset so the rotated square stays inside the cell), rotation.
    float sz = STAR_HALF * (0.6 + 0.8 * hash13(id + 53.0));
    float inset = sz * 1.5; // keeps the rotated square clear of the cell edge
    float2 center = inset + (1.0 - 2.0 * inset) * float2(hash13(id + 11.0), hash13(id + 23.0));
    float2 q = frac(g) - center;
    float ang = hash13(id + 91.0) * 6.2831853;        // random per-star rotation (vanilla zRot)
    float cs = cos(ang), sn = sin(ang);
    q = float2(cs * q.x - sn * q.y, sn * q.x + cs * q.y); // GLSL mat2(cs,-sn,sn,cs) * q, written out
    float box = max(abs(q.x), abs(q.y));               // Chebyshev distance → square
    float star = 1.0 - smoothstep(sz * 0.6, sz, box);  // soft-edged square (helps DLSS stability)
    if (star <= 0.0) return float3(0.0, 0.0, 0.0);
    float bright = 0.55 + 0.45 * hash13(id + 71.0);    // mild per-star brightness variation
    star *= bright * min(dir.y * 3.0, 1.0) * max(0.0, 1.0 - pow(abs(VdotS) * 1.002, 100.0));
    // Per-star colour temperature: hash picks a spot on a warm-orange ↔ blue-white ramp (real star
    // populations). Average luminance close to the old uniform constant so night exposure doesn't shift.
    float3 starCol = lerp(float3(0.78, 0.60, 0.42), float3(0.50, 0.62, 0.95), hash13(id + 137.0));
    return 0.2 * star * starCol * starBrightness;
}

// ---- Nether skybox. The Nether has no sky: the player is under a bedrock ceiling looking into a
// thick, self-illuminated haze. So instead of a gradient with a horizon, this is a dome of diffuse
// ambient light — warm and dark, brightening slightly downward (toward the lava seas that light the
// dimension from below) and toward the fog colour the dimension pushes. There is no sun term, no
// transmittance, and no direction that reads as "up", which is what makes it look enclosed rather
// than open. The radiance level is deliberately low but non-zero: it is the ONLY ambient light source
// for a path that escapes geometry, so zero would make unlit caves pure black.
static const float3 NETHER_SKY_LOW  = float3(0.36, 0.085, 0.030); // toward the lava glow below
static const float3 NETHER_SKY_HIGH = float3(0.085, 0.024, 0.020); // toward the dark ceiling
float3 netherSky(float3 dir, float3 fogColor) {
    // Downward rays see the glow, upward rays the ceiling; smoothstep keeps the crossover soft so the
    // ambient term has no visible band in it.
    float down = smoothstep(-0.75, 0.35, -dir.y);
    float3 col = lerp(NETHER_SKY_HIGH, NETHER_SKY_LOW, down);
    // Blend toward the dimension/biome fog colour so a crimson forest reads red and a soul valley
    // teal, exactly like the fog the same biome pushes into worldPush.ambientFog.
    return lerp(col, col * 0.5 + fogColor * 0.6, 0.5);
}

// ---- The End skybox. Deep void purple with a dense, static starfield: the End is rendered as open
// space, so unlike the Nether it gets a real (if dim) star layer, and unlike the Overworld those stars
// do not wheel — the End has no celestial rotation, so the field is fixed to world directions and the
// player can navigate by it. The gradient is subtle and vertical-symmetric (there is no horizon out
// there); the small amount of extra brightness near the "equator" reads as distant void haze.
static const float3 END_SKY_DEEP  = float3(0.020, 0.010, 0.035); // straight up / straight down
static const float3 END_SKY_BAND  = float3(0.055, 0.028, 0.080); // toward the horizontal band
static const float  END_STAR_BRIGHTNESS = 0.55;
float3 endSky(float3 dir, float3 fogColor) {
    float band = 1.0 - abs(dir.y);          // 0 at the poles, 1 around the horizontal
    float3 col = lerp(END_SKY_DEEP, END_SKY_BAND, band * band);
    col = lerp(col, fogColor * 0.35, 0.35); // the End's own fog tint, kept subtle
    // Static starfield: the same cube-cell hash the Overworld uses, but sampled from the unrotated
    // direction (celestial.w is meaningless here) and with no horizon/daylight gates.
    float3 a = abs(dir);
    float2 uv;
    float face;
    if (a.x >= a.y && a.x >= a.z)      { uv = dir.yz / a.x; face = dir.x > 0.0 ? 0.0 : 1.0; }
    else if (a.y >= a.z)               { uv = dir.xz / a.y; face = dir.y > 0.0 ? 2.0 : 3.0; }
    else                               { uv = dir.xy / a.z; face = dir.z > 0.0 ? 4.0 : 5.0; }
    float2 g = uv * STAR_CELLS;
    float3 id = float3(floor(g), face);
    if (hash13(id) >= STAR_THRESHOLD) {
        float sz = STAR_HALF * (0.6 + 0.8 * hash13(id + 53.0));
        float inset = sz * 1.5;
        float2 center = inset + (1.0 - 2.0 * inset) * float2(hash13(id + 11.0), hash13(id + 23.0));
        float2 q = frac(g) - center;
        float box = max(abs(q.x), abs(q.y));
        float star = 1.0 - smoothstep(sz * 0.6, sz, box);
        if (star > 0.0) {
            // End stars are cold: a violet-white ramp rather than the Overworld's warm/blue mix.
            float3 starCol = lerp(float3(0.62, 0.55, 0.95), float3(0.90, 0.86, 1.0), hash13(id + 137.0));
            col += 0.2 * star * starCol * END_STAR_BRIGHTNESS * (0.55 + 0.45 * hash13(id + 71.0));
        }
    }
    return col;
}

// Write a sky miss into the payload: HDR radiance in albedo, the -1 hitT sentinel, and the neutral
// material lanes every consumer expects on a miss. One writer for all three skyboxes, so a new
// dimension cannot forget to clear a lane and leave a hit's stale roughness or IOR behind.
//
// albedo is a half3 lane, so the sky's HDR radiance is clamped to half's finite range rather than
// allowed to round up to +inf and propagate as NaN through the path throughput. This is a guard, not a
// correction: the brightest term is SUN_DISC_RADIANCE at 24.0, far under the 65504 ceiling.
void writeSkyMiss(inout Payload payload, float3 radiance) {
    payload.albedo = half3(clamp(radiance, float3(0.0, 0.0, 0.0),
            float3(HALF_MAX, HALF_MAX, HALF_MAX)));
    payload.hitT = -1.0;
    payload.normal = half3(0.0h, 0.0h, 0.0h);
    payload.motionPrev = half3(0.0h, 0.0h, 0.0h);
    payload.f0 = half3(0.0h, 0.0h, 0.0h);
    payload.flags = 0u;
    payload.roughMetal = packHalf2(float2(1.0, 0.0));
    payload.emissionSss = packHalf2(float2(0.0, 0.0));
    payload.iorTransmission = packHalf2(float2(1.0, 0.0));
    payload.rayCone = 0u;
}

[shader("miss")]
void main(inout Payload payload) {
    WorldPush worldPush = ConstPtr<WorldPush>(pc.worldPushAddr)[0];
    float3 dir = normalize(WorldRayDirection());

    // Nether / End: no atmosphere, no sun, no moon, no wheeling stars. Answer with the dimension's own
    // skybox and skip the entire Overworld path below — including the celestials-atlas samples, which
    // would draw an Overworld sun into a Nether ceiling.
    if (worldPush.dimension != DIMENSION_OVERWORLD) {
        writeSkyMiss(payload, worldPush.dimension == DIMENSION_NETHER
                ? netherSky(dir, worldPush.ambientFog.xyz)
                : endSky(dir, worldPush.ambientFog.xyz));
        return;
    }

    float day = worldPush.sunDir.w;
    float3 sd = worldPush.sunDir.xyz;
    float SdotU = sd.y;
    float VdotS = dot(dir, sd);
    float sunUp = clamp((SdotU + 0.0625) / 0.125, 0.0, 1.0); // 0 well below horizon .. 1 above
    float starBrightness = worldPush.lightRadiance.w;
    bool showCelestial = (payload.flags & PAYLOAD_SHOW_CELESTIAL) != 0u;

    // Physically-based in-scattering: blue zenith, bright/desaturated horizon, warm Mie halo + red
    // sunset all fall out of the Rayleigh/Mie/ozone march. At night the sun is below the horizon so the
    // light march is blocked → near-zero; a faint night ambient + the rotating starfield sit underneath.
    //
    // Soft horizon: below-horizon rays used to march into the planet-surface clip, drawing a hard fixed
    // line across the sky. Instead, march the ray flattened onto the horizon (same azimuth, y=0) so the
    // gradient continues seamlessly downward, with an exponential falloff so it settles into darkness
    // instead of a uniform bright band.
    float3 mdir = dir;
    float downFade = 1.0;
    if (dir.y < 0.0) {
        float2 flat2 = float2(dir.x, dir.z);
        float fl = length(flat2);
        mdir = fl > 1.0e-4 ? float3(flat2.x / fl, 0.0, flat2.y / fl) : float3(1.0, 0.0, 0.0);
        downFade = exp(dir.y * 3.0);
    }
    float3 col = atmosphere(mdir, sd);

    // ---- Soft Cinema post-tint: blend physical atmosphere toward requested pastel palette.
    // Keeps physical sunset variation but guarantees zenith is (0.42,0.62,0.82) pastel and horizon
    // is whitish desaturated with Mie scattering around sun.
    {
        float upFactor = saturate(dir.y); // 0 at horizon, 1 at zenith
        // Smooth gradient from horizon to zenith (pow softens transition)
        float3 softGradient = lerp(SKY_HORIZON_SOFT, SKY_ZENITH_SOFT, pow(upFactor, 0.58));
        // Blend amount: stronger at day, moderate at zenith/horizon, preserves sunset when sun low
        float dayBlend = day * clamp(SdotU * 0.5 + 0.7, 0.0, 1.0);
        float softBlend = dayBlend * (0.28 + 0.22 * upFactor + 0.20 * (1.0 - upFactor));
        // Slightly dim softGradient to stay in HDR range similar to physical sky
        col = lerp(col, softGradient * 0.90, softBlend);

        // Extra Mie halo around sun for whitish foggy look (soft cinema sun haze)
        float sunHalo = pow(saturate(VdotS), 24.0) * 0.7 + pow(saturate(VdotS), 72.0) * 1.2;
        col += float3(0.92, 0.94, 0.98) * sunHalo * sunUp * day * 0.35;
    }

    // Luminance-preserving vibrance on the in-scatter only (see SKY_SATURATION).
    float lum = dot(col, float3(0.2126, 0.7152, 0.0722));
    col = max(lerp(float3(lum, lum, lum), col, SKY_SATURATION), float3(0.0, 0.0, 0.0));
    float up = clamp(dir.y, 0.0, 1.0);
    float3 nightAmbient = lerp(NIGHT_HORIZON, NIGHT_ZENITH, up);
    col += nightAmbient * (1.0 - day);
    col *= downFade;

    // ---- Weather. worldPush.weather.z is the sky-darkening factor Java computed from the same rain and
    // thunder levels it used to attenuate the NEE sun/moon radiance, so overcast sky and dimmer ground
    // light are two readings of one number and cannot drift apart. Vanilla hides the sun, moon and stars
    // behind the cloud deck during rain; the same happens here, driven by rain strength.
    //
    // The clouds are not black: an overcast sky is a diffuse grey-blue light source, and killing the
    // in-scatter outright would make a storm darker than night and destroy the sky fill that lights
    // everything indirectly. So the atmosphere is dimmed toward a desaturated overcast radiance derived
    // from its own luminance, which keeps the correct time-of-day brightness ordering (a storm at noon
    // is still far brighter than a clear midnight) while removing the blue.
    bool weatherOn = worldFeature(worldPush, FEATURE_WEATHER_LIGHTING);
    float rainStrength = weatherOn ? clamp(worldPush.weather.x, 0.0, 1.0) : 0.0;
    float skyDarken = weatherOn ? clamp(worldPush.weather.z, 0.0, 1.0) : 1.0;
    if (rainStrength > 0.0) {
        float overcastLum = dot(col, float3(0.2126, 0.7152, 0.0722));
        float3 overcast = float3(overcastLum, overcastLum, overcastLum) * float3(0.92, 0.95, 1.0);
        col = lerp(col, overcast, rainStrength * 0.85);
    }
    col *= skyDarken;
    // Stars are already faded for rain on the CPU (RtComposite.skyPush scales the pushed
    // starBrightness), so this must NOT re-apply the rain factor or the fade would be squared.
    col += stars(dir, starBrightness, VdotS, worldPush.celestial);

    // The sky WITHOUT the celestial discs, kept for the cloud composite below: it is the ambient fill a
    // cloud's underside receives from the sky dome around it. It must exclude the sun/moon, or an opaque
    // cloud would still leak the disc through the fill and the sun would burn through solid cover.
    float3 skyAmbient = col;

    // Sun & moon discs — sampled from the vanilla celestials atlas, gated to rays that haven't already
    // accounted for the light via diffuse NEE. squareBody gives the body-local [-1,1] coord; the
    // sprite's own texel coverage (alpha) shapes the disc.
    //
    // Hiding the discs during rain is a STAND-IN for the cloud deck that used to be missing: with no
    // clouds drawn, fading the sun out was the only way for an overcast sky to not have a blazing sun in
    // it. Now that the deck is real and occludes the discs geometrically, applying both would hide the
    // sun twice — it would vanish in light rain even through a genuine gap in the clouds. So the
    // stand-in is used only when the deck is not being drawn, and the clouds are authoritative when it
    // is. Rain still closes the deck over (cloudState raises coverage), so an overcast sky still hides
    // the sun — it just does it with an actual cloud now.
    float discVisible = cloudsEnabled(worldPush) ? 1.0 : 1.0 - rainStrength;
    if (showCelestial && discVisible > 0.0) {
        float2 local;
        squareBody(dir, sd, SUN_DISC_HALF_ANGLE, worldPush.celestial.xyz, local);
        if (all(abs(local) < float2(1.0, 1.0))) {
            float2 uv = lerp(worldPush.sunUv.xy, worldPush.sunUv.zw, local * 0.5 + 0.5);
            float3 t = celestialsAtlas.SampleLevel(uv, 0.0).rgb;
            // The vanilla sun sprite bakes a soft glow gradient into its texels; scaling that raw to HDR
            // makes a huge ring. Raise the texture luminance to a high power — only the bright core
            // survives, the halo collapses to ~0.
            float core = pow(clamp(dot(t, t) * 0.45, 0.0, 1.0), 6.0);
            // transmittanceToSpace reddens+dims the disc through the sunset on the sky's own curve.
            col += SUN_DISC_RADIANCE * transmittanceToSpace(dir) * core * sunUp * discVisible;
        }
        // Same horizon gate as the sun: with the soft-horizon change, below-horizon rays no longer clip
        // at the planet, so without this the moon disc would stay visible after it set.
        float moonUp = clamp((worldPush.moonDir.y + 0.0625) / 0.125, 0.0, 1.0);
        squareBody(dir, worldPush.moonDir.xyz, MOON_DISC_HALF_ANGLE, worldPush.celestial.xyz, local);
        if (all(abs(local) < float2(1.0, 1.0)) && moonUp > 0.0) {
            float2 uv = lerp(worldPush.moonUv.xy, worldPush.moonUv.zw, local * 0.5 + 0.5);
            float3 t = celestialsAtlas.SampleLevel(uv, 0.0).rgb;
            // Moon: a smoothstep contrast ramp suppresses the faint halo while keeping the lit phase
            // shape from the texture. The transmittance tint gives a warm amber moon at moonrise/set.
            float m = smoothstep(0.0, 1.0, min(length(t), 1.0)) * 1.3;
            col += MOON_DISC_RADIANCE * transmittanceToSpace(dir) * t * m * pow2(1.0 - day) * moonUp * discVisible;
        }
    }

    // ---- Cloud deck. Composited LAST, over the finished sky: the deck is nearer than the atmosphere,
    // the stars and the sun/moon discs, so it must occlude all three rather than be added to them. That
    // ordering is also what makes the sun genuinely disappear behind a cloud instead of burning through
    // it, and it is why this is a lerp toward the cloud radiance rather than a `+=`.
    //
    // No showCelestial gate here. Unlike the sun/moon discs, the deck is not a NEE light — nothing
    // samples it explicitly — so a diffuse bounce that sees it is not double-counting anything, and
    // hiding it from those rays would instead punch cloud-shaped holes in the sky fill lighting the
    // ground. Every escaping ray gets the same sky, which is what keeps reflections consistent.
    //
    // The ray's own origin is used, not the camera: a reflection off water starts at the water surface,
    // and parallax against a deck 128 blocks up is very visible over even a short offset. Both this and
    // the shadow query work in camera-relative space (WorldRayOrigin is in the terrain's rebased space),
    // so neither is affected when the terrain rebase origin shifts.
    //
    // This ray escaped to the sky, so nothing occludes the deck: maxDistance is unbounded. Rays that end
    // on geometry get their clouds from world.rgen instead, bounded by the hit distance — that split is
    // what keeps terrain and cloud correctly ordered from both above and below the deck.
    //
    // skyAmbient (no discs) is the fill; `col` (with discs) is what the cloud covers.
    //
    // showCelestial doubles as the cloud quality tier, because it already marks exactly the rays whose
    // clouds are looked at directly: the camera ray and specular/dielectric bounces (mirror reflections
    // in water and glass) carry it, while diffuse continuation rays — which only gather a broad,
    // denoised sky-fill term — do not. Same signal, same set of rays, no second flag to keep in sync.
    col = cloudLayer(worldPush, WorldRayOrigin() - worldPush.camOffset, dir, 1.0e9, col, skyAmbient,
                     showCelestial);

    writeSkyMiss(payload, col);
}
