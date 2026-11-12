package dev.comfyfluffy.caustica.compat;

import java.util.Arrays;

/**
 * Turns a vertical stack of Voxy world sections into the 64-byte LOD quads the ray-tracing LOD proxy
 * already consumes for Distant Horizons ({@link DistantHorizonsCompat.LodMesh}).
 *
 * <p>Voxy stores every level of detail as 32×32×32 sections of packed ids; one voxel of level {@code L}
 * covers {@code 2^L} blocks. Its own renderer draws those sections with OpenGL, which is unavailable on
 * the Vulkan backend Caustica needs, so the surface is extracted here on the CPU instead: visible faces
 * are found per axis and merged greedily into rectangles of identical appearance.
 *
 * <p>This class is deliberately free of Minecraft and Voxy types. Everything it needs to know about a
 * block arrives through {@link Palette}, which makes the whole algorithm unit-testable.
 */
public final class VoxyLodMesher {
    /** Voxels along one section edge. */
    public static final int SIZE = 32;
    private static final int AREA = SIZE * SIZE;
    /** Longs in one section, indexed {@code (y << 10) | (z << 5) | x} as Voxy's WorldSection does. */
    public static final int VOLUME = SIZE * SIZE * SIZE;

    public static final byte KIND_AIR = 0;
    public static final byte KIND_OPAQUE = 1;
    public static final byte KIND_TRANSLUCENT = 2;

    public static final byte TINT_NONE = 0;
    public static final byte TINT_GRASS = 1;
    public static final byte TINT_FOLIAGE = 2;
    public static final byte TINT_WATER = 3;

    /** Bytes per emitted quad: four 16-byte vertices. */
    public static final int QUAD_BYTES = 64;

    private VoxyLodMesher() {
    }

    /**
     * Immutable per-world appearance tables. Block arrays are indexed by Voxy block id, biome arrays by
     * Voxy biome id; ids beyond a table (registered after the snapshot was taken) fall back to
     * {@link #fallbackRgb} opaque stone-like matter so new blocks are never rendered as holes.
     */
    public record Palette(byte[] kind, byte[] material, int[] rgb, byte[] tint,
                          int[] grassRgb, int[] foliageRgb, int[] waterRgb,
                          int fallbackRgb, byte fallbackMaterial) {
        public int blockCount() {
            return kind.length;
        }
    }

    /** Quads of the two passes, each a multiple of {@link #QUAD_BYTES} long. */
    public record Result(byte[] opaque, byte[] transparent) {
        public boolean isEmpty() {
            return opaque.length == 0 && transparent.length == 0;
        }
    }

    /**
     * The voxel planes just outside the four vertical sides of the column, one entry per stacked section
     * (null = nothing stored there, treated as air). {@code west}/{@code east} planes are indexed
     * {@code y * 32 + z}, {@code north}/{@code south} planes {@code y * 32 + x}; west is the x-1
     * neighbour's x=31 plane, east the x+1 neighbour's x=0 plane, north z-1 / z=31, south z+1 / z=0.
     */
    public record Borders(long[][] west, long[][] east, long[][] north, long[][] south) {
        public static Borders none(int sections) {
            return new Borders(new long[sections][], new long[sections][], new long[sections][],
                    new long[sections][]);
        }
    }

    /** Voxy mapping-id layout: block id in bits 27..46, biome id in bits 47..55. */
    public static int blockId(long id) {
        return (int) ((id >>> 27) & 0xFFFFFL);
    }

    public static int biomeId(long id) {
        return (int) ((id >>> 47) & 0x1FFL);
    }

    public static long composeId(int blockId, int biomeId) {
        return ((long) (biomeId & 0x1FF) << 47) | ((long) (blockId & 0xFFFFF) << 27);
    }

    public static int sectionIndex(int x, int y, int z) {
        return (y << 10) | (z << 5) | x;
    }

