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
static const float3 MOON_DISC_RADIANCE = float3(1.7, 1.85, 2.2) / 4.0;
// VISIBLE disc half-angles, matched to vanilla: the sun/moon quads are drawn at half-width 30/20 at
// distance 100 → half-angle = atan(0.30) / atan(0.20). Decoupled from the NEE light radius (≈0.6°).
static const float SUN_DISC_HALF_ANGLE  = 0.29146;
static const float MOON_DISC_HALF_ANGLE = 0.19740;
// Faint night-sky ambient so the night isn't pure black where the atmosphere in-scatter falls to zero.
static const float3 NIGHT_ZENITH  = float3(0.004, 0.008, 0.022) / 5.0;
static const float3 NIGHT_HORIZON = float3(0.015, 0.022, 0.045) / 5.0;

// ---- Sky palettes ----
// Modified: vivid pastel clean blue, subtle glow only near sun (not whitish/fosco)
static const float3 SKY_ZENITH_SOFT_MOD  = float3(0.42, 0.62, 0.82);
static const float3 SKY_HORIZON_SOFT_MOD = float3(0.68, 0.80, 0.92);

// Common planet constants
static const float  PLANET_R   = 6371000.0;
static const float  ATMOS_R    = 6471000.0;
static const float3 OZONE_BETA = float3(0.650e-6, 1.881e-6, 0.085e-6);
static const float  OZONE_CENTER_H = 25000.0;
static const float  OZONE_WIDTH_H  = 15000.0;
static const float  SUN_INTENSITY = 22.0;
static const int    ATMOS_PRIMARY_STEPS = 16;
static const int    ATMOS_LIGHT_STEPS   = 8;

// Vanilla atmosphere constants (original Caustica)
static const float3 RAY_BETA_VANILLA   = float3(5.5e-6, 13.0e-6, 22.4e-6);
static const float  MIE_BETA_VANILLA   = 21.0e-6;
static const float  RAY_SCALE_H_VANILLA = 8000.0;
static const float  MIE_SCALE_H_VANILLA = 1200.0;
static const float  MIE_G_VANILLA      = 0.758;
static const float  SKY_SATURATION_VANILLA = 1.2;

// Modified atmosphere constants - balanced, vivid pastel, clean, not whitish
static const float3 RAY_BETA_MOD   = float3(5.2e-6, 12.0e-6, 19.0e-6);
static const float  MIE_BETA_MOD   = 24.0e-6; // subtle haze, not 38e-6 aggressive
static const float  RAY_SCALE_H_MOD = 8500.0;
static const float  MIE_SCALE_H_MOD = 1350.0;
static const float  MIE_G_MOD      = 0.76; // subtle forward, close to vanilla 0.758
static const float  SKY_SATURATION_MOD = 1.05; // vivid, slightly above 1

// Runtime selection - set in main() from feature flag
static bool gModifiedSky = true;

float ozoneDensity(float h) { return max(0.0, 1.0 - abs(h - OZONE_CENTER_H) / OZONE_WIDTH_H); }

float2 raySphere(float3 o, float3 d, float r) {
    float b = dot(o, d);
    float c = dot(o, o) - r * r;
    float disc = b * b - c;
    if (disc < 0.0) return float2(1.0e9, -1.0e9);
    disc = sqrt(disc);
    return float2(-b - disc, -b + disc);
}

