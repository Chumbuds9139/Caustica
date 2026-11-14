package dev.comfyfluffy.caustica.rt.accel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The registry is static, so every test uses addresses of its own. */
final class RtAccelRegistryTest {
    @AfterEach
    void stopTracking() {
        RtAccelRegistry.trackGraphics(null, null);
    }

    private static boolean accept(long address, long graphicsUse) {
        synchronized (RtAccelRegistry.lock()) {
            return RtAccelRegistry.acceptLocked(address, graphicsUse);
        }
    }

    @Test
    void destroyedStructureIsRefusedAndNamed() {
        long address = 0x1_0000_0000L;
        RtAccelRegistry.created(address, 11L, "terrain section 1,2,3 BLAS");
        assertTrue(accept(address, 1L));

        RtAccelRegistry.destroyed(address, 11L);
        assertFalse(accept(address, 2L));
        String described = RtAccelRegistry.describeDead(address);
        assertTrue(described.contains("terrain section 1,2,3 BLAS"), described);
        assertTrue(described.contains("destroyed"), described);
    }

    @Test
    void addressInsideAnotherStructureIsNotAStructure() {
        // The old check accepted any address inside a live buffer; only a structure's exact address counts.
        long address = 0x2_0000_0000L;
        RtAccelRegistry.created(address, 21L, "entity BLAS");
        assertFalse(accept(address + 0x100L, 1L));
        RtAccelRegistry.destroyed(address, 21L);
    }

    @Test
    void destroyOfAnOlderStructureKeepsTheNewOneAtTheSameAddress() {
        long address = 0x3_0000_0000L;
        RtAccelRegistry.created(address, 31L, "old");
        RtAccelRegistry.created(address, 32L, "new");
        RtAccelRegistry.destroyed(address, 31L);
        assertTrue(accept(address, 1L));
        RtAccelRegistry.destroyed(address, 32L);
        assertFalse(accept(address, 1L));
    }

    @Test
    void destroyBeforeTheLastUsingFrameFinishesIsCounted() {
        long[] completed = {5L};
        RtAccelRegistry.trackGraphics(() -> completed[0], () -> 6L);
        long address = 0x4_0000_0000L;
        RtAccelRegistry.created(address, 41L, "in flight");
        assertTrue(accept(address, 6L));
        int before = RtAccelRegistry.inFlightDestroyCount();
        RtAccelRegistry.destroyed(address, 41L);
        assertEquals(before + 1, RtAccelRegistry.inFlightDestroyCount());
    }

    @Test
    void destroyAfterTheLastUsingFrameFinishedIsNotCounted() {
        RtAccelRegistry.trackGraphics(() -> 10L, () -> 10L);
        long address = 0x5_0000_0000L;
        RtAccelRegistry.created(address, 51L, "done");
        assertTrue(accept(address, 7L));
        int before = RtAccelRegistry.inFlightDestroyCount();
        RtAccelRegistry.destroyed(address, 51L);
        assertEquals(before, RtAccelRegistry.inFlightDestroyCount());
    }

    @Test
    void neverReferencedOrUntrackedDestroysAreNotCounted() {
        RtAccelRegistry.trackGraphics(() -> 0L, () -> 0L);
        long unused = 0x6_0000_0000L;
        RtAccelRegistry.created(unused, 61L, "never in a TLAS");
        int before = RtAccelRegistry.inFlightDestroyCount();
        RtAccelRegistry.destroyed(unused, 61L);
        assertEquals(before, RtAccelRegistry.inFlightDestroyCount());

        RtAccelRegistry.trackGraphics(null, null); // device idle for good
        long idle = 0x7_0000_0000L;
        RtAccelRegistry.created(idle, 71L, "torn down");
        assertTrue(accept(idle, 99L));
        RtAccelRegistry.destroyed(idle, 71L);
        assertEquals(before, RtAccelRegistry.inFlightDestroyCount());
    }
}
