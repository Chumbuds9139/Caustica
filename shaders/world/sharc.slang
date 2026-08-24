// Experimental SHaRC (Spatially Hashed Radiance Cache) integration for Caustica.
//
// This is Caustica's shader-side implementation of NVIDIA's Spatial Hash Radiance Cache idea: a
// world-space, view-independent voxel cache of the local radiance observed at surfaces. The host
// allocates one persistent storage buffer and publishes its device address in WorldPush; this module
// implements the hash, the temporal blend and the query/update helpers the path tracer calls.
//
// The experimental contract is deliberately humble so it can ship while the real cache matures:
//   * Cache entries store local environment radiance at a surface (emission + direct NEE + held-item
//     light + emitter RIS + thin-surface SSS), already de-modulated from the path's throughput.
//   * Early bounces (bounce < startBounce) still trace normally and WARM the cache; later diffuse
//     bounces query it and, when a warm entry is found, use it as the indirect ambient tail and stop
//     tracing. That is what cuts path length and therefore noise in multi-bounce GI.
//   * Specular/glossy continuations and exact mirrors are never replaced — reflections keep their
//     traced sharpness and dynamic behaviour.
//   * Collisions, stale entries, normal mismatch and invalid data are all treated as "no cache data",
//     so a bad entry can never turn a pixel black.
//
// The cache is a single flat spatial hash: cell = floor(pos / cellSize), hash(cell) % entryCount.
// An entry is valid only when its stored cell matches the query cell, its frame stamp is recent
// enough, its stored normal is close enough to the query normal and its confidence is > 0.

import world_common;

// 48-byte entry (all float4/uint4 aligned, no tail padding):
//   radiance.xyz = HDR local environment radiance, radiance.w = confidence (0 = empty/invalid)
//   cellFrame.xyz = quantised integer cell coordinate, cellFrame.w = frame stamp
//   normal.xyz = stored surface normal (used as an approximate directional gate)
public struct SharcEntry {
    public float4 radiance;
    public uint4   cellFrame;
    public float4  normal;
};

// A small, non-zero confidence for any genuinely lit sample. Pure-black entries are left invalid so
// the query treats them as "no data" rather than as a lightless cache hit (which would darken GI).
public float sharcConfidence(float3 radiance) {
    float maxC = max(radiance.r, max(radiance.g, radiance.b));
    return maxC > 1.0e-4 ? 0.75 : 0.0;
}

// The cache is only usable when the host has published a real buffer and sane parameters.
public bool sharcReady(WorldPush push) {
    if (push.sharcCacheAddr == 0) {
        return false;
    }
    uint count = uint(push.sharcGridOrigin.w);
    if (count == 0u || push.sharcParams.x <= 0.0 || push.sharcParams2.z <= 0.0) {
        return false;
    }
    return true;
}

// Integer spatial hash (xorshift-multiply over the cell coordinate).
public uint sharcHash(uint3 cell) {
    uint h = cell.x * 0x9E3779B1u;
    h ^= cell.y * 0x85EBCA77u;
    h ^= cell.z * 0xC2B2AE3Du;
    h ^= h >> 16u;
    h *= 0x7FEB352Du;
    h ^= h >> 15u;
    h *= 0x846CA68Bu;
    h ^= h >> 16u;
    return h;
}

public uint3 sharcCell(WorldPush push, float3 pos) {
    float cellSize = push.sharcParams.x;
    float3 floored = floor(pos / cellSize);
    return uint3(floored.x, floored.y, floored.z);
}

