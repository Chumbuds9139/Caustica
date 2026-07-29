// Direct lighting: the emitter light grid, RIS reservoirs, and reservoir shading.
// INDIRECT PASS ONLY — the primary pass shades nothing. Depends on trace and math.

// contribution weight, M the effective sample count. wSum/phat are streaming scratch (not persisted).

import world_common;
import world_core;
import math;
import trace;

public struct Reservoir {
    public float3 pos;  // light sample point (rebased world space)
    public float3 lnrm; // emitter outward normal
    public float3 le;   // emitter radiance after the current runtime scale
    public float area;  // emitter rectangle area (the area-light pdf factor)
    public float M;
    public float W;
    public float wSum;
    public float phat;
    public uint packedLe; // unscaled R11G11B10 source radiance; survives a live intensity change
    public uint age;
};

public static const uint RESTIR_SPATIAL_SAMPLES = 4u;
public static const float RESTIR_MAX_HISTORY_FACTOR = 20.0;
public static const uint RESTIR_MAX_AGE = 30u;

public Reservoir resEmpty() {
    Reservoir r;
    r.pos = float3(0.0, 0.0, 0.0);
    r.lnrm = float3(0.0, 0.0, 0.0);
    r.le = float3(0.0, 0.0, 0.0);
    r.area = 0.0;
    r.M = 0.0;
    r.W = 0.0;
    r.wSum = 0.0;
    r.phat = 0.0;
    r.packedLe = 0u;
    r.age = 0u;
    return r;
}

// Unshadowed contribution of a fixed light sample point at surface (hitPos,n,v); out p-hat = its
// luminance (the resampling target). Evaluated from the UNBIASED hitPos — SURF_BIAS is applied only to
// the shadow-ray origin in shadeReservoir, on whichever side the survivor sample lands. Three receiver
// modes share one target function because they are mutually exclusive per candidate:
//   - front hemisphere (ndl>0): the same Lambert(+GGX) BRDF split the sun NEE uses;
//   - twoSided (particles): billboards have no back face, ndl=abs(ndl) folds both sides into the front;
//   - back hemisphere with sss>0 (LabPBR leaves/grass): HG transmission phase — cosT = dot(wi,rd) peaks
//     when the ray points from the light through the slab toward the camera (same as the sun SSS term).
public float3 evalSampleContrib(float3 sp, float3 lnrm, float3 le, float area, float3 hitPos, float3 n,
                         float3 v, float3 rd, float3 diffAlb, float3 F0, float rough,
                         bool twoSided, float sss, out float phat) {
    phat = 0.0;
    float3 toL = sp - hitPos;
    float dist2 = dot(toL, toL);
    if (dist2 < 1.0e-6) {
        return float3(0.0, 0.0, 0.0);
    }
    float dist = sqrt(dist2);
    float3 wi = toL / dist;
    float cosL = max(0.0, dot(lnrm, -wi));
    if (cosL <= 0.0) {
        return float3(0.0, 0.0, 0.0); // receiver behind the emitter face
    }
    float ndl = dot(n, wi);
    if (twoSided) {
        ndl = abs(ndl);
    }
    float3 contrib = float3(0.0, 0.0, 0.0);
    if (ndl > 0.0) {
        float G = ndl * cosL / dist2; // area-light geometry term (x area)
        float3 brdf = diffAlb * INV_PI;
        // twoSided callers are particle billboards, which pass rough=1/F0=0 and want diffuse-only
        // shading: fresnelSchlick still returns up to full white at grazing angles even with F0=0 (that
        // IS the Fresnel effect), so skipping the term here — not just zeroing F0 — is what keeps
        // billboards from picking up a rim highlight they were never meant to have.
        if (!twoSided) {
            float3 h = normalize(wi + v);
            float ndh = max(0.0, dot(n, h));
            float ndv = max(1.0e-4, dot(n, v));
            float vdh = max(0.0, dot(v, h));
            float D = ggxD(ndh, rough);
            float Gs = ggxG1(ndv, rough) * ggxG1(ndl, rough);
            float3 Fs = fresnelSchlick(vdh, F0);
            brdf += (D * Gs) * Fs / (4.0 * ndv * ndl);
        }
        contrib = brdf * le * (G * area);
    } else if (sss > 0.0) {
        float backNdl = -ndl;
        float G = backNdl * cosL / dist2;
        float cosT = dot(wi, rd);
        contrib = diffAlb * sss * SSS_STRENGTH * hg(cosT, SSS_G) * le * (G * area);
    }
    phat = luminance(contrib);
    return contrib;
}


