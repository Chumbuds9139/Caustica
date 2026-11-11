package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The watchdog replaces Minecraft's fixed "5s timeout reached when waiting for VK semaphore" crash with a
 * bounded, sliced wait. These cases pin the three properties that make that safe: a healthy GPU costs
 * exactly one vanilla-sized wait, a slow GPU is waited for, and a dead one still ends in vanilla's own
 * failure path after the budget.
 */
final class RtGpuWatchdogTest {
    private static final long SLICE = RtGpuWatchdog.VANILLA_TIMEOUT_NANOS;

    @Test
    void healthyGpuTakesOneVanillaSizedWait() {
        List<Long> slices = new ArrayList<>();
        AtomicLong clock = new AtomicLong();
        boolean done = RtGpuWatchdog.await(slice -> {
            slices.add(slice);
            return true;
        }, SLICE, 60 * 1_000_000_000L, clock::get, "test");
        assertTrue(done);
        assertEquals(List.of(SLICE), slices);
    }

    @Test
    void slowGpuIsWaitedForInSlicesUntilItFinishes() {
        AtomicLong clock = new AtomicLong();
        List<Long> slices = new ArrayList<>();
        boolean done = RtGpuWatchdog.await(slice -> {
            slices.add(slice);
            clock.addAndGet(slice); // each attempt blocks for its full slice
            return slices.size() == 4; // completes during the fourth wait: ~20 s, vanilla would have crashed
        }, SLICE, 60 * 1_000_000_000L, clock::get, "test");
        assertTrue(done);
        assertEquals(List.of(SLICE, SLICE, SLICE, SLICE), slices);
    }

    @Test
    void unresponsiveGpuStillFailsOnceTheBudgetIsSpent() {
        AtomicLong clock = new AtomicLong();
        AtomicLong waited = new AtomicLong();
        boolean done = RtGpuWatchdog.await(slice -> {
            clock.addAndGet(slice);
            waited.addAndGet(slice);
            return false;
        }, SLICE, 12 * 1_000_000_000L, clock::get, "test");
        assertFalse(done, "the caller's own timeout handling must still run");
        // 5 s + 5 s + the remaining 2 s: never longer than the budget.
        assertEquals(12 * 1_000_000_000L, waited.get());
    }

    @Test
    void budgetAtOrBelowTheVanillaTimeoutBehavesLikeVanilla() {
        AtomicLong clock = new AtomicLong();
        List<Long> slices = new ArrayList<>();
        boolean done = RtGpuWatchdog.await(slice -> {
            slices.add(slice);
            clock.addAndGet(slice);
            return false;
        }, SLICE, SLICE, clock::get, "test");
        assertFalse(done);
        assertEquals(List.of(SLICE), slices);
    }

    @Test
    void pollsAndUnboundedWaitsPassThroughUntouched() {
        List<Long> slices = new ArrayList<>();
        assertFalse(RtGpuWatchdog.await(slice -> {
            slices.add(slice);
            return false;
        }, 0L, 60 * 1_000_000_000L, () -> 0L, "test"));
        assertEquals(List.of(0L), slices);
    }

    @Test
    void configuredBudgetNeverUndercutsTheCallSite() {
        assertTrue(RtGpuWatchdog.budgetNanos(SLICE) >= SLICE);
        assertTrue(RtGpuWatchdog.budgetNanos(700 * 1_000_000_000L) >= 700 * 1_000_000_000L);
    }
}
