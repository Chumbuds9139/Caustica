package dev.comfyfluffy.caustica.compat;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Voxy LOD cell selection must tile the ground exactly once: a gap is a hole to the void in the
 * ray-traced horizon, an overlap is two differently simplified copies of the same terrain.
 */
final class VoxyCellSelectionTest {
    @Test
    void keysRoundTripIncludingNegativeCoordinates() {
        int[][] samples = {{0, 0, 0}, {4, -1, -1}, {2, 123456, -654321}, {1, -8388608, 8388607}, {3, -7, 9}};
        for (int[] sample : samples) {
            long key = VoxyNativeBridge.cellKey(sample[0], sample[1], sample[2]);
            assertEquals(sample[0], VoxyNativeBridge.cellLevel(key));
            assertEquals(sample[1], VoxyNativeBridge.cellX(key));
            assertEquals(sample[2], VoxyNativeBridge.cellZ(key));
        }
    }

    @Test
    void selectionTilesTheDiscWithoutGapsOrOverlaps() {
        int[][] cameras = {{0, 0}, {-1000, 37}, {123457, -98765}, {-511, -513}};
        for (int[] camera : cameras) {
            int radius = 1536;
            List<Long> cells = VoxyNativeBridge.selectCells(camera[0], camera[1], radius, 0);
            assertFalse(cells.isEmpty());
            assertEquals(cells.size(), new HashSet<>(cells).size(), "no cell selected twice");
            // Sample the ground on a coarse grid of block positions and count the covering cells.
            for (int dx = -radius; dx <= radius; dx += 61) {
                for (int dz = -radius; dz <= radius; dz += 59) {
                    if ((long) dx * dx + (long) dz * dz > (long) (radius - 1) * (radius - 1)) continue;
                    int x = camera[0] + dx;
                    int z = camera[1] + dz;
                    int covering = 0;
                    for (long key : cells) {
                        int size = VoxyLodMesher.SIZE << VoxyNativeBridge.cellLevel(key);
                        long x0 = (long) VoxyNativeBridge.cellX(key) * size;
                        long z0 = (long) VoxyNativeBridge.cellZ(key) * size;
                        if (x >= x0 && x < x0 + size && z >= z0 && z < z0 + size) covering++;
                    }
                    assertEquals(1, covering, "block " + x + "," + z + " around camera "
                            + camera[0] + "," + camera[1]);
                }
            }
        }
    }

    @Test
    void detailFallsOffWithDistanceAndNearCellsComeFirst() {
        List<Long> cells = VoxyNativeBridge.selectCells(0, 0, 4096, 0);
        Set<Integer> levels = new HashSet<>();
        for (long key : cells) {
            levels.add(VoxyNativeBridge.cellLevel(key));
        }
        assertEquals(Set.of(0, 1, 2, 3, 4), levels, "every Voxy level is used across 4096 blocks");
        // The cell under the camera is the finest level and is scheduled first.
        assertEquals(0, VoxyNativeBridge.cellLevel(cells.get(0)));
        long previous = -1;
        for (long key : cells) {
            int level = VoxyNativeBridge.cellLevel(key);
            long size = (long) VoxyLodMesher.SIZE << level;
            long x0 = VoxyNativeBridge.cellX(key) * size;
            long z0 = VoxyNativeBridge.cellZ(key) * size;
            long dx = Math.max(Math.max(x0, -(x0 + size)), 0L);
            long dz = Math.max(Math.max(z0, -(z0 + size)), 0L);
            long nearestSq = dx * dx + dz * dz;
            assertTrue(nearestSq >= previous, "cells are ordered nearest first");
            previous = nearestSq;
            // A cell kept at level L is at least four cell widths away (level 0 is never refined).
            if (level > 0) assertTrue(nearestSq >= 16 * size * size);
        }
    }

    @Test
    void cellsDeepInsideTheLoadedChunksAreLeftToRealTerrain() {
        List<Long> all = VoxyNativeBridge.selectCells(8, 8, 1024, 0);
        List<Long> trimmed = VoxyNativeBridge.selectCells(8, 8, 1024, 32 * 16);
        assertTrue(trimmed.size() < all.size());
        for (long key : trimmed) {
            long size = (long) VoxyLodMesher.SIZE << VoxyNativeBridge.cellLevel(key);
            long x0 = VoxyNativeBridge.cellX(key) * size;
            long z0 = VoxyNativeBridge.cellZ(key) * size;
            long far = Math.max(Math.max(Math.abs(x0 - 8), Math.abs(x0 + size - 8)),
                    Math.max(Math.abs(z0 - 8), Math.abs(z0 + size - 8)));
            assertTrue(far >= 256, "only cells wholly within half the vanilla radius are skipped");
        }
    }
}