// Select one light from the emitted-power distribution in O(1).
public void selectGlobalLight(inout uint proposalSeed, out uint lightIndex) {
    float aliasSample = rndf(proposalSeed) * float(worldPush.lightCount);
    uint column = min(uint(aliasSample), worldPush.lightCount - 1u);
    if (pc.lightAliasAddr != 0) {
        LightAlias a = ConstPtr<LightAlias>(pc.lightAliasAddr)[column];
        bool self = aliasSample - float(column) < a.accept;
        lightIndex = self ? column : a.aliasIndex;
    } else {
        lightIndex = column;
    }
}

public bool findLightGridCell(float3 p, out LightGridCell cell, out int3 cellCoord) {
    cell.spanOffset = 0u;
    cell.spanCount = 0u;
    cell.invWeightSum = 0.0;
    cellCoord = int3(0, 0, 0);
    if (pc.lightGridCellAddr == 0 || pc.lightGridSpanAddr == 0) return false;
    int3 coord = int3(floor((p - worldPush.lightGridOrigin.xyz) / worldPush.lightGridOrigin.w));
    if (coord.x < 0 || coord.y < 0 || coord.z < 0
            || coord.x >= worldPush.lightGridDims.x
            || coord.y >= worldPush.lightGridDims.y
            || coord.z >= worldPush.lightGridDims.z) return false;
    uint linear = (uint(coord.z) * uint(worldPush.lightGridDims.y) + uint(coord.y))
            * uint(worldPush.lightGridDims.x) + uint(coord.x);
    cell = ConstPtr<LightGridCell>(pc.lightGridCellAddr)[linear];
    cellCoord = coord;
    return cell.spanCount > 0u;
}

public void selectSectionLight(uint firstLight, uint lightCount, inout uint proposalSeed,
                        out uint lightIndex) {
    float aliasSample = rndf(proposalSeed) * float(lightCount);
    uint column = min(uint(aliasSample), lightCount - 1u);
    LightAlias alias = ConstPtr<LightAlias>(pc.lightLocalAliasAddr)[firstLight + column];
    bool self = aliasSample - float(column) < alias.accept;
    uint localIndex = self ? column : alias.aliasIndex;
    lightIndex = firstLight + localIndex;
}

public float unpackUnsignedFloat(uint bits, uint mantissaBits) {
    uint mantissaMask = (1u << mantissaBits) - 1u;
    uint mantissa = bits & mantissaMask;
    uint exponent = (bits >> mantissaBits) & 31u;
    if (exponent == 0u) {
        return float(mantissa) * exp2(float(1 - 15 - int(mantissaBits)));
    }
    return (1.0 + float(mantissa) / float(1u << mantissaBits))
            * exp2(float(int(exponent) - 15));
}

public float3 lightRadiancePacked(uint packed) {
    return float3(unpackUnsignedFloat(packed & 0x7ffu, 6u),
            unpackUnsignedFloat((packed >> 11u) & 0x7ffu, 6u),
            unpackUnsignedFloat((packed >> 22u) & 0x3ffu, 5u));
}

public float3 lightRadiance(Light light) {
    return lightRadiancePacked(light.le);
}

public int3 lightSectionCoord(Light light) {
    uint packed = light.section;
    return int3(int(packed & 0x3ffu), int((packed >> 10u) & 0x3ffu),
            int((packed >> 20u) & 0x3ffu));
}

