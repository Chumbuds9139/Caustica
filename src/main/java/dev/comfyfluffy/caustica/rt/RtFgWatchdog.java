package dev.comfyfluffy.caustica.rt;

/**
 * Decides when DLSS-G's generated frames have gone black and what to do about it.
 *
 * <p>The failure this watches for: every generated frame comes out black, alternating with the real
 * frames, and stays that way until frame generation is switched off and on again. Switching it off and
 * on does nothing but stop the generated presents for a moment and then evaluate once with the reset
 * flag, so the first rung does exactly that. A run of black frames soon after an action climbs to the
 * next rung: a fresh feature, then duplicates of the real frame until frame generation restarts.
 *
 * <p>Fed one brightness sample per evaluation: the mean encoded luma of a tiny downsample of the
 * generated frame and of the real frame it belongs to, read back several evaluations later. Each sample
 * carries the epoch it was recorded in; anything that restarts DLSS-G's history bumps the epoch, so
 * samples still in flight from before it are ignored instead of being blamed on the new history.
 *
 * <p>Pure logic, no Vulkan, so it is unit-tested.
 */
public final class RtFgWatchdog {
    public enum Action { NONE, RESTART, FRESH_FEATURE, DUPLICATE }

    /** Consecutive black generated frames before acting: about a quarter of a second at 30 real fps. */
    static final int BLACK_STREAK = 8;
    /** A real frame dimmer than this (mean encoded luma, 0..1) cannot tell a black generated frame from a dark scene. */
    static final float MIN_REAL_LUMA = 0.04f;
    /** A generated frame counts as black below this fraction of its real frame's brightness. */
    static final float BLACK_RATIO = 0.2f;
    /** Another black run within this many evaluations of the last action climbs to the next rung. */
    static final int ESCALATE_WINDOW = 900;

    private int epoch;
    private int streak;
    private int rung;
    private int evalsSinceAction = Integer.MAX_VALUE;
    private float lastGenLuma = Float.NaN;
    private float lastRealLuma = Float.NaN;

    /** The epoch to tag a sample recorded now with. */
    public int epoch() {
        return epoch;
    }

    /** Samples still in flight no longer describe DLSS-G's history (a reset, a new feature, a cut). */
    public void invalidate() {
        epoch++;
        streak = 0;
    }

    /** Frame generation was restarted from outside (a world change, switched off and on): start the ladder over. */
    public void clearEscalation() {
        rung = 0;
        evalsSinceAction = Integer.MAX_VALUE;
        invalidate();
    }

    /** Call once per DLSS-G evaluation. */
    public void onEvaluation() {
        if (evalsSinceAction != Integer.MAX_VALUE) {
            evalsSinceAction++;
        }
    }

    /** Judge one read-back sample; returns what to do now (usually nothing). */
    public Action judge(int sampleEpoch, float genLuma, float realLuma) {
        lastGenLuma = genLuma;
        lastRealLuma = realLuma;
        if (sampleEpoch != epoch) {
            return Action.NONE;
        }
        if (!isBlack(genLuma, realLuma)) {
            streak = 0;
            return Action.NONE;
        }
        if (++streak < BLACK_STREAK) {
            return Action.NONE;
        }
        rung = (rung == 0 || evalsSinceAction > ESCALATE_WINDOW) ? 1 : Math.min(rung + 1, 3);
        evalsSinceAction = 0;
        invalidate();
        return switch (rung) {
            case 1 -> Action.RESTART;
            case 2 -> Action.FRESH_FEATURE;
            default -> Action.DUPLICATE;
        };
    }

    /** NaN-safe: an unreadable real frame never counts, an unreadable generated frame counts as black. */
    static boolean isBlack(float genLuma, float realLuma) {
        if (!(realLuma >= MIN_REAL_LUMA)) {
            return false;
        }
        return !(genLuma >= realLuma * BLACK_RATIO);
    }

    /** Rec. 709 luma of an 8-bit encoded texel, 0..1. */
    public static float luma(int r, int g, int b) {
        return (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255.0f;
    }

    public int rung() {
        return rung;
    }

    public float lastGenLuma() {
        return lastGenLuma;
    }

    public float lastRealLuma() {
        return lastRealLuma;
    }
}