    /**
     * Appearance key of one voxel: 0 for air, otherwise {@code kind << 28 | material << 24 | rgb}. Two
     * adjacent faces merge only when their keys are equal, so a key fully determines an emitted quad.
     */
    static int cellKey(long id, Palette palette) {
        int block = blockId(id);
        if (block == 0) return 0;
        if (block >= palette.kind.length) {
            return (KIND_OPAQUE << 28) | ((palette.fallbackMaterial & 0xF) << 24) | (palette.fallbackRgb & 0xFFFFFF);
        }
        int kind = palette.kind[block];
        if (kind == KIND_AIR) return 0;
        int rgb = palette.rgb[block];
        int biome = biomeId(id);
        switch (palette.tint[block]) {
            case TINT_GRASS -> rgb = biome < palette.grassRgb.length ? palette.grassRgb[biome] : rgb;
            case TINT_FOLIAGE -> rgb = biome < palette.foliageRgb.length ? palette.foliageRgb[biome] : rgb;
            case TINT_WATER -> rgb = biome < palette.waterRgb.length ? palette.waterRgb[biome] : rgb;
            default -> { }
        }
        return (kind << 28) | ((palette.material[block] & 0xF) << 24) | (rgb & 0xFFFFFF);
    }

    /**
     * Whether the face of {@code self} towards {@code other} can be seen. Opaque matter shows wherever
     * the neighbour is not opaque; translucent matter shows against air and against a different
     * translucent medium (water behind glass), but never against itself or against opaque matter.
     */
    static boolean faceVisible(int self, int other) {
        if (self == 0) return false;
        int selfKind = self >>> 28;
        int otherKind = other >>> 28;
        if (selfKind == KIND_OPAQUE) {
            // Distant water is shaded as a reflective surface that nothing is seen through, so the bed
            // and banks beneath it can never be visible: leave them out of the BVH altogether.
            if (otherKind == KIND_TRANSLUCENT && ((other >>> 24) & 0xF) == MATERIAL_WATER) return false;
            return otherKind != KIND_OPAQUE;
        }
        if (otherKind == KIND_AIR) return true;
        return otherKind == KIND_TRANSLUCENT && ((self ^ other) & 0x0F000000) != 0;
    }

    /**
     * Mesh one column.
     *
     * @param sections bottom-to-top stack of section data (each {@link #VOLUME} longs, or null for air)
     * @param borders  neighbouring voxel planes used to cull the column's outer walls
     * @param level    Voxy level of detail; quad coordinates are emitted in blocks ({@code voxel << level})
     * @param palette  appearance tables
     */
    public static Result mesh(long[][] sections, Borders borders, int level, Palette palette) {
        return mesh(sections, borders, level, palette, false);
    }

    /** How far below its own column's surface side air is still trusted to be open, in voxels. */
    static final int CAVE_DEPTH = 8;

    /**
     * Mesh one column.
     *
     * @param sealCaves fill air that cannot be reached from the open sky (see {@link #sealEnclosedAir});
     *                  only meaningful where the top of the stack is open sky, i.e. not under a ceiling
     */
    public static Result mesh(long[][] sections, Borders borders, int level, Palette palette, boolean sealCaves) {
        int stack = sections.length;
        int height = stack * SIZE;
        if (stack == 0) return new Result(new byte[0], new byte[0]);
        long maxCoordinate = (long) Math.max(height, SIZE) << level;
        if (level < 0 || maxCoordinate > 0xFFFF) {
            throw new IllegalArgumentException("Voxy LOD column does not fit 16-bit quad coordinates: level "
                    + level + ", " + stack + " sections");
        }

        // Dense appearance volume, indexed (y * 32 + z) * 32 + x.
        int[] cells = new int[height * AREA];
        boolean any = false;
        for (int s = 0; s < stack; s++) {
            long[] data = sections[s];
            if (data == null) continue;
            if (data.length != VOLUME) {
                throw new IllegalArgumentException("Voxy section must hold " + VOLUME + " voxels");
            }
            int base = s * VOLUME;
            for (int i = 0; i < VOLUME; i++) {
                long id = data[i];
                if (id == 0L) continue;
                int key = cellKey(id, palette);
                if (key != 0) {
                    cells[base + i] = key;
                    any = true;
                }
            }
        }
        if (!any) return new Result(new byte[0], new byte[0]);
        if (sealCaves) {
            int fill = (KIND_OPAQUE << 28) | ((palette.fallbackMaterial & 0xF) << 24) | (palette.fallbackRgb & 0xFFFFFF);
            sealEnclosedAir(cells, height, borders, palette, fill);
        }

        QuadSink opaque = new QuadSink();
        QuadSink transparent = new QuadSink();
        int[] mask = new int[Math.max(AREA, SIZE * height)];
        int scale = 1 << level;

        // ---- +Y / -Y: slices along y, mask over (z, x) -------------------------------------------------
        for (int y = 0; y < height; y++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                int ny = y + sign;
                boolean maskAny = false;
                for (int x = 0; x < SIZE; x++) {
                    for (int z = 0; z < SIZE; z++) {
                        int self = cells[(y * SIZE + z) * SIZE + x];
                        int key = 0;
                        // Below the stack lies unloaded/out-of-world space: never emit a floor there.
                        // Above the stack is open air.
                        if (self != 0 && ny >= 0) {
                            int other = ny >= height ? 0 : cells[(ny * SIZE + z) * SIZE + x];
                            if (faceVisible(self, other)) key = self;
                        }
                        // u = z, v = x  (z × x = +y)
                        mask[x * SIZE + z] = key;
                        maskAny |= key != 0;
                    }
                }
                if (maskAny) {
                    emitMask(mask, SIZE, SIZE, 1, sign, y + (sign > 0 ? 1 : 0), scale, opaque, transparent);
                }
            }
        }