// The half axes share three lanes two-at-a-time; see the Light record in world_common.
public float3 lightHalfU(Light light) {
    return float3(unpackHalf2(light.halfUxy), unpackHalf2(light.halfUzVx).x);
}

public float3 lightHalfV(Light light) {
    return float3(unpackHalf2(light.halfUzVx).y, unpackHalf2(light.halfVyz));
}

// U x V drives both the emitter normal and the rectangle area, so it is computed once and shared.
public float3 lightCrossUV(Light light) {
    return cross(lightHalfU(light), lightHalfV(light));
}

// The rect spans 2U x 2V, so its area is 4|U x V| — exactly the rectArea the collector used to store.
public float lightArea(Light light) {
    return 4.0 * length(lightCrossUV(light));
}

public float3 lightGeometricNormal(Light light) {
    float3 normal = normalize(lightCrossUV(light));
    return (light.section & 0x40000000u) != 0u ? -normal : normal;
}

public float proposalPdf(Light light, float3 le, LightGridCell cell, int3 cellCoord,
                  float localProbability) {
    float power = max(0.0, lightArea(light) * luminance(le));
    float globalPdf = pc.lightAliasAddr != 0
            ? power * worldPush.lightRebase.w : 1.0 / float(worldPush.lightCount);
    float localPdf = 0.0;
    if (localProbability > 0.0 && power > 0.0) {
        int3 delta = lightSectionCoord(light) - cellCoord;
        if (all(abs(delta) <= int3(2, 2, 2))) {
            float3 deltaF = float3(delta);
            localPdf = power * cell.invWeightSum / max(1.0, dot(deltaF, deltaF));
        }
    }
    return localProbability * localPdf + (1.0 - localProbability) * globalPdf;
}

public void selectLightGridSpanLight(LightGridCell cell, inout uint proposalSeed,
                              out uint lightIndex) {
    ConstPtr<LightGridSpan> spans = ConstPtr<LightGridSpan>(pc.lightGridSpanAddr);
    float aliasSample = rndf(proposalSeed) * float(cell.spanCount);
    uint column = min(uint(aliasSample), cell.spanCount - 1u);
    LightGridSpan span = spans[cell.spanOffset + column];
    bool self = aliasSample - float(column) < span.accept;
    uint firstLight = self ? span.firstLight : span.aliasFirstLight;
    uint lightCount = self ? (span.packedLightCounts & 0xffffu)
            : (span.packedLightCounts >> 16u);
    selectSectionLight(firstLight, lightCount, proposalSeed, lightIndex);
}

// Sample the exact mixture q = alpha*qCell + (1-alpha)*qGlobal. qCell first selects a nearby section
// by emitted power and section distance, then its section-local power alias. Every light in
// the neighborhood is represented without a fixed cap; qGlobal gives distant lights full support.
// Alias spans make weighted local selection O(1). The mixture PDF is reconstructed after the single
// Light48 load: section power cancels with the section-local light PDF.
public void selectLightGridLight(LightGridCell cell, bool useLocal, inout uint proposalSeed,
                          out uint lightIndex) {
    if (useLocal) {
        selectLightGridSpanLight(cell, proposalSeed, lightIndex);
    } else {
        selectGlobalLight(proposalSeed, lightIndex);
    }
}

// ---- RIS cost profile. Temporary probes pinned the candidate walk out of the shader to bound what
// memory-side RIS work could ever be worth. Against an
// 18.0ms frame at M=8, removing every dependent load (span -> alias -> record) and all lane divergence
// saved 5.9ms — a third of the frame. Split by vertex that was ~4.3ms at secondary vertices against
// ~1.1ms at the primary hit, and forcing coherent selection recovered at most 1.3ms of it. So the cost
// was chasing depth, not divergence, and it lived at secondary vertices. A presampled candidate pool
// would attack the same 5.9ms structurally, but see the note on
// proposalSeed in tracePath for why it should share the pool rather than the seed.