// Looks up an entry for `pos`/`n` and returns the cached radiance scaled by the normal agreement.
// Returns false for any stale, colliding or unconfident entry — callers must treat that as "trace
// normally" rather than clamp/blacken the cached value.
public bool sharcQuery(WorldPush push, float3 pos, float3 n,
                       out float3 radiance, out float confidence) {
    radiance = float3(0.0, 0.0, 0.0);
    confidence = 0.0;
    if (!sharcReady(push)) {
        return false;
    }
    uint count = uint(push.sharcGridOrigin.w);
    float cellSize = push.sharcParams.x;
    uint3 cell = sharcCell(push, pos);
    uint index = sharcHash(cell) % count;

    SharcEntry entry = ConstPtr<SharcEntry>(push.sharcCacheAddr)[index];
    if (entry.cellFrame.x != cell.x || entry.cellFrame.y != cell.y || entry.cellFrame.z != cell.z) {
        return false; // spatial-hash collision
    }
    if (entry.cellFrame.w == 0u) {
        return false; // empty
    }
    uint age = push.frameIndex - entry.cellFrame.w;
    if (age > uint(push.sharcParams2.z)) {
        return false; // stale
    }
    if (entry.radiance.w <= 0.0) {
        return false; // invalid/empty confidence
    }
    float3 storedNormal = normalize(entry.normal.xyz + float3(1.0e-6, 1.0e-6, 1.0e-6));
    float nd = saturate(dot(storedNormal, n));
    if (nd < push.sharcParams2.w) {
        return false; // surface orientation differs too much
    }
    float3 center = (float3(cell.x, cell.y, cell.z) + float3(0.5, 0.5, 0.5)) * cellSize;
    float distance = length(pos - center);
    if (distance > push.sharcParams.w) {
        return false; // too far from the cell the cache describes
    }
    radiance = entry.radiance.xyz * nd;
    confidence = entry.radiance.w;
    return confidence > 0.0;
}

// Warms / refreshes a cache entry with the local environment radiance observed at a surface. The
// caller passes `seed` so the update-coverage gate can be varied per path without making the same
// cell flicker between adjacent pixels. Reads the current entry, blends it and writes it back.
public void sharcWrite(WorldPush push, float3 pos, float3 n, float3 radiance,
                       float confidence, uint seed) {
    if (!sharcReady(push)) {
        return;
    }
    // A single non-finite sample must never poison a cache entry: it would spread through the
    // temporal blend and then through every query that hits the cell.
    if (any(isnan(radiance)) || any(isinf(radiance))) {
        return;
    }
    if (confidence <= 0.0) {
        return; // a truly unlit sample would only normalize a warm entry toward black
    }
    uint count = uint(push.sharcGridOrigin.w);
    float cellSize = push.sharcParams.x;
    uint3 cell = sharcCell(push, pos);

    // Update coverage: only a fraction of the paths that see a cell actually write it. Deterministic
    // per cell+frame (plus the path seed) so a visible cell is refreshed evenly rather than gating on
    // a per-path coin flip that would starve the same cell in half the frames.
    uint coverageThresh = uint(clamp(push.sharcParams2.y, 0.0, 1.0) * 1000.0);
    uint coverageHash = sharcHash(cell) ^ (push.frameIndex * 0x9E3779B1u) ^ seed;
    if (coverageHash % 1000u >= coverageThresh) {
        return;
    }

    uint index = sharcHash(cell) % count;
    DevicePtr<SharcEntry> cache = DevicePtr<SharcEntry>(push.sharcCacheAddr);
    SharcEntry entry = cache[index];
    bool sameCell = entry.cellFrame.x == cell.x
            && entry.cellFrame.y == cell.y
            && entry.cellFrame.z == cell.z;
    bool occupied = sameCell && entry.cellFrame.w != 0u;
    bool stale = occupied && (push.frameIndex - entry.cellFrame.w > uint(push.sharcParams2.z));
    // A different live cell owns this hash slot: leave it alone (an aggressive overwrite would make
    // the cache churn between near misses and destroy temporal reuse).
    if (!sameCell && entry.cellFrame.w != 0u && !stale) {
        return;
    }

    float blend = clamp(push.sharcParams.z, 0.0, 0.99);
    if (!occupied || stale) {
        blend = 1.0;
    }
    float3 oldRadiance = entry.radiance.xyz;
    float oldConfidence = entry.radiance.w;

    SharcEntry updated;
    updated.radiance = float4(lerp(oldRadiance, clamp(radiance,
                              float3(0.0, 0.0, 0.0), float3(65504.0, 65504.0, 65504.0)), blend),
                              lerp(oldConfidence, confidence, blend));
    updated.cellFrame = uint4(cell.x, cell.y, cell.z, push.frameIndex);
    updated.normal = float4(normalize(n + float3(1.0e-6, 1.0e-6, 1.0e-6)), 0.0);
    cache[index] = updated;
}