float3 atmosphereInternal(float3 dir, float3 sunDir, float3 rayBeta, float mieBeta, float rayScaleH, float mieScaleH, float mieG) {
    float3 orig = float3(0.0, PLANET_R + 2000.0, 0.0);
    float2 t = raySphere(orig, dir, ATMOS_R);
    if (t.x > t.y) return float3(0.0, 0.0, 0.0);
    float tStart = max(t.x, 0.0);
    float tEnd = t.y;
    float2 tGround = raySphere(orig, dir, PLANET_R);
    if (tGround.x > 0.0) tEnd = min(tEnd, tGround.x);
    float segLen = (tEnd - tStart) / float(ATMOS_PRIMARY_STEPS);

    float mu = dot(dir, sunDir);
    float phaseR = 3.0 / (16.0 * PI) * (1.0 + mu * mu);
    float g = mieG;
    float phaseM = 3.0 / (8.0 * PI) * ((1.0 - g * g) * (1.0 + mu * mu))
                 / ((2.0 + g * g) * pow(max(1.0 + g * g - 2.0 * g * mu, 0.0), 1.5));

    float3 sumR = float3(0.0, 0.0, 0.0), sumM = float3(0.0, 0.0, 0.0);
    float odR = 0.0, odM = 0.0, odO = 0.0;
    float tCur = tStart;
    for (int i = 0; i < ATMOS_PRIMARY_STEPS; i++) {
        float3 p = orig + dir * (tCur + segLen * 0.5);
        float h = length(p) - PLANET_R;
        float hr = exp(-h / rayScaleH) * segLen;
        float hm = exp(-h / mieScaleH) * segLen;
        odR += hr; odM += hm; odO += ozoneDensity(h) * segLen;

        float2 tl = raySphere(p, sunDir, ATMOS_R);
        float segLenL = tl.y / float(ATMOS_LIGHT_STEPS);
        float tlCur = 0.0, odRL = 0.0, odML = 0.0, odOL = 0.0;
        bool blocked = false;
        for (int j = 0; j < ATMOS_LIGHT_STEPS; j++) {
            float3 pl = p + sunDir * (tlCur + segLenL * 0.5);
            float hl = length(pl) - PLANET_R;
            if (hl < 0.0) { blocked = true; break; }
            odRL += exp(-hl / rayScaleH) * segLenL;
            odML += exp(-hl / mieScaleH) * segLenL;
            odOL += ozoneDensity(hl) * segLenL;
            tlCur += segLenL;
        }
        if (!blocked) {
            float3 tau = rayBeta * (odR + odRL) + mieBeta * 1.1 * (odM + odML)
                       + OZONE_BETA * (odO + odOL);
            float3 atten = exp(-tau);
            sumR += hr * atten;
            sumM += hm * atten;
        }
        tCur += segLen;
    }
    return SUN_INTENSITY * (sumR * rayBeta * phaseR + sumM * mieBeta * phaseM);
}

float3 atmosphere(float3 dir, float3 sunDir) {
    if (gModifiedSky) {
        return atmosphereInternal(dir, sunDir, RAY_BETA_MOD, MIE_BETA_MOD, RAY_SCALE_H_MOD, MIE_SCALE_H_MOD, MIE_G_MOD);
    } else {
        return atmosphereInternal(dir, sunDir, RAY_BETA_VANILLA, MIE_BETA_VANILLA, RAY_SCALE_H_VANILLA, MIE_SCALE_H_VANILLA, MIE_G_VANILLA);
    }
}

float3 transmittanceToSpaceInternal(float3 dir, float3 rayBeta, float mieBeta, float rayScaleH, float mieScaleH) {
    float3 orig = float3(0.0, PLANET_R + 2000.0, 0.0);
    float2 t = raySphere(orig, dir, ATMOS_R);
    if (t.y <= 0.0) return float3(1.0, 1.0, 1.0);
    float segLen = t.y / float(ATMOS_LIGHT_STEPS);
    float odR = 0.0, odM = 0.0, odO = 0.0;
    for (int i = 0; i < ATMOS_LIGHT_STEPS; i++) {
        float3 p = orig + dir * (segLen * (float(i) + 0.5));
        float h = length(p) - PLANET_R;
        odR += exp(-h / rayScaleH) * segLen;
        odM += exp(-h / mieScaleH) * segLen;
        odO += ozoneDensity(h) * segLen;
    }
    return exp(-(rayBeta * odR + mieBeta * 1.1 * odM + OZONE_BETA * odO));
}

float3 transmittanceToSpace(float3 dir) {
    if (gModifiedSky) {
        return transmittanceToSpaceInternal(dir, RAY_BETA_MOD, MIE_BETA_MOD, RAY_SCALE_H_MOD, MIE_SCALE_H_MOD);
    } else {
        return transmittanceToSpaceInternal(dir, RAY_BETA_VANILLA, MIE_BETA_VANILLA, RAY_SCALE_H_VANILLA, MIE_SCALE_H_VANILLA);
    }
}

void celestialFrame(float3 dir, float3 celestialAxis, out float3 right, out float3 up) {
    right = normalize(cross(dir, celestialAxis));
    up = cross(right, dir);
}

float squareBody(float3 dir, float3 bodyDir, float halfAngle, float3 celestialAxis, out float2 local) {
    float c = dot(dir, bodyDir);
    if (c <= 1.0e-3) { local = float2(2.0, 2.0); return 0.0; }
    float3 d = dir / c;
    float3 r, u;
    celestialFrame(bodyDir, celestialAxis, r, u);
    float t = tan(halfAngle);
    local = float2(dot(d, r), dot(d, u)) / t;
    float m = max(abs(local.x), abs(local.y));
    return 1.0 - smoothstep(0.90, 1.0, m);
}

