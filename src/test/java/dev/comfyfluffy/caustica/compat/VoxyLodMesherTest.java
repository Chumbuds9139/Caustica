package dev.comfyfluffy.caustica.compat;

import org.junit.jupiter.api.Test;

import static dev.comfyfluffy.caustica.compat.VoxyLodMesher.SIZE;
import static dev.comfyfluffy.caustica.compat.VoxyLodMesher.VOLUME;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the CPU surface extraction that lets Voxy's LOD data be ray traced without Voxy's OpenGL renderer.
 * The byte layout asserted here is the contract with {@code RtDistantHorizonsTerrain}'s quad decoder
 * (u16 x/y/z per 16-byte vertex, colour at +8, material at +12, 64 bytes per quad).
 */
final class VoxyLodMesherTest {
    private static final int STONE = 1;
    private static final int WATER = 2;
    private static final int GLASS = 3;
    private static final int GRASS = 4;
    private static final int PLANT = 5; // classified as air: non-solid decoration

    private static VoxyLodMesher.Palette palette() {
        byte[] kind = {VoxyLodMesher.KIND_AIR, VoxyLodMesher.KIND_OPAQUE, VoxyLodMesher.KIND_TRANSLUCENT,
                VoxyLodMesher.KIND_TRANSLUCENT, VoxyLodMesher.KIND_OPAQUE, VoxyLodMesher.KIND_AIR};
        byte[] material = {0, VoxyLodMesher.MATERIAL_STONE, VoxyLodMesher.MATERIAL_WATER,
                VoxyLodMesher.MATERIAL_UNKNOWN, VoxyLodMesher.MATERIAL_GRASS, 0};
        int[] rgb = {0, 0x808080, 0x3F76E4, 0xFFFFFF, 0x00FF00, 0};
        byte[] tint = {0, VoxyLodMesher.TINT_NONE, VoxyLodMesher.TINT_WATER, VoxyLodMesher.TINT_NONE,
                VoxyLodMesher.TINT_GRASS, 0};
        return new VoxyLodMesher.Palette(kind, material, rgb, tint,
                new int[]{0x112233, 0x445566}, new int[]{0x223344}, new int[]{0x0A0B0C, 0x0D0E0F},
                0x7F7F7F, VoxyLodMesher.MATERIAL_STONE);
    }

    private static long[] section() {
        return new long[VOLUME];
    }