// Initial RIS over M power-weighted candidates -> one resampled sample, no shadow ray yet. The chosen
// sample's W = wSum / (M * p-hat); M counts every candidate, including zero-weight ones.
public Reservoir risInitial(float3 hitPos, float3 n, float3 v, float3 rd, float3 diffAlb, float3 F0,
                     float rough, bool twoSided, float sss, inout uint seed, inout uint proposalSeed) {
    Reservoir r = resEmpty();
    LightGridCell gridCell;
    int3 gridCellCoord;
    float3 gridLookup = hitPos;
    if (pc.lightGridCellAddr != 0) {
        // Stochastically blend across hard section boundaries. Every cell proposal retains full global
        // support, so conditioning on this independently jittered lookup remains unbiased.
        gridLookup += (float3(rndf(proposalSeed), rndf(proposalSeed), rndf(proposalSeed)) - 0.5)
                * worldPush.lightGridOrigin.w;
    }
    bool hasGridCell = findLightGridCell(gridLookup, gridCell, gridCellCoord);
    uint candidateCount = worldPush.risCandidates;
    r.M = float(candidateCount);
    // Deterministically stratify the proposal mixture. At the default M=8 this schedules exactly six
    // local and two global candidates, with every lane taking the same branch for a given candidate.
    // Counts that are not divisible by four use the nearest practical split with at least one global
    // candidate; M=1 is global-only so the estimator retains full support.
    uint globalCandidateCount = hasGridCell
            ? max(1u, (candidateCount + 2u) / 4u) : candidateCount;
    uint localCandidateCount = candidateCount - globalCandidateCount;
    float localProbability = float(localCandidateCount) / float(candidateCount);
    for (uint c = 0u; c < candidateCount; c++) {
        uint li;
        uint globalsBefore = (c * globalCandidateCount) / candidateCount;
        uint globalsAfter = ((c + 1u) * globalCandidateCount) / candidateCount;
        bool useLocal = hasGridCell && globalsAfter == globalsBefore;
        selectLightGridLight(gridCell, useLocal, proposalSeed, li);
        Light lg = ConstPtr<Light>(pc.lightBufAddr)[li];
        // Soft shadows: uniform point on the emitter rectangle ((s,t) in [-1,1]^2, pdf 1/area).
        float s = rndf(seed) * 2.0 - 1.0;
        float t = rndf(seed) * 2.0 - 1.0;
        float3 sp = lg.pos + worldPush.lightRebase.xyz
                + s * lightHalfU(lg) + t * lightHalfV(lg);
        float3 rawLe = lightRadiance(lg);
        float3 le = rawLe * max(worldPush.lightScales.x, 0.0);
        float3 lightNormal = lightGeometricNormal(lg);
        float area = lightArea(lg);
        float phat;
        evalSampleContrib(sp, lightNormal, le, area, hitPos, n, v, rd,
                diffAlb, F0, rough, twoSided, sss, phat);
        if (phat <= 0.0) {
            continue;
        }
        float sourcePdf = proposalPdf(lg, rawLe, gridCell, gridCellCoord, localProbability);
        float w = phat / max(sourcePdf, 1.0e-20);
        r.wSum += w;
        if (rndf(seed) * r.wSum < w) { // weighted reservoir update
            r.pos = sp;
            r.lnrm = lightNormal;
            r.le = le;
            r.area = area;
            r.phat = phat;
            r.packedLe = lg.le;
            r.age = 0u;
        }
    }
    r.W = r.phat > 0.0 ? (r.wSum / (r.M * r.phat)) : 0.0;
    return r;
}

// ---- ReSTIR DI/GI history ---------------------------------------------------------------
//
// The first emitter-lighting vertex of the primary continuation is stable enough to reproject. It is
// direct illumination at an ordinary camera hit and transported indirect illumination when the primary
// pass handed us a reflection/refraction continuation. Deeper random-walk vertices keep independent RIS:
// their screen-space identity is not stable, and pretending otherwise causes much worse ghosting than the
// variance it removes. Four validated neighbours from the PREVIOUS buffer provide spatial reuse without
// a same-dispatch read/write race; the reprojected centre provides temporal reuse.

