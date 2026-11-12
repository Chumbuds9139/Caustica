package dev.comfyfluffy.caustica.rt;

/** Packing of index limits into the world pipeline's push constants (see world_common.slang). */
final class RtPushLimits {
    private RtPushLimits() {
    }

    /**
     * Pack a small flag/id (bits 0-7) with an index limit (bits 8-31) into one push-constant word; see
     * debugViewPacked / restirModePacked in world_common.slang. A limit that does not fit is sent as 0,
     * which the shaders read as "unknown" and do not clamp against.
     */
    static int pack(int low, int limit) {
        int bounded = limit > 0 && limit < (1 << 24) ? limit : 0;
        return (low & 0xFF) | (bounded << 8);
    }
}