        // ---- +X / -X: slices along x, mask over (y, z) -------------------------------------------------
        for (int x = 0; x < SIZE; x++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                int nx = x + sign;
                long[][] plane = nx < 0 ? borders.west() : nx >= SIZE ? borders.east() : null;
                boolean maskAny = false;
                for (int z = 0; z < SIZE; z++) {
                    for (int y = 0; y < height; y++) {
                        int self = cells[(y * SIZE + z) * SIZE + x];
                        int key = 0;
                        if (self != 0) {
                            int other;
                            if (plane != null) {
                                other = borderKey(plane, y, z, palette);
                            } else if (nx < 0 || nx >= SIZE) {
                                other = 0;
                            } else {
                                other = cells[(y * SIZE + z) * SIZE + nx];
                            }
                            if (faceVisible(self, other)) key = self;
                        }
                        // u = y, v = z  (y × z = +x)
                        mask[z * height + y] = key;
                        maskAny |= key != 0;
                    }
                }
                if (maskAny) {
                    emitMask(mask, height, SIZE, 0, sign, x + (sign > 0 ? 1 : 0), scale, opaque, transparent);
                }
            }
        }

        // ---- +Z / -Z: slices along z, mask over (x, y) -------------------------------------------------
        for (int z = 0; z < SIZE; z++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                int nz = z + sign;
                long[][] plane = nz < 0 ? borders.north() : nz >= SIZE ? borders.south() : null;
                boolean maskAny = false;
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < SIZE; x++) {
                        int self = cells[(y * SIZE + z) * SIZE + x];
                        int key = 0;
                        if (self != 0) {
                            int other;
                            if (plane != null) {
                                other = borderKey(plane, y, x, palette);
                            } else if (nz < 0 || nz >= SIZE) {
                                other = 0;
                            } else {
                                other = cells[(y * SIZE + nz) * SIZE + x];
                            }
                            if (faceVisible(self, other)) key = self;
                        }
                        // u = x, v = y  (x × y = +z)
                        mask[y * SIZE + x] = key;
                        maskAny |= key != 0;
                    }
                }
                if (maskAny) {
                    emitMask(mask, SIZE, height, 2, sign, z + (sign > 0 ? 1 : 0), scale, opaque, transparent);
                }
            }
        }
        return new Result(opaque.toArray(), transparent.toArray());
    }

    /**
     * Turn air that cannot be reached from the open sky into solid rock.
     *
     * <p>A distant-terrain proxy only ever needs the outside of the world. Without this, every cave
     * system inside the selected radius is meshed in full: in ordinary terrain that is several times
     * the area of the visible surface, all of it BVH memory, build time and traversal cost for geometry
     * no ray from the outside can usefully see.
     *
     * <p>Reachability is a flood fill through air and translucent matter (so windows do not seal a
     * room, and water does not seal what is above it), seeded from the top of the stack and from side
     * air that is near its own column's surface (within {@link #CAVE_DEPTH} voxels). Anything genuinely
     * open to the sky inside the cell, such as a ravine or a shaft, is reached and kept; pockets with no
     * opening, and tunnels that only continue into a neighbouring cell deep underground, are not.
     * Everything not reached is filled; the result stays watertight because a filled voxel that borders
     * reachable air simply shows a rock face there.
     *
     * <p>Air under an overhang or a floating island stays connected sideways to open air and is kept,
     * as long as the open side lies within the same cell.
     */
    static void sealEnclosedAir(int[] cells, int height, Borders borders, Palette palette, int fill) {
        int[] top = new int[AREA];
        int lowestSurface = Integer.MAX_VALUE;
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                int y = height - 1;
                while (y >= 0 && cells[(y * SIZE + z) * SIZE + x] == 0) y--;
                top[z * SIZE + x] = y; // -1: nothing in this column
                lowestSurface = Math.min(lowestSurface, y);
            }
        }
        int floor = Math.max(0, lowestSurface - CAVE_DEPTH);

        boolean[] reached = new boolean[cells.length];
        int[] queue = new int[cells.length];
        int head = 0;
        int tail = 0;
        // Open sky: the whole top layer of the stack.
        for (int i = (height - 1) * AREA; i < height * AREA; i++) {
            if (passable(cells[i])) {
                reached[i] = true;
                queue[tail++] = i;
            }
        }
        // Side air continues into the neighbouring cell. Only air near the surface of its own column is
        // trusted to be open; a deep tunnel crossing the border is sealed here and shows a rock face
        // towards the neighbour instead.
        for (int w = 0; w < SIZE; w++) {
            tail = seedBorderColumn(cells, reached, queue, tail, height, floor, top, 0, w, borders.west(), w, palette);
            tail = seedBorderColumn(cells, reached, queue, tail, height, floor, top, SIZE - 1, w, borders.east(), w, palette);
            tail = seedBorderColumn(cells, reached, queue, tail, height, floor, top, w, 0, borders.north(), w, palette);
            tail = seedBorderColumn(cells, reached, queue, tail, height, floor, top, w, SIZE - 1, borders.south(), w, palette);
        }
        while (head < tail) {
            int index = queue[head++];
            int x = index & 31;
            int z = (index >> 5) & 31;
            int y = index >> 10;
            if (x > 0) tail = visit(cells, reached, queue, tail, index - 1);
            if (x < SIZE - 1) tail = visit(cells, reached, queue, tail, index + 1);
            if (z > 0) tail = visit(cells, reached, queue, tail, index - SIZE);
            if (z < SIZE - 1) tail = visit(cells, reached, queue, tail, index + SIZE);
            if (y < height - 1) tail = visit(cells, reached, queue, tail, index + AREA);
            if (y > floor) tail = visit(cells, reached, queue, tail, index - AREA);
        }
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] == 0 && !reached[i]) cells[i] = fill;
        }
    }

    private static boolean passable(int key) {
        return (key >>> 28) != KIND_OPAQUE;
    }

    private static int visit(int[] cells, boolean[] reached, int[] queue, int tail, int index) {
        if (!reached[index] && passable(cells[index])) {
            reached[index] = true;
            queue[tail++] = index;
        }
        return tail;
    }

    /** Seed the passable voxels of one side column whose outside neighbour is passable too. */
    private static int seedBorderColumn(int[] cells, boolean[] reached, int[] queue, int tail, int height,
                                        int floor, int[] top, int x, int z, long[][] plane, int planeW,
                                        Palette palette) {
        int from = Math.max(floor, top[z * SIZE + x] - CAVE_DEPTH);
        for (int y = Math.max(0, from); y < height; y++) {
            int index = (y * SIZE + z) * SIZE + x;
            if (reached[index] || !passable(cells[index])) continue;
            if (!passable(borderKey(plane, y, planeW, palette))) continue;
            reached[index] = true;
            queue[tail++] = index;
        }
        return tail;
    }

    /** Appearance of the voxel at stacked height {@code y}, in-plane coordinate {@code w}, of a border. */
    private static int borderKey(long[][] plane, int y, int w, Palette palette) {
        int section = y >> 5;
        if (plane == null || section >= plane.length) return 0;
        long[] data = plane[section];
        if (data == null) return 0;
        long id = data[(y & 31) * SIZE + w];
        return id == 0L ? 0 : cellKey(id, palette);
    }

    /**
     * Greedily merge a {@code uSize × vSize} mask (indexed {@code v * uSize + u}) into rectangles and emit
     * them on the plane {@code axis = plane}. The in-plane axes are chosen so that u × v is the positive
     * normal axis: X → (y, z), Y → (z, x), Z → (x, y). The mask is consumed.
     */
    private static void emitMask(int[] mask, int uSize, int vSize, int axis, int sign, int plane, int scale,
                                 QuadSink opaque, QuadSink transparent) {
        for (int v = 0; v < vSize; v++) {
            int row = v * uSize;
            for (int u = 0; u < uSize; ) {
                int key = mask[row + u];
                if (key == 0) {
                    u++;
                    continue;
                }
                int width = 1;
                while (u + width < uSize && mask[row + u + width] == key) width++;
                int heightRun = 1;
                grow:
                while (v + heightRun < vSize) {
                    int next = (v + heightRun) * uSize + u;
                    for (int i = 0; i < width; i++) {
                        if (mask[next + i] != key) break grow;
                    }
                    heightRun++;
                }
                for (int dv = 0; dv < heightRun; dv++) {
                    int clear = (v + dv) * uSize + u;
                    Arrays.fill(mask, clear, clear + width, 0);
                }
                QuadSink sink = (key >>> 28) == KIND_TRANSLUCENT ? transparent : opaque;
                sink.add(axis, sign, plane * scale, u * scale, (u + width) * scale, v * scale,
                        (v + heightRun) * scale, key);
                u += width;
            }
        }
    }

    /** Growable little-endian quad buffer. */
    private static final class QuadSink {
        private byte[] bytes = new byte[QUAD_BYTES * 64];
        private int size;

        void add(int axis, int sign, int plane, int u0, int u1, int v0, int v1, int key) {
            if (size + QUAD_BYTES > bytes.length) {
                bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, size + QUAD_BYTES));
            }
            // Counter-clockwise seen from outside, so (p1 - p0) × (p2 - p0) is the outward normal.
            if (sign > 0) {
                vertex(0, axis, plane, u0, v0, key);
                vertex(1, axis, plane, u1, v0, key);
                vertex(2, axis, plane, u1, v1, key);
                vertex(3, axis, plane, u0, v1, key);
            } else {
                vertex(0, axis, plane, u0, v0, key);
                vertex(1, axis, plane, u0, v1, key);
                vertex(2, axis, plane, u1, v1, key);
                vertex(3, axis, plane, u1, v0, key);
            }
            size += QUAD_BYTES;
        }

        private void vertex(int index, int axis, int plane, int u, int v, int key) {
            int x;
            int y;
            int z;
            switch (axis) {
                case 0 -> { x = plane; y = u; z = v; }
                case 1 -> { y = plane; z = u; x = v; }
                default -> { z = plane; x = u; y = v; }
            }
            int p = size + index * 16;
            putU16(p, x);
            putU16(p + 2, y);
            putU16(p + 4, z);
            bytes[p + 6] = 0;
            bytes[p + 7] = 0;
            bytes[p + 8] = (byte) (key >>> 16);
            bytes[p + 9] = (byte) (key >>> 8);
            bytes[p + 10] = (byte) key;
            int material = (key >>> 24) & 0xF;
            bytes[p + 11] = materialAlpha(key);
            bytes[p + 12] = (byte) material;
            bytes[p + 13] = materialLight(material);
            bytes[p + 14] = 0;
            bytes[p + 15] = 0;
        }

        private void putU16(int offset, int value) {
            bytes[offset] = (byte) value;
            bytes[offset + 1] = (byte) (value >>> 8);
        }

        byte[] toArray() {
            return size == 0 ? new byte[0] : Arrays.copyOf(bytes, size);
        }
    }

    /** DH-style mini-material ids understood by the LOD proxy; only the ones this mesher emits. */
    public static final byte MATERIAL_UNKNOWN = 0;
    public static final byte MATERIAL_LEAVES = 1;
    public static final byte MATERIAL_STONE = 2;
    public static final byte MATERIAL_WOOD = 3;
    public static final byte MATERIAL_METAL = 4;
    public static final byte MATERIAL_DIRT = 5;
    public static final byte MATERIAL_LAVA = 6;
    public static final byte MATERIAL_SNOW = 8;
    public static final byte MATERIAL_SAND = 9;
    public static final byte MATERIAL_WATER = 12;
    public static final byte MATERIAL_GRASS = 13;
    public static final byte MATERIAL_ILLUMINATED = 15;

    private static byte materialAlpha(int key) {
        // Opaque matter is fully covered; translucent glass-like matter carries a moderate coverage that
        // the proxy converts into a thin-glass interface. Water ignores alpha entirely.
        return (key >>> 28) == KIND_TRANSLUCENT ? (byte) 110 : (byte) 255;
    }

    private static byte materialLight(int material) {
        return material == MATERIAL_LAVA || material == MATERIAL_ILLUMINATED ? (byte) 15 : (byte) 0;
    }
}