public float2 restirOctEncode(float3 direction) {
    float3 n = direction / max(abs(direction.x) + abs(direction.y) + abs(direction.z), 1.0e-20);
    float2 signNotZero = float2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    float2 oct = n.z >= 0.0 ? n.xy : (1.0 - abs(n.yx)) * signNotZero;
    return oct * 0.5 + 0.5;
}

public float3 restirOctDecode(float2 encoded) {
    float2 oct = encoded * 2.0 - 1.0;
    float3 n = float3(oct, 1.0 - abs(oct.x) - abs(oct.y));
    if (n.z < 0.0) {
        float2 signNotZero = float2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
        n.xy = (1.0 - abs(n.yx)) * signNotZero;
    }
    return normalize(n);
}

public uint restirPackNormal(float3 n) {
    uint2 q = uint2(round(clamp(restirOctEncode(normalize(n)), 0.0, 1.0) * 65535.0));
    return q.x | (q.y << 16u);
}

public float3 restirUnpackNormal(uint p) {
    return restirOctDecode(float2(p & 0xffffu, p >> 16u) / 65535.0);
}

public void restirInvalidate(uint pixelIndex) {
    if (pc.restirCurrentAddr == 0) return;
    PackedRestirReservoir record = {};
    record.meta = 0u;
    DevicePtr<PackedRestirReservoir>(pc.restirCurrentAddr)[pixelIndex] = record;
}

public bool restirLoadCandidate(PackedRestirReservoir record, float3 expectedPreviousSurface,
                         float3 currentNormal, bool twoSided, float maxPositionDelta,
                         float minNormalDot, out Reservoir candidate) {
    candidate = resEmpty();
    uint storedM = record.meta & 0xffffu;
    uint age = (record.meta >> 16u) & 0xffu;
    uint generation = (record.meta >> 24u) & 0x7fu;
    bool storedTwoSided = (record.meta & 0x80000000u) != 0u;
    if (storedM == 0u || age > RESTIR_MAX_AGE || storedTwoSided != twoSided
            || generation != (pc.lightGeneration & 0x7fu)
            || record.W <= 0.0 || record.area <= 0.0) {
        return false;
    }

    // Records are relative to the camera that produced them. camDelta is current-minus-previous, so
    // this reconstructs both old positions in the current terrain-rebased coordinate system.
    float3 previousSurface = record.surfaceCameraRelative
            + worldPush.camOffset - worldPush.camDelta;
    float3 previousNormal = restirUnpackNormal(record.surfaceNormal);
    if (length(previousSurface - expectedPreviousSurface) > maxPositionDelta
            || dot(previousNormal, currentNormal) < minNormalDot) {
        return false;
    }

    candidate.pos = record.sampleCameraRelative + worldPush.camOffset - worldPush.camDelta;
    candidate.lnrm = restirUnpackNormal(record.sampleNormal);
    candidate.packedLe = record.packedLe;
    candidate.le = lightRadiancePacked(record.packedLe) * max(worldPush.lightScales.x, 0.0);
    candidate.area = record.area;
    candidate.M = float(storedM);
    candidate.W = record.W;
    candidate.age = age;
    return true;
}

// Combine one already-finalized source reservoir at the CURRENT receiver. The source's representative
// stands for source.M candidates, therefore its resampling weight is pHat_current * source.W * source.M.
// M is always accumulated, even when the target is zero. This is the weighted-reservoir identity that
// avoids treating a history reservoir as merely one fresh candidate.
public void restirMerge(inout Reservoir dst, Reservoir source, float maxM,
                 float3 hitPos, float3 n, float3 v, float3 rd, float3 diffAlb, float3 F0,
                 float rough, bool twoSided, float sss, inout uint seed) {
    float acceptedM = min(source.M, max(0.0, maxM - dst.M));
    if (acceptedM <= 0.0 || source.W <= 0.0) return;

    float target;
    evalSampleContrib(source.pos, source.lnrm, source.le, source.area,
            hitPos, n, v, rd, diffAlb, F0, rough, twoSided, sss, target);
    float w = target * source.W * acceptedM;
    dst.M += acceptedM;
    if (w <= 0.0) return;
    dst.wSum += w;
    if (rndf(seed) * dst.wSum < w) {
        dst.pos = source.pos;
        dst.lnrm = source.lnrm;
        dst.le = source.le;
        dst.area = source.area;
        dst.phat = target;
        dst.packedLe = source.packedLe;
        dst.age = min(source.age + 1u, RESTIR_MAX_AGE);
    }
}