float hash13(float3 p3) {
    p3 = frac(p3 * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return frac((p3.x + p3.y) * p3.z);
}

float3 rotateAxis(float3 v, float3 axis, float ang) {
    float c = cos(ang), s = sin(ang);
    return v * c + cross(axis, v) * s + axis * dot(axis, v) * (1.0 - c);
}

static const float STAR_CELLS     = 21.0;
static const float STAR_THRESHOLD = 0.86;
static const float STAR_HALF      = 0.045;
float3 stars(float3 dir, float starBrightness, float VdotS, float4 celestial) {
    if (starBrightness <= 0.0 || dir.y <= 0.0) return float3(0.0, 0.0, 0.0);
    float3 sdir = rotateAxis(dir, normalize(celestial.xyz), -celestial.w);
    float3 a = abs(sdir);
    float2 uv;
    float face;
    if (a.x >= a.y && a.x >= a.z)      { uv = sdir.yz / a.x; face = sdir.x > 0.0 ? 0.0 : 1.0; }
    else if (a.y >= a.z)               { uv = sdir.xz / a.y; face = sdir.y > 0.0 ? 2.0 : 3.0; }
    else                               { uv = sdir.xy / a.z; face = sdir.z > 0.0 ? 4.0 : 5.0; }
    float2 g = uv * STAR_CELLS;
    float3 id = float3(floor(g), face);
    if (hash13(id) < STAR_THRESHOLD) return float3(0.0, 0.0, 0.0);
    float sz = STAR_HALF * (0.6 + 0.8 * hash13(id + 53.0));
    float inset = sz * 1.5;
    float2 center = inset + (1.0 - 2.0 * inset) * float2(hash13(id + 11.0), hash13(id + 23.0));
    float2 q = frac(g) - center;
    float ang = hash13(id + 91.0) * 6.2831853;
    float cs = cos(ang), sn = sin(ang);
    q = float2(cs * q.x - sn * q.y, sn * q.x + cs * q.y);
    float box = max(abs(q.x), abs(q.y));
    float star = 1.0 - smoothstep(sz * 0.6, sz, box);
    if (star <= 0.0) return float3(0.0, 0.0, 0.0);
    float bright = 0.55 + 0.45 * hash13(id + 71.0);
    star *= bright * min(dir.y * 3.0, 1.0) * max(0.0, 1.0 - pow(abs(VdotS) * 1.002, 100.0));
    float3 starCol = lerp(float3(0.78, 0.60, 0.42), float3(0.50, 0.62, 0.95), hash13(id + 137.0));
    return 0.2 * star * starCol * starBrightness;
}

static const float3 NETHER_SKY_LOW  = float3(0.36, 0.085, 0.030);
static const float3 NETHER_SKY_HIGH = float3(0.085, 0.024, 0.020);
float3 netherSky(float3 dir, float3 fogColor) {
    float down = smoothstep(-0.75, 0.35, -dir.y);
    float3 col = lerp(NETHER_SKY_HIGH, NETHER_SKY_LOW, down);
    return lerp(col, col * 0.5 + fogColor * 0.6, 0.5);
}

static const float3 END_SKY_DEEP  = float3(0.020, 0.010, 0.035);
static const float3 END_SKY_BAND  = float3(0.055, 0.028, 0.080);
static const float  END_STAR_BRIGHTNESS = 0.55;
float3 endSky(float3 dir, float3 fogColor) {
    float band = 1.0 - abs(dir.y);
    float3 col = lerp(END_SKY_DEEP, END_SKY_BAND, band * band);
    col = lerp(col, fogColor * 0.35, 0.35);
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
            float3 starCol = lerp(float3(0.62, 0.55, 0.95), float3(0.90, 0.86, 1.0), hash13(id + 137.0));
            col += 0.2 * star * starCol * END_STAR_BRIGHTNESS * (0.55 + 0.45 * hash13(id + 71.0));
        }
    }
    return col;
}

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

    // Sky style toggle: modified vs vanilla
    gModifiedSky = worldFeature(worldPush, FEATURE_MODIFIED_SKY);

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
    float sunUp = clamp((SdotU + 0.0625) / 0.125, 0.0, 1.0);
    float starBrightness = worldPush.lightRadiance.w;
    bool showCelestial = (payload.flags & PAYLOAD_SHOW_CELESTIAL) != 0u;

    float3 mdir = dir;
    float downFade = 1.0;
    if (dir.y < 0.0) {
        float2 flat2 = float2(dir.x, dir.z);
        float fl = length(flat2);
        mdir = fl > 1.0e-4 ? float3(flat2.x / fl, 0.0, flat2.y / fl) : float3(1.0, 0.0, 0.0);
        downFade = exp(dir.y * 3.0);
    }
    float3 col = atmosphere(mdir, sd);

    // ---- Modified sky post-tint: subtle pastel, vivid clean, not whitish/fosco ----
    // Only when modified sky is enabled; vanilla keeps pure physical atmosphere
    if (gModifiedSky) {
        float upFactor = saturate(dir.y); // 0 horizon, 1 zenith
        // Very subtle gradient from horizon to zenith - keeps sky vivid, not washed out
        float3 softGradient = lerp(SKY_HORIZON_SOFT_MOD, SKY_ZENITH_SOFT_MOD, pow(upFactor, 0.65));
        float dayBlend = day * clamp(SdotU * 0.5 + 0.7, 0.0, 1.0);
        // Reduced blend: 0.10-0.18 max, previously 0.28-0.50 which was too aggressive/fosco
        float softBlend = dayBlend * (0.10 + 0.08 * upFactor);
        col = lerp(col, softGradient * 0.88, softBlend);

        // Subtle Mie halo only near sun, not whole horizon
        float sunHalo = pow(saturate(VdotS), 32.0) * 0.5 + pow(saturate(VdotS), 96.0) * 0.8;
        col += float3(0.90, 0.92, 0.98) * sunHalo * sunUp * day * 0.12;
    }

    float skySat = gModifiedSky ? SKY_SATURATION_MOD : SKY_SATURATION_VANILLA;
    float lum = dot(col, float3(0.2126, 0.7152, 0.0722));
    col = max(lerp(float3(lum, lum, lum), col, skySat), float3(0.0, 0.0, 0.0));
    float up = clamp(dir.y, 0.0, 1.0);
    float3 nightAmbient = lerp(NIGHT_HORIZON, NIGHT_ZENITH, up);
    col += nightAmbient * (1.0 - day);
    col *= downFade;

    bool weatherOn = worldFeature(worldPush, FEATURE_WEATHER_LIGHTING);
    float rainStrength = weatherOn ? clamp(worldPush.weather.x, 0.0, 1.0) : 0.0;
    float skyDarken = weatherOn ? clamp(worldPush.weather.z, 0.0, 1.0) : 1.0;
    if (rainStrength > 0.0) {
        float overcastLum = dot(col, float3(0.2126, 0.7152, 0.0722));
        float3 overcast = float3(overcastLum, overcastLum, overcastLum) * float3(0.92, 0.95, 1.0);
        col = lerp(col, overcast, rainStrength * 0.85);
    }
    col *= skyDarken;
    col += stars(dir, starBrightness, VdotS, worldPush.celestial);

    float3 skyAmbient = col;

    float discVisible = cloudsEnabled(worldPush) ? 1.0 : 1.0 - rainStrength;
    if (showCelestial && discVisible > 0.0) {
        float2 local;
        squareBody(dir, sd, SUN_DISC_HALF_ANGLE, worldPush.celestial.xyz, local);
        if (all(abs(local) < float2(1.0, 1.0))) {
            float2 uv = lerp(worldPush.sunUv.xy, worldPush.sunUv.zw, local * 0.5 + 0.5);
            float3 t = celestialsAtlas.SampleLevel(uv, 0.0).rgb;
            float core = pow(clamp(dot(t, t) * 0.45, 0.0, 1.0), 6.0);
            col += SUN_DISC_RADIANCE * transmittanceToSpace(dir) * core * sunUp * discVisible;
        }
        float moonUp = clamp((worldPush.moonDir.y + 0.0625) / 0.125, 0.0, 1.0);
        squareBody(dir, worldPush.moonDir.xyz, MOON_DISC_HALF_ANGLE, worldPush.celestial.xyz, local);
        if (all(abs(local) < float2(1.0, 1.0)) && moonUp > 0.0) {
            float2 uv = lerp(worldPush.moonUv.xy, worldPush.moonUv.zw, local * 0.5 + 0.5);
            float3 t = celestialsAtlas.SampleLevel(uv, 0.0).rgb;
            float m = smoothstep(0.0, 1.0, min(length(t), 1.0)) * 1.3;
            col += MOON_DISC_RADIANCE * transmittanceToSpace(dir) * t * m * pow2(1.0 - day) * moonUp * discVisible;
        }
    }

    col = cloudLayer(worldPush, WorldRayOrigin() - worldPush.camOffset, dir, 1.0e9, col, skyAmbient,
                     showCelestial);

    writeSkyMiss(payload, col);
}