    private static void set(long[] data, int x, int y, int z, int block, int biome) {
        data[VoxyLodMesher.sectionIndex(x, y, z)] = VoxyLodMesher.composeId(block, biome);
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int[] vertex(byte[] quads, int quad, int v) {
        int p = quad * 64 + v * 16;
        return new int[]{u16(quads, p), u16(quads, p + 2), u16(quads, p + 4)};
    }

    /** Outward normal as the proxy computes it: (p1 - p0) × (p2 - p0), reduced to signs. */
    private static int[] normal(byte[] quads, int quad) {
        int[] a = vertex(quads, quad, 0);
        int[] b = vertex(quads, quad, 1);
        int[] c = vertex(quads, quad, 2);
        long ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
        long vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
        return new int[]{Long.signum(uy * vz - uz * vy), Long.signum(uz * vx - ux * vz),
                Long.signum(ux * vy - uy * vx)};
    }

    @Test
    void mappingIdLayoutMatchesVoxy() {
        // Mapper.composeMappingId: light << 56 | biome << 47 | block << 27.
        long id = (0xABL << 56) | (300L << 47) | (123456L << 27);
        assertEquals(123456, VoxyLodMesher.blockId(id));
        assertEquals(300, VoxyLodMesher.biomeId(id));
        // WorldSection.getIndex: (y & 31) << 10 | (z & 31) << 5 | (x & 31).
        assertEquals((7 << 10) | (5 << 5) | 3, VoxyLodMesher.sectionIndex(3, 7, 5));
    }

    @Test
    void emptyOrDecorationOnlyColumnsProduceNothing() {
        long[] data = section();
        set(data, 4, 4, 4, PLANT, 0);
        assertTrue(VoxyLodMesher.mesh(new long[][]{null}, VoxyLodMesher.Borders.none(1), 0, palette()).isEmpty());
        assertTrue(VoxyLodMesher.mesh(new long[][]{data}, VoxyLodMesher.Borders.none(1), 0, palette()).isEmpty());
    }

    @Test
    void isolatedVoxelEmitsSixOutwardFacingUnitQuads() {
        long[] data = section();
        set(data, 10, 5, 20, STONE, 0);
        VoxyLodMesher.Result result = VoxyLodMesher.mesh(new long[][]{data}, VoxyLodMesher.Borders.none(1), 0, palette());
        assertEquals(0, result.transparent().length);
        assertEquals(6 * 64, result.opaque().length);
        int[][] seen = new int[6][];
        for (int q = 0; q < 6; q++) {
            int[] n = normal(result.opaque(), q);
            assertEquals(1, Math.abs(n[0]) + Math.abs(n[1]) + Math.abs(n[2]), "axis-aligned normal");
            // Every vertex lies on the voxel's surface, and the face plane is on the side the normal points to.
            for (int v = 0; v < 4; v++) {
                int[] p = vertex(result.opaque(), q, v);
                assertTrue(p[0] >= 10 && p[0] <= 11 && p[1] >= 5 && p[1] <= 6 && p[2] >= 20 && p[2] <= 21);
                if (n[0] != 0) assertEquals(n[0] > 0 ? 11 : 10, p[0]);
                if (n[1] != 0) assertEquals(n[1] > 0 ? 6 : 5, p[1]);
                if (n[2] != 0) assertEquals(n[2] > 0 ? 21 : 20, p[2]);
            }
            int slot = n[0] != 0 ? (n[0] > 0 ? 0 : 1) : n[1] != 0 ? (n[1] > 0 ? 2 : 3) : (n[2] > 0 ? 4 : 5);
            assertEquals(null, seen[slot], "each direction exactly once");
            seen[slot] = n;
            int p = q * 64;
            assertEquals(0x80, result.opaque()[p + 8] & 0xFF);
            assertEquals(0x80, result.opaque()[p + 9] & 0xFF);
            assertEquals(0x80, result.opaque()[p + 10] & 0xFF);
            assertEquals(255, result.opaque()[p + 11] & 0xFF);
            assertEquals(VoxyLodMesher.MATERIAL_STONE, result.opaque()[p + 12]);
        }
    }

    @Test
    void solidSectionMergesEachSideIntoOneQuadAndHasNoFloor() {
        long[] data = section();
        for (int i = 0; i < VOLUME; i++) data[i] = VoxyLodMesher.composeId(STONE, 0);
        VoxyLodMesher.Result open = VoxyLodMesher.mesh(new long[][]{data}, VoxyLodMesher.Borders.none(1), 0, palette());
        // Top + four walls; the underside of the stack is never emitted.
        assertEquals(5 * 64, open.opaque().length);
        for (int q = 0; q < 5; q++) {
            assertTrue(normal(open.opaque(), q)[1] >= 0, "no downward face at the bottom of the stack");
        }

        // With solid neighbours on every side only the top remains.
        long[] solidPlane = new long[SIZE * SIZE];
        java.util.Arrays.fill(solidPlane, VoxyLodMesher.composeId(STONE, 0));
        long[][] plane = {solidPlane};
        VoxyLodMesher.Result enclosed = VoxyLodMesher.mesh(new long[][]{data},
                new VoxyLodMesher.Borders(plane, plane, plane, plane), 0, palette());
        assertEquals(64, enclosed.opaque().length);
        assertArrayEquals(new int[]{0, 1, 0}, normal(enclosed.opaque(), 0));
        int minX = 99, maxX = -1, minZ = 99, maxZ = -1;
        for (int v = 0; v < 4; v++) {
            int[] p = vertex(enclosed.opaque(), 0, v);
            assertEquals(32, p[1]);
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minZ = Math.min(minZ, p[2]);
            maxZ = Math.max(maxZ, p[2]);
        }
        assertEquals(0, minX);
        assertEquals(32, maxX);
        assertEquals(0, minZ);
        assertEquals(32, maxZ);
    }

    @Test
    void stackedSectionsShareOneVolumeAndScaleWithLevel() {
        long[] bottom = section();
        long[] top = section();
        for (int i = 0; i < VOLUME; i++) {
            bottom[i] = VoxyLodMesher.composeId(STONE, 0);
            top[i] = VoxyLodMesher.composeId(STONE, 0);
        }
        long[] solidPlane = new long[SIZE * SIZE];
        java.util.Arrays.fill(solidPlane, VoxyLodMesher.composeId(STONE, 0));
        long[][] plane = {solidPlane, solidPlane};
        VoxyLodMesher.Result result = VoxyLodMesher.mesh(new long[][]{bottom, top},
                new VoxyLodMesher.Borders(plane, plane, plane, plane), 3, palette());
        // No face between the two stacked sections: only the top of the upper one.
        assertEquals(64, result.opaque().length);
        for (int v = 0; v < 4; v++) {
            int[] p = vertex(result.opaque(), 0, v);
            assertEquals(64 << 3, p[1], "two sections of 32 voxels, 8 blocks per voxel");
            assertTrue(p[0] == 0 || p[0] == 32 << 3);
            assertTrue(p[2] == 0 || p[2] == 32 << 3);
        }
    }

    @Test
    void waterSurfaceGoesToTheTransparentPassAndTheBedStaysVisible() {
        long[] data = section();
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                set(data, x, 0, z, STONE, 0);
                set(data, x, 1, z, WATER, 1);
            }
        }
        long[] solid = new long[SIZE * SIZE];
        java.util.Arrays.fill(solid, VoxyLodMesher.composeId(STONE, 0));
        long[][] plane = {solid};
        VoxyLodMesher.Result result = VoxyLodMesher.mesh(new long[][]{data},
                new VoxyLodMesher.Borders(plane, plane, plane, plane), 0, palette());
        // Water: a single merged top face; nothing against the stone walls or the bed.
        assertEquals(64, result.transparent().length);
        assertArrayEquals(new int[]{0, 1, 0}, normal(result.transparent(), 0));
        assertEquals(VoxyLodMesher.MATERIAL_WATER, result.transparent()[12]);
        // Biome 1's water tint replaces the block colour.
        assertEquals(0x0D, result.transparent()[8] & 0xFF);
        assertEquals(0x0E, result.transparent()[9] & 0xFF);
        assertEquals(0x0F, result.transparent()[10] & 0xFF);
        // Distant water is shaded as an opaque reflective surface, so the bed under it is never
        // visible and must not cost any geometry.
        assertEquals(0, result.opaque().length);
    }

    private static long[] solidBelow(int surface) {
        long[] data = section();
        for (int y = 0; y < surface; y++) {
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) set(data, x, y, z, STONE, 0);
            }
        }
        return data;
    }

    private static VoxyLodMesher.Borders solidBorders(int surface) {
        long[] plane = new long[SIZE * SIZE];
        for (int y = 0; y < surface; y++) {
            for (int w = 0; w < SIZE; w++) plane[y * SIZE + w] = VoxyLodMesher.composeId(STONE, 0);
        }
        long[][] p = {plane};
        return new VoxyLodMesher.Borders(p, p, p, p);
    }

    @Test
    void sealedCavesCostNoGeometry() {
        long[] data = solidBelow(24);
        // A closed cavern well below the surface.
        for (int y = 4; y < 10; y++) {
            for (int z = 8; z < 20; z++) {
                for (int x = 8; x < 20; x++) set(data, x, y, z, 0, 0);
            }
        }
        VoxyLodMesher.Result open = VoxyLodMesher.mesh(new long[][]{data}, solidBorders(24), 0, palette(), false);
        VoxyLodMesher.Result sealed = VoxyLodMesher.mesh(new long[][]{data}, solidBorders(24), 0, palette(), true);
        assertTrue(open.opaque().length > 64, "without sealing the cavern walls are meshed");
        assertEquals(64, sealed.opaque().length, "with sealing only the flat surface remains");
        assertArrayEquals(new int[]{0, 1, 0}, normal(sealed.opaque(), 0));
        assertEquals(24, vertex(sealed.opaque(), 0, 0)[1]);
    }

    @Test
    void cavesOpenToTheSkyAreKeptWhileSealedOnesBesideThemAreFilled() {
        long[] data = solidBelow(24);
        // A 2x2 shaft from the surface down to y=2: open to the sky, so it is real visible geometry.
        for (int y = 2; y < 24; y++) {
            for (int z = 10; z < 12; z++) {
                for (int x = 10; x < 12; x++) set(data, x, y, z, 0, 0);
            }
        }
        // A closed pocket elsewhere, not connected to anything.
        for (int y = 6; y < 9; y++) {
            for (int z = 20; z < 24; z++) {
                for (int x = 20; x < 24; x++) set(data, x, y, z, 0, 0);
            }
        }
        VoxyLodMesher.Result sealed = VoxyLodMesher.mesh(new long[][]{data}, solidBorders(24), 0, palette(), true);
        int lowest = Integer.MAX_VALUE;
        for (int q = 0; q < sealed.opaque().length / 64; q++) {
            for (int v = 0; v < 4; v++) {
                int[] p = vertex(sealed.opaque(), q, v);
                lowest = Math.min(lowest, p[1]);
                boolean inPocket = p[0] >= 20 && p[0] <= 24 && p[2] >= 20 && p[2] <= 24 && p[1] < 24;
                assertTrue(!inPocket, "the sealed pocket must not produce any face");
            }
        }
        assertEquals(2, lowest, "the open shaft keeps its walls and floor");
    }

    @Test
    void airUnderAnOverhangStaysOpen() {
        long[] data = solidBelow(4);
        // A slab floating over half of the cell, 10 voxels above the ground.
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < 16; x++) set(data, x, 14, z, STONE, 0);
        }
        VoxyLodMesher.Result open = VoxyLodMesher.mesh(new long[][]{data}, solidBorders(4), 0, palette(), false);
        VoxyLodMesher.Result sealed = VoxyLodMesher.mesh(new long[][]{data}, solidBorders(4), 0, palette(), true);
        assertArrayEquals(open.opaque(), sealed.opaque(),
                "air connected sideways to the open sky is not a cave: the slab must not grow a pillar");
    }

    @Test
    void windowsDoNotSealARoom() {
        long[] data = solidBelow(4);
        // A hollow stone box with one glass block in its wall.
        for (int y = 4; y < 10; y++) {
            for (int z = 8; z < 16; z++) {
                for (int x = 8; x < 16; x++) {
                    boolean shell = y == 9 || x == 8 || x == 15 || z == 8 || z == 15;
                    if (shell) set(data, x, y, z, STONE, 0);
                }
            }
        }
        set(data, 8, 6, 11, GLASS, 0);
        VoxyLodMesher.Result open = VoxyLodMesher.mesh(new long[][]{data}, solidBorders(4), 0, palette(), false);
        VoxyLodMesher.Result sealed = VoxyLodMesher.mesh(new long[][]{data}, solidBorders(4), 0, palette(), true);
        assertArrayEquals(open.opaque(), sealed.opaque(), "the interior is reachable through the window");
        assertArrayEquals(open.transparent(), sealed.transparent());
    }

    @Test
    void differentTranslucentMediaKeepTheirInterfaceButLikeMediaDoNot() {
        long[] data = section();
        set(data, 5, 5, 5, WATER, 0);
        set(data, 6, 5, 5, WATER, 0);
        set(data, 7, 5, 5, GLASS, 0);
        VoxyLodMesher.Result result = VoxyLodMesher.mesh(new long[][]{data}, VoxyLodMesher.Borders.none(1), 0, palette());
        // Water pair merges to a 2×1×1 box (6 quads); glass cube has 6; the water|glass contact adds one
        // face from each side, and the water|water contact adds none: 6 + 6 = 12 quads in total because
        // the shared +x face of the water box and the -x face of the glass are among those twelve.
        assertEquals(12 * 64, result.transparent().length);
        assertEquals(0, result.opaque().length);
    }

    @Test
    void biomeTintAndUnknownBlocksAreResolved() {
        long[] data = section();
        set(data, 1, 3, 1, GRASS, 1);
        set(data, 20, 3, 20, 4000, 0); // registered after the palette snapshot
        VoxyLodMesher.Result result = VoxyLodMesher.mesh(new long[][]{data}, VoxyLodMesher.Borders.none(1), 0, palette());
        assertEquals(12 * 64, result.opaque().length);
        boolean sawGrass = false;
        boolean sawFallback = false;
        for (int q = 0; q < 12; q++) {
            int p = q * 64;
            int rgb = ((result.opaque()[p + 8] & 0xFF) << 16) | ((result.opaque()[p + 9] & 0xFF) << 8)
                    | (result.opaque()[p + 10] & 0xFF);
            if (rgb == 0x445566) {
                sawGrass = true;
                assertEquals(VoxyLodMesher.MATERIAL_GRASS, result.opaque()[p + 12]);
            } else {
                assertEquals(0x7F7F7F, rgb);
                sawFallback = true;
            }
        }
        assertTrue(sawGrass && sawFallback);
    }

    @Test
    void coordinatesMustFitTheSixteenBitVertexFormat() {
        // 4 sections × 32 voxels × 2^10 blocks would overflow u16.
        long[][] sections = new long[4][];
        assertThrows(IllegalArgumentException.class,
                () -> VoxyLodMesher.mesh(sections, VoxyLodMesher.Borders.none(4), 10, palette()));
    }
}