public bool restirPreviousPixel(float3 hitPos, float3 motionPrev, uint2 dimensions,
                         out int2 previousPixel) {
    float3 currentCameraRelative = hitPos - worldPush.camOffset;
    float4 clip = mul(worldPush.prevViewProj,
            float4(currentCameraRelative + worldPush.camDelta - motionPrev, 1.0));
    if (clip.w <= 0.0) {
        previousPixel = int2(0, 0);
        return false;
    }
    float2 uv = clip.xy / clip.w * 0.5 + 0.5;
    previousPixel = int2(floor(uv * float2(dimensions)));
    return all(previousPixel >= int2(0, 0)) && all(previousPixel < int2(dimensions));
}

public int2 restirSpatialOffset(uint sampleIndex, uint hash) {
    int2 offset;
    switch (sampleIndex & 3u) {
        case 0u: offset = int2(1, 0); break;
        case 1u: offset = int2(0, 1); break;
        case 2u: offset = int2(-1, 0); break;
        default: offset = int2(0, -1); break;
    }
    uint rotation = hash & 3u;
    if (rotation == 1u) offset = int2(-offset.y, offset.x);
    else if (rotation == 2u) offset = -offset;
    else if (rotation == 3u) offset = int2(offset.y, -offset.x);
    int radius = 1 + int((hash >> (2u + sampleIndex * 3u)) & 7u);
    return offset * radius;
}

public void restirStore(Reservoir r, uint pixelIndex, float3 surfacePos, float3 surfaceNormal,
                 bool twoSided) {
    if (pc.restirCurrentAddr == 0 || r.W <= 0.0 || r.M <= 0.0 || r.phat <= 0.0) return;
    PackedRestirReservoir record;
    record.sampleCameraRelative = r.pos - worldPush.camOffset;
    record.area = r.area;
    record.surfaceCameraRelative = surfacePos - worldPush.camOffset;
    record.W = r.W;
    record.sampleNormal = restirPackNormal(r.lnrm);
    record.packedLe = r.packedLe;
    record.surfaceNormal = restirPackNormal(surfaceNormal);
    uint storedM = min(uint(round(r.M)), 0xffffu);
    record.meta = storedM
            | (min(r.age, 0xffu) << 16u)
            | ((pc.lightGeneration & 0x7fu) << 24u)
            | (twoSided ? 0x80000000u : 0u);
    DevicePtr<PackedRestirReservoir>(pc.restirCurrentAddr)[pixelIndex] = record;
}

