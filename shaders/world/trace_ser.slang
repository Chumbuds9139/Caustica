// EXT Shader Execution Reordering radiance dispatch. Kept in a separate module so the ordinary
// indirect shader never imports hit-object operations or their SPIR-V capability.

import world_common;
import world_core;
import trace;

// Check a terrain hit object against the section table before any shader is invoked on it, straight
// after traversal. Entity and LOD hits are left to the hit-shader guards: only terrain has ever been
// refused.
bool hitObjectInRange(HitObject hObj, uint stage) {
    if (!hObj.IsHit()) { return true; }
    uint instanceId = hObj.GetInstanceID();
    if ((instanceId & (ENTITY_BIT | PARTICLE_BIT)) != 0u) { return true; }
    uint geometry = hObj.GetGeometryIndex();
    uint tag = hitFaultTag(instanceId, geometry, PAYLOAD_REORDERED) | stage;
    if (!hitIndexInRange(pc.worldPushAddr, HIT_FAULT_TERRAIN_INSTANCE, instanceId,
                         worldPush.hitTableLimits.x, tag)) {
        return false;
    }
    Section sec = ConstPtr<Section>(pc.tableAddr)[instanceId];
    uint pid = hObj.GetPrimitiveIndex() + sec.triBase[geometry & 3u];
    return hitIndexInRange(pc.worldPushAddr, HIT_FAULT_TERRAIN_PRIM, pid, sec.primCount, tag);
}

// Everything the hit object reports about what it hit, folded into one word. Nothing may change it
// between traversal and Invoke: the reorder moves the thread, not the hit. It is taken on both sides of
// ReorderThread because once it did change: a terrain hit that passed the range check straight after
// traversal failed the very same check after the reorder, against a section table that cannot change
// during the dispatch. Comparing the fingerprint catches that whether or not the changed hit still fits
// the tables, and the hit is dropped before Invoke can hand a hit shader whatever it now describes.
// One word is all that spans the reorder for it.
uint hitObjectFingerprint(HitObject hObj) {
    if (hObj.IsMiss()) { return 0x4D495353u; }
    if (!hObj.IsHit()) { return 0x4E4F5020u; }
    uint h = hObj.GetInstanceID();
    h = (h ^ hObj.GetInstanceIndex()) * 0x9E3779B1u;
    h = (h ^ hObj.GetPrimitiveIndex()) * 0x85EBCA77u;
    h = (h ^ hObj.GetGeometryIndex()) * 0xC2B2AE3Du;
    h = (h ^ hObj.GetShaderTableIndex()) * 0x27D4EB2Fu;
    h = (h ^ hObj.GetHitKind()) * 0x165667B1u;
    h ^= asuint(hObj.GetRayDesc().TMax);
    return h ^ (h >> 15u);
}

// Trace into a hit object, reorder threads by hit coherence, THEN invoke the hit/miss shader. This is
// where the divergent Section/Prim shading work (world.rchit/world.rahit) actually happens, so
// reordering before it is what pays for the reorder's own cost.
//
// The payload is built TWICE from the trace state rather than carried across ReorderThread. Traversal
// runs only world.rahit, which never writes a RADIANCE payload: its terrain tint paths live in the
// translucent/water buckets, which carry no any-hit record for radiance rays, and its entity paths are
// gated on RAY_FLAG_SKIP_CLOSEST_HIT_SHADER. So whatever TraceRay leaves in the payload holds no
// information. Reading it back and carrying it over would pin all ten members — 72 bytes — in registers
// across the one point where SER must spill every live value. Rebuilding costs a few constant moves and
// leaves only three words spanning the reorder: the two payload words and the hit fingerprint.
public void traceRadianceReordered(uint cullMask, float3 ro, float tmin, float3 rd, float tmax,
                            bool showCelestial, bool rayInWater, float rayConeWidth, float rayConeSpread,
                            uint pathPhaseHint) {
    uint flags = (showCelestial ? PAYLOAD_SHOW_CELESTIAL : 0u)
            | (rayInWater && worldPush.underwaterEffects.w > 0.0 ? PAYLOAD_RAY_IN_WATER : 0u)
            | PAYLOAD_REORDERED;
    uint rayCone = packHalf2(float2(rayConeWidth, rayConeSpread));
    Payload tracePayload = makeRadiancePayload(flags, rayCone);
    HitObject hObj = HitObject::TraceRay(topLevelAS, RAY_FLAG_NONE, cullMask,
            SBT_RADIANCE, SBT_STRIDE_BUCKET, MISS_RADIANCE,
            makeRay(ro, tmin, rd, tmax), tracePayload);
    // The hit object groups shader IDs first. Its SBT index then supplies the two-bit geometry/material
    // bucket ({solid, cutout, translucent, water}); terrain and entity record ranges are both multiples
    // of TERRAIN_BUCKETS, so the low bits have the same meaning. The high two bits describe path lifetime:
    // pre-roulette, roulette-active, or guaranteed terminal after this hit.
    // A hit the section table cannot describe is dropped here, as a miss sentinel, before the scheduler
    // or a hit shader ever sees it.
    if (!hitObjectInRange(hObj, HIT_FAULT_STAGE_TRAVERSAL)) {
        payload = makeRadiancePayload(flags, rayCone);
        return;
    }
    uint fingerprint = hitObjectFingerprint(hObj);
    uint materialHint = hObj.GetShaderTableIndex() & (TERRAIN_BUCKETS - 1u);
    uint coherenceHint = (pathPhaseHint << 2u) | materialHint;
    // Diagnostic split: the same hit-object trace and invoke, without handing the thread to the scheduler.
    if ((worldPush.featureFlags & FEATURE_SER_NO_REORDER) == 0u) {
        // Diagnostic split of the sort key: the driver's hit-object grouping and the caller's hint
        // reach the scheduler through different entry points.
        uint key = (worldPush.featureFlags >> SER_KEY_SHIFT) & SER_KEY_MASK;
        if (key == SER_KEY_HIT) {
            ReorderThread(hObj);
        } else if (key == SER_KEY_HINT) {
            ReorderThread(coherenceHint, 4u);
        } else {
            ReorderThread(hObj, coherenceHint, 4u);
        }
    }
    payload = makeRadiancePayload(flags, rayCone);
    // Same fingerprint, same hit: the traversal-stage range check above already holds for it.
    uint after = hitObjectFingerprint(hObj);
    if (after != fingerprint) {
        uint instanceId = hObj.IsHit() ? hObj.GetInstanceID() : 0u;
        uint geometry = hObj.IsHit() ? hObj.GetGeometryIndex() : 0u;
        recordHitFault(pc.worldPushAddr, HIT_FAULT_HIT_OBJECT_CHANGED, after, fingerprint,
                       hitFaultTag(instanceId, geometry, PAYLOAD_REORDERED) | HIT_FAULT_STAGE_REORDER);
        return;
    }
    HitObject::Invoke(topLevelAS, hObj, payload);
}
