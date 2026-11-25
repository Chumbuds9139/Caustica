package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtFgWatchdogTest {
    private static final float REAL = 0.45f;
    private static final float BLACK = 0.0f;

    /** Feed n samples at the current epoch, one evaluation each; returns the first non-NONE action. */
    private static RtFgWatchdog.Action feed(RtFgWatchdog w, int n, float gen, float real) {
        for (int i = 0; i < n; i++) {
            w.onEvaluation();
            RtFgWatchdog.Action a = w.judge(w.epoch(), gen, real);
            if (a != RtFgWatchdog.Action.NONE) {
                return a;
            }
        }
        return RtFgWatchdog.Action.NONE;
    }

    @Test
    void normalFramesNeverAct() {
        RtFgWatchdog w = new RtFgWatchdog();
        assertEquals(RtFgWatchdog.Action.NONE, feed(w, 5000, 0.43f, REAL));
        assertEquals(0, w.rung());
    }

    @Test
    void steadyBlackRestartsAfterTheStreak() {
        RtFgWatchdog w = new RtFgWatchdog();
        assertEquals(RtFgWatchdog.Action.NONE, feed(w, RtFgWatchdog.BLACK_STREAK - 1, BLACK, REAL));
        w.onEvaluation();
        assertEquals(RtFgWatchdog.Action.RESTART, w.judge(w.epoch(), BLACK, REAL));
    }

    @Test
    void aGoodFrameBreaksTheStreak() {
        RtFgWatchdog w = new RtFgWatchdog();
        for (int i = 0; i < 100; i++) {
            assertEquals(RtFgWatchdog.Action.NONE, feed(w, RtFgWatchdog.BLACK_STREAK - 1, BLACK, REAL));
            assertEquals(RtFgWatchdog.Action.NONE, feed(w, 1, 0.44f, REAL));
        }
    }

    @Test
    void darkScenesAreNotJudged() {
        RtFgWatchdog w = new RtFgWatchdog();
        assertEquals(RtFgWatchdog.Action.NONE, feed(w, 1000, BLACK, 0.02f));
        assertFalse(RtFgWatchdog.isBlack(0.0f, Float.NaN));
        assertTrue(RtFgWatchdog.isBlack(Float.NaN, REAL));
    }

    @Test
    void samplesFromBeforeAnActionAreIgnored() {
        RtFgWatchdog w = new RtFgWatchdog();
        assertEquals(RtFgWatchdog.Action.RESTART, feed(w, RtFgWatchdog.BLACK_STREAK, BLACK, REAL));
        int stale = w.epoch() - 1;
        // The ring still holds a full ring's worth of black samples recorded before the restart.
        for (int i = 0; i < 64; i++) {
            w.onEvaluation();
            assertEquals(RtFgWatchdog.Action.NONE, w.judge(stale, BLACK, REAL));
        }
        assertEquals(1, w.rung());
    }

    @Test
    void blackAgainSoonClimbsTheLadder() {
        RtFgWatchdog w = new RtFgWatchdog();
        assertEquals(RtFgWatchdog.Action.RESTART, feed(w, RtFgWatchdog.BLACK_STREAK, BLACK, REAL));
        assertEquals(RtFgWatchdog.Action.FRESH_FEATURE, feed(w, RtFgWatchdog.BLACK_STREAK, BLACK, REAL));
        assertEquals(RtFgWatchdog.Action.DUPLICATE, feed(w, RtFgWatchdog.BLACK_STREAK, BLACK, REAL));
    }

    @Test
    void blackLongAfterAnActionStartsOverAtTheFirstRung() {
        RtFgWatchdog w = new RtFgWatchdog();
        assertEquals(RtFgWatchdog.Action.RESTART, feed(w, RtFgWatchdog.BLACK_STREAK, BLACK, REAL));
        assertEquals(RtFgWatchdog.Action.NONE, feed(w, RtFgWatchdog.ESCALATE_WINDOW + 1, 0.44f, REAL));
        assertEquals(RtFgWatchdog.Action.RESTART, feed(w, RtFgWatchdog.BLACK_STREAK, BLACK, REAL));
    }

    @Test
    void clearingTheEscalationStartsOverAndDropsInFlightSamples() {
        RtFgWatchdog w = new RtFgWatchdog();
        assertEquals(RtFgWatchdog.Action.RESTART, feed(w, RtFgWatchdog.BLACK_STREAK, BLACK, REAL));
        assertEquals(RtFgWatchdog.Action.NONE, feed(w, RtFgWatchdog.BLACK_STREAK - 1, BLACK, REAL));
        int before = w.epoch();
        w.clearEscalation();
        assertEquals(0, w.rung());
        w.onEvaluation();
        assertEquals(RtFgWatchdog.Action.NONE, w.judge(before, BLACK, REAL));
        assertEquals(RtFgWatchdog.Action.RESTART, feed(w, RtFgWatchdog.BLACK_STREAK, BLACK, REAL));
    }

    @Test
    void lumaSpansZeroToOne() {
        assertEquals(0.0f, RtFgWatchdog.luma(0, 0, 0), 1e-6f);
        assertEquals(1.0f, RtFgWatchdog.luma(255, 255, 255), 1e-5f);
    }
}