public Reservoir restirSpatiotemporal(Reservoir initial, uint2 pixel, uint2 dimensions,
                       float3 hitPos, float3 motionPrev, float3 n, float3 v, float3 rd,
                       float3 diffAlb, float3 F0, float rough, bool twoSided, float sss,
                       inout uint seed) {
    Reservoir result = initial;
    float maxM = max(initial.M, initial.M * RESTIR_MAX_HISTORY_FACTOR);
    int2 previousPixel;
    if (!restirPreviousPixel(hitPos, motionPrev, dimensions, previousPixel)) {
        restirStore(result, pixel.y * dimensions.x + pixel.x, hitPos, n, twoSided);
        return result;
    }

    ConstPtr<PackedRestirReservoir> history =
            ConstPtr<PackedRestirReservoir>(pc.restirPreviousAddr);
    float cameraDistance = length(hitPos - worldPush.camOffset);
    float3 expectedPreviousSurface = hitPos - motionPrev;
    Reservoir candidate;
    uint previousIndex = uint(previousPixel.y) * dimensions.x + uint(previousPixel.x);
    if (restirLoadCandidate(history[previousIndex], expectedPreviousSurface, n, twoSided,
            max(0.20, cameraDistance * 0.01), 0.90, candidate)) {
        // Leave capacity for neighbours even after temporal history reaches its age/M ceiling.
        candidate.M = min(candidate.M, initial.M * 8.0);
        restirMerge(result, candidate, maxM, hitPos, n, v, rd, diffAlb, F0, rough,
                twoSided, sss, seed);
    }

    // Read neighbours only from last frame's immutable buffer. This is a genuine spatial pass but does
    // not depend on undefined cross-invocation ordering in the current raygen dispatch.
    uint spatialHash = pixel.x * 1973u + pixel.y * 9277u
            + worldPush.frameIndex * 26699u;
    spatialHash = pcg(spatialHash);
    for (uint i = 0u; i < RESTIR_SPATIAL_SAMPLES; ++i) {
        int2 neighbour = previousPixel + restirSpatialOffset(i, spatialHash);
        if (any(neighbour < int2(0, 0)) || any(neighbour >= int2(dimensions))) continue;
        uint neighbourIndex = uint(neighbour.y) * dimensions.x + uint(neighbour.x);
        if (restirLoadCandidate(history[neighbourIndex], expectedPreviousSurface, n, twoSided,
                max(1.5, cameraDistance * 0.05), 0.75, candidate)) {
            candidate.M = min(candidate.M, initial.M * 3.0);
            restirMerge(result, candidate, maxM, hitPos, n, v, rd, diffAlb, F0, rough,
                    twoSided, sss, seed);
        }
    }

    result.W = result.phat > 0.0 && result.M > 0.0
            ? result.wSum / (result.M * result.phat) : 0.0;
    restirStore(result, pixel.y * dimensions.x + pixel.x, hitPos, n, twoSided);
    return result;
}

// Shade a finalized reservoir: recompute the survivor's contribution at (hitPos,n,v), cast ONE shadow
// ray, and return throughput-free radiance contrib*vis*W. The one ray serves whichever term fired for
// the survivor (front BRDF, twoSided billboard, or SSS backscatter) — the origin is biased toward the
// sample's side of the surface.
public float3 shadeReservoir(Reservoir s, float3 hitPos, float3 n, float3 v, float3 rd, float3 diffAlb,
                      float3 F0, float rough, bool twoSided, float sss) {
    if (s.W <= 0.0 || s.phat <= 0.0) {
        return float3(0.0, 0.0, 0.0);
    }
    float phat;
    float3 contrib = evalSampleContrib(s.pos, s.lnrm, s.le, s.area, hitPos, n, v, rd,
            diffAlb, F0, rough, twoSided, sss, phat);
    if (phat <= 0.0) {
        return float3(0.0, 0.0, 0.0);
    }
    // Pick the shadow-ray origin on the sample's side of the surface FIRST, then measure the ray from
    // that origin — measuring dist from hitPos while tracing from the biased origin shifts the ray tip
    // up to SURF_BIAS*ndl toward the emitter plane, beating the 0.001*dist stop-short margin for lights
    // closer than ~SURF_BIAS/0.001 blocks -> shadow ray hits the emitter's own face -> per-texel black.
    float side = dot(n, s.pos - hitPos) >= 0.0 ? 1.0 : -1.0;
    float3 origin = hitPos + side * n * SURF_BIAS;
    float3 toL = s.pos - origin;
    float dist = length(toL);
    // Stop just short of the sample point so the ray doesn't self-occlude on the emitter's own face.
    VisibilityResult shadow = visibility(origin, toL / dist, dist * 0.999);
    float3 vis = shadow.transmittance;
    return contrib * vis * s.W;
}

// TLAS instance-mask bits (set on the Java side, ANDed against these per-ray cull masks):
//   bit 0 (0x01) — secondary rays: shadows, GI bounces, reflections.
//   bit 1 (0x02) — the primary camera ray.
// Terrain / entities / block entities keep 0xFF (both bits). Particles use 0x02: primary-visible
