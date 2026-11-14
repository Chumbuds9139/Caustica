package dev.comfyfluffy.caustica.rt.accel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtScratchPoolTest {
    private static final long KIB = 1L << 10;
    private static final long MIB = 1L << 20;
    private static long nextAddress = 0x40_0000_0000L;

    /** Hands out fake buffers and records frees; nothing here touches Vulkan. */
    private static final class FakeAllocator implements RtScratchPool.Allocator {
        final List<RtBuffer> created = new ArrayList<>();
        final List<RtBuffer> freed = new ArrayList<>();

        @Override
        public RtBuffer create(long size, String label) {
            long address;
            synchronized (RtScratchPoolTest.class) {
                address = nextAddress;
                nextAddress += 1L << 32;
            }
            RtBuffer buffer = new RtBuffer(0L, address, 0L, address, 0L, size, 0, false, label);
            created.add(buffer);
            return buffer;
        }

        @Override
        public void free(RtBuffer buffer) {
            freed.add(buffer);
        }
    }

    private static final class FakeProgress implements RtScratchPool.Progress {
        long completedGraphics;
        long reservedGraphics;
        long finishedBuild;
        long assignedBuild;
        boolean lost;

        @Override
        public long completedGraphics() {
            if (lost) {
                throw new IllegalStateException("device lost");
            }
            return completedGraphics;
        }

        @Override
        public long reservedGraphics() {
            return reservedGraphics;
        }

        @Override
        public long finishedBuild() {
            return finishedBuild;
        }

        @Override
        public long assignedBuild() {
            return assignedBuild;
        }
    }

    private final FakeAllocator allocator = new FakeAllocator();
    private final FakeProgress progress = new FakeProgress();
    private final RtScratchPool pool = new RtScratchPool(allocator, progress, 256L);

    @Test
    void sizeClassesAreQuarterStepsAboveTheMinimum() {
        assertEquals(16 * KIB, RtScratchPool.classSize(1L));
        assertEquals(16 * KIB, RtScratchPool.classSize(16 * KIB));
        assertEquals(20 * KIB, RtScratchPool.classSize(16 * KIB + 1));
        assertEquals(40 * KIB, RtScratchPool.classSize(33 * KIB));
        assertEquals(64 * KIB, RtScratchPool.classSize(64 * KIB));
        for (long bytes = 1L; bytes < 256 * MIB; bytes = bytes * 3 + 7) {
            long size = RtScratchPool.classSize(bytes);
            assertTrue(size >= bytes);
            assertTrue(bytes <= RtScratchPool.MIN_CLASS_BYTES || size <= bytes + bytes / 4 + 1, "class of " + bytes);
        }
    }

    @Test
    void returnedScratchIsLentAgainOnceTheGpuHasPassedIt() {
        RtBuffer first = pool.acquire(100 * KIB, "a");
        first.destroy();
        RtBuffer second = pool.acquire(100 * KIB, "b");
        assertEquals(1, allocator.created.size());
        assertSame(first.pooledBuffer(), second.pooledBuffer());
        assertEquals(first.deviceAddress, second.deviceAddress);
        assertNotSame(first, second);
    }

    @Test
    void notLentWhileAFrameHandedOutBeforeTheReturnIsUnfinished() {
        progress.reservedGraphics = 5L;
        progress.completedGraphics = 4L;
        RtBuffer first = pool.acquire(100 * KIB, "a");
        first.destroy();
        RtBuffer second = pool.acquire(100 * KIB, "b");
        assertEquals(2, allocator.created.size());
        second.destroy();

        progress.completedGraphics = 5L;
        RtBuffer third = pool.acquire(100 * KIB, "c");
        assertEquals(2, allocator.created.size());
        assertSame(first.pooledBuffer(), third.pooledBuffer());
    }

    @Test
    void notLentWhileABuildQueuedBeforeTheReturnIsUnfinished() {
        progress.assignedBuild = 3L;
        progress.finishedBuild = 2L;
        pool.acquire(100 * KIB, "a").destroy();
        pool.acquire(100 * KIB, "b");
        assertEquals(2, allocator.created.size());

        progress.finishedBuild = 3L;
        pool.acquire(100 * KIB, "c");
        assertEquals(2, allocator.created.size());
    }

    @Test
    void aSecondDestroyOfTheSameLeaseDoesNotReturnTheNextLeasesBuffer() {
        RtBuffer first = pool.acquire(100 * KIB, "a");
        first.destroy();
        RtBuffer second = pool.acquire(100 * KIB, "b");
        assertEquals(0, pool.idleCount());
        first.destroy();
        assertEquals(0, pool.idleCount());
        second.destroy();
        assertEquals(1, pool.idleCount());
    }

    @Test
    void classesAreNotShared() {
        pool.acquire(16 * KIB, "small").destroy();
        pool.acquire(1 * MIB, "large");
        assertEquals(2, allocator.created.size());
    }

    @Test
    void anUnreadableTimelineMeansANewBuffer() {
        pool.acquire(100 * KIB, "a").destroy();
        progress.lost = true;
        pool.acquire(100 * KIB, "b");
        assertEquals(2, allocator.created.size());
    }

    @Test
    void requestsTooLargeToPoolGetTheirOwnBuffer() {
        RtBuffer big = pool.acquire(RtScratchPool.MAX_POOLED_BYTES + 1, "huge");
        assertNull(big.pooledBuffer());
        assertEquals(RtScratchPool.MAX_POOLED_BYTES + 1, big.size);
    }

    @Test
    void idleBuffersBeyondTheCapOrPastTheirTimeAreFreed() {
        List<RtBuffer> leases = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            leases.add(pool.acquire(32 * MIB, "big " + i));
        }
        for (RtBuffer lease : leases) {
            lease.destroy();
        }
        assertEquals(96 * MIB, pool.idleBytes());
        List<RtBuffer> doomed;
        synchronized (pool) {
            doomed = pool.trimLocked(System.nanoTime());
        }
        assertEquals(1, doomed.size());
        assertEquals(RtScratchPool.IDLE_CAP_BYTES, pool.idleBytes());

        synchronized (pool) {
            doomed = pool.trimLocked(System.nanoTime() + RtScratchPool.IDLE_TTL_NANOS + 1L);
        }
        assertEquals(2, doomed.size());
        assertEquals(0L, pool.idleBytes());
    }

    @Test
    void unfinishedIdleBuffersAreKeptEvenOverTheCap() {
        progress.reservedGraphics = 9L;
        List<RtBuffer> leases = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            leases.add(pool.acquire(32 * MIB, "big " + i));
        }
        for (RtBuffer lease : leases) {
            lease.destroy();
        }
        List<RtBuffer> doomed;
        synchronized (pool) {
            doomed = pool.trimLocked(System.nanoTime() + RtScratchPool.IDLE_TTL_NANOS + 1L);
        }
        assertTrue(doomed.isEmpty());
    }

    @Test
    void closeFreesIdleBuffersAndLaterReturnsFreeAtOnce() {
        RtBuffer kept = pool.acquire(100 * KIB, "kept");
        pool.acquire(200 * KIB, "idle").destroy();
        pool.close();
        assertEquals(1, allocator.freed.size());
        kept.destroy();
        assertEquals(2, allocator.freed.size());
        assertSame(kept.pooledBuffer(), allocator.freed.get(1));
    }
}
