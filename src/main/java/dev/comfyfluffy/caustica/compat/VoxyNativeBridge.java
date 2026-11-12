package dev.comfyfluffy.caustica.compat;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Ray-traced LODs from an unmodified Voxy.
 *
 * <p>Voxy keeps two things apart: a storage/ingest engine that voxelises loaded chunks into a
 * multi-resolution world ({@code WorldEngine}), and an OpenGL renderer that draws it. Only the renderer
 * is tied to OpenGL. On the Vulkan backend Caustica leaves that renderer switched off (see {@link
 * VoxyCompat#vulkanBridgeRequested()}) and this class reads the engine's voxel data directly, meshes it
 * on worker threads with {@link VoxyLodMesher} and publishes the result as {@link
 * DistantHorizonsCompat.LodMesh}es, the same form the Distant Horizons path already feeds to the RT LOD
 * proxy. All Vulkan resources stay inside Caustica.
 *
 * <p>Voxy is reached through reflection only, so no Voxy type is in Caustica's constant pool and a
 * missing or reshaped Voxy simply leaves the bridge unavailable. Every call into Voxy is guarded: a
 * failure withdraws the published LODs and logs once; it never propagates into the frame.
 *
 * <p>Threading: {@link #tick()} and the accessors run on the render thread and own all bookkeeping.
 * Workers only read immutable inputs and append to {@link #completed}; Voxy's ingest threads only add to
 * {@link #dirty} through the section-change callback.
 */
final class VoxyNativeBridge {
    /** Voxy's coarsest level; one level-4 section spans 512 blocks. */
    private static final int MAX_LEVEL = 4;
    /** A cell is refined into its four children while the camera is closer than this many cell widths. */
    private static final int SUBDIVIDE_CELL_WIDTHS = 4;
    /** Upper bound on simultaneously selected cells, nearest first; bounds CPU and BVH memory. */
    private static final int MAX_CELLS = 4096;
    private static final int MAX_IN_FLIGHT = 6;
    private static final long PLAN_INTERVAL_NANOS = 200_000_000L;
    /** Minimum time between two rebuilds of the same cell while chunks keep streaming into it. */
    private static final long REBUILD_DEBOUNCE_NANOS = 1_500_000_000L;
    private static final int REPLAN_DISTANCE_BLOCKS = 16;
    private static final long FAILURE_BACKOFF_NANOS = 5_000_000_000L;

    private static final AtomicLong MESH_VERSION = new AtomicLong();

    private final Api api;
    private final ExecutorService workers;
    private final ConcurrentLinkedQueue<Completed> completed = new ConcurrentLinkedQueue<>();
    /** Cells whose source data changed; written by Voxy threads, drained on the render thread. */
    private final Set<Long> dirty = ConcurrentHashMap.newKeySet();
    /** Cells the callback should bother recording; everything else in the world is ignored. */
    private final Set<Long> tracked = ConcurrentHashMap.newKeySet();
    private final AtomicInteger inFlight = new AtomicInteger();

    // ---- render-thread state ----
    private final HashMap<Long, Entry> cells = new HashMap<>();
    private final HashSet<Long> building = new HashSet<>();
    private List<Long> desired = List.of();
    private Object engine;
    private Object level;
    private Object callback;
    /** Bumped by every reset; read by workers to drop work that belongs to a previous world. */
    private volatile long epoch;
    private long lastPlanNanos;
    private int planX = Integer.MIN_VALUE;
    private int planZ = Integer.MIN_VALUE;
    private int planChunks = -1;
    private int planVanillaChunks = -1;
    private boolean snapshotStale;
    private PaletteBuilder paletteBuilder;
    private boolean warned;
    private long failedAtNanos;

    private volatile List<DistantHorizonsCompat.LodMesh> snapshot = List.of();
    private volatile long revision;
    private volatile int renderDistanceChunks;

    private record Entry(DistantHorizonsCompat.LodMesh mesh, long builtNanos) {
    }

    private record Completed(long epoch, long key, DistantHorizonsCompat.LodMesh mesh, Throwable failure) {
    }

    private VoxyNativeBridge(Api api) {
        this.api = api;
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "Caustica Voxy LOD mesher");
                    thread.setDaemon(true);
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                });
        pool.allowCoreThreadTimeOut(true);
        this.workers = pool;
    }

    /** Resolve Voxy's data API, or return null when this Voxy build does not expose what is needed. */
    static VoxyNativeBridge create() {
        try {
            return new VoxyNativeBridge(new Api());
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("Voxy is installed but its world-data API is not the one Caustica knows "
                    + "(tested against Voxy 0.2.20); Voxy LODs will not be ray traced", t);
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Startup hooks (called from the optional Voxy mixins)
    // ------------------------------------------------------------------------------------------------

    /**
     * Stand in for {@code VoxyClient.initVoxyClient()} on the Vulkan backend: that method probes OpenGL
     * capabilities, which do not exist here. The one thing it does that the data engine needs is to
     * register the instance factory, so do exactly that and nothing OpenGL-related.
     */
    void installHeadlessInstanceFactory() throws ReflectiveOperationException {
        if (Boolean.TRUE.equals(api.commonIsAvailable.invoke(null))) return;
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "create" -> api.clientInstanceConstructor.newInstance();
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "Caustica headless Voxy instance factory";
            default -> throw new UnsupportedOperationException(method.getName());
        };
        Object factory = Proxy.newProxyInstance(api.instanceFactoryType.getClassLoader(),
                new Class<?>[]{api.instanceFactoryType}, handler);
        api.commonSetInstanceFactory.invoke(null, factory);
    }

    // ------------------------------------------------------------------------------------------------
    // Settings surface
    // ------------------------------------------------------------------------------------------------

    /**
     * Voxy's config object, or null while Voxy is unavailable. Reading the static field initialises
     * {@code VoxyConfig}, whose initialiser permanently disables Voxy when it runs before the instance
     * factory is registered; never touch it earlier than that.
     */
    private Object config() throws ReflectiveOperationException {
        if (!Boolean.TRUE.equals(api.commonIsAvailable.invoke(null))) return null;
        return api.configInstance.get(null);
    }

    boolean ingestEnabled() {
        try {
            Object config = config();
            return config != null && api.configIngestEnabled.getBoolean(config);
        } catch (Throwable t) {
            return false;
        }
    }

    boolean setIngestEnabled(boolean enabled) {
        try {
            Object config = config();
            if (config == null) return false;
            api.configIngestEnabled.setBoolean(config, enabled);
            api.configSave.invoke(config);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Ray-traced LOD distance. Deliberately Caustica's own setting rather than Voxy's render distance:
     * that one is tuned for Voxy's rasteriser (512 chunks by default), far more geometry than is sensible
     * to keep in a BVH, and it keeps its meaning for the player's OpenGL sessions.
     */
    int configuredRenderDistanceChunks() {
        return Math.clamp(CausticaConfig.Rt.Stability.VOXY_LOD_DISTANCE_CHUNKS.value(), 32, 512);
    }

    boolean setConfiguredRenderDistanceChunks(int chunks) {
        CausticaConfig.Rt.Stability.VOXY_LOD_DISTANCE_CHUNKS.set(Math.clamp(chunks, 32, 512));
        return true;
    }

    List<DistantHorizonsCompat.LodMesh> meshes() {
        return snapshot;
    }

    long revision() {
        return revision;
    }

    int renderDistanceChunks() {
        return renderDistanceChunks;
    }

    /** Drop every mesh and rebuild from Voxy's current data. Render thread. */
    void reset() {
        clear();
    }

    // ------------------------------------------------------------------------------------------------
    // Per-frame service (render thread)
    // ------------------------------------------------------------------------------------------------

    void tick(boolean wanted) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel clientLevel = minecraft.level;
        if (!wanted || clientLevel == null || minecraft.gameRenderer == null) {
            clear();
            return;
        }
        if (System.nanoTime() - failedAtNanos < FAILURE_BACKOFF_NANOS && failedAtNanos != 0L) {
            return; // already cleared by the failure; do not hammer a broken provider every frame
        }
        try {
            Object currentEngine = currentEngine(clientLevel);
            if (currentEngine == null) {
                clear();
                return;
            }
            if (currentEngine != engine || clientLevel != level) {
                clear();
                attach(currentEngine, clientLevel);
            }
            paletteBuilder.update();
            drainCompleted();

            var camera = minecraft.gameRenderer.mainCamera().position();
            int camX = (int) Math.floor(camera.x);
            int camZ = (int) Math.floor(camera.z);
            int chunks = configuredRenderDistanceChunks();
            int vanillaChunks = minecraft.options.renderDistance().get();
            renderDistanceChunks = chunks;

            long now = System.nanoTime();
            boolean moved = Math.abs(camX - planX) >= REPLAN_DISTANCE_BLOCKS
                    || Math.abs(camZ - planZ) >= REPLAN_DISTANCE_BLOCKS
                    || chunks != planChunks || vanillaChunks != planVanillaChunks;
            if (moved && now - lastPlanNanos >= PLAN_INTERVAL_NANOS) {
                lastPlanNanos = now;
                planX = camX;
                planZ = camZ;
                planChunks = chunks;
                planVanillaChunks = vanillaChunks;
                desired = selectCells(camX, camZ, chunks * 16, vanillaChunks * 16);
                // Shrink, then grow: a cell that stays selected is never absent from the set, so a change
                // notification arriving from a Voxy thread during the swap cannot be lost.
                HashSet<Long> selected = new HashSet<>(desired);
                tracked.retainAll(selected);
                tracked.addAll(selected);
                // Stale notifications for cells that are no longer selected are worthless.
                dirty.retainAll(selected);
                snapshotStale = true;
            }
            schedule(clientLevel, now);
            if (snapshotStale) publish();
        } catch (Throwable t) {
            clear();
            failedAtNanos = Math.max(1L, System.nanoTime());
            if (!warned) {
                warned = true;
                CausticaMod.LOGGER.warn("Reading Voxy's LOD data failed (logged once); Voxy LODs are withdrawn "
                        + "and retried every few seconds", t);
            }
        }
    }

    private Object currentEngine(ClientLevel clientLevel) throws ReflectiveOperationException {
        if (api.commonGetInstance.invoke(null) == null) return null; // not in a session, or Voxy disabled
        Object identifier = api.worldIdentifierOf.invoke(null, clientLevel);
        if (identifier == null) return null;
        Object current = api.identifierGetOrCreateEngine.invoke(identifier);
        if (current == null || !Boolean.TRUE.equals(api.engineIsLive.invoke(current))) return null;
        return current;
    }

    private void attach(Object newEngine, ClientLevel clientLevel) throws ReflectiveOperationException {
        engine = newEngine;
        level = clientLevel;
        paletteBuilder = new PaletteBuilder(api, api.engineGetMapper.invoke(newEngine), clientLevel);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "accept" -> {
                sectionChanged(args[0]);
                yield null;
            }
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "Caustica Voxy section listener";
            default -> null;
        };
        callback = Proxy.newProxyInstance(api.changeCallbackType.getClassLoader(),
                new Class<?>[]{api.changeCallbackType}, handler);
        // Voxy's own renderer is the only other user of this single callback slot, and it is never
        // created on the Vulkan backend, so the slot is free.
        api.engineSetDirtyCallback.invoke(newEngine, callback);
    }

    /** Voxy ingest thread: remember that a selected cell (or one whose wall borders it) changed. */
    private void sectionChanged(Object section) {
        try {
            int lvl = api.sectionLvl.getInt(section);
            int x = api.sectionX.getInt(section);
            int z = api.sectionZ.getInt(section);
            markDirty(cellKey(lvl, x, z));
            markDirty(cellKey(lvl, x - 1, z));
            markDirty(cellKey(lvl, x + 1, z));
            markDirty(cellKey(lvl, x, z - 1));
            markDirty(cellKey(lvl, x, z + 1));
        } catch (Throwable ignored) {
            // A notification that cannot be decoded is dropped; the cell is rebuilt on the next reset.
        }
    }

    private void markDirty(long key) {
        if (tracked.contains(key)) dirty.add(key);
    }

    private void clear() {
        boolean hadState = engine != null || !cells.isEmpty() || !snapshot.isEmpty();
        Object oldEngine = engine;
        Object oldCallback = callback;
        engine = null;
        level = null;
        callback = null;
        paletteBuilder = null;
        epoch++;
        cells.clear();
        building.clear();
        desired = List.of();
        tracked.clear();
        dirty.clear();
        completed.clear();
        planX = planZ = Integer.MIN_VALUE;
        planChunks = planVanillaChunks = -1;
        lastPlanNanos = 0L;
        snapshotStale = false;
        renderDistanceChunks = 0;
        if (oldEngine != null && oldCallback != null) {
            try {
                if (Boolean.TRUE.equals(api.engineIsLive.invoke(oldEngine))) {
                    api.engineSetDirtyCallback.invoke(oldEngine, (Object) null);
                }
            } catch (Throwable ignored) {
                // The engine is being torn down by Voxy; there is nothing left to detach from.
            }
        }
        if (!snapshot.isEmpty()) {
            snapshot = List.of();
            revision++;
        } else if (hadState) {
            revision++;
        }
    }

    private void drainCompleted() {
        Completed done;
        while ((done = completed.poll()) != null) {
            if (done.epoch != epoch) continue;
            building.remove(done.key);
            if (done.failure != null) {
                if (!warned) {
                    warned = true;
                    CausticaMod.LOGGER.warn("Meshing a Voxy LOD cell failed (logged once); it will be retried",
                            done.failure);
                }
                // Leave the previous entry (if any) in place and retry after the debounce.
                Entry previous = cells.get(done.key);
                cells.put(done.key, new Entry(previous != null ? previous.mesh : null, System.nanoTime()));
                dirty.add(done.key);
                continue;
            }
            cells.put(done.key, new Entry(done.mesh, System.nanoTime()));
            snapshotStale = true;
        }
    }

    private void schedule(ClientLevel clientLevel, long now) {
        if (desired.isEmpty() || inFlight.get() >= MAX_IN_FLIGHT) return;
        VoxyLodMesher.Palette palette = paletteBuilder.snapshot();
        int minY = clientLevel.getMinY();
        int maxYExclusive = minY + clientLevel.getHeight();
        // Sealing assumes the top of the world is open sky; under a ceiling (the Nether) the whole
        // playable space would count as an enclosed cave.
        boolean sealCaves = CausticaConfig.Rt.Stability.VOXY_LOD_SEAL_CAVES.value()
                && clientLevel.dimensionType().hasSkyLight() && !clientLevel.dimensionType().hasCeiling();
        for (long key : desired) {
            if (inFlight.get() >= MAX_IN_FLIGHT) break;
            if (building.contains(key)) continue;
            Entry entry = cells.get(key);
            if (entry != null) {
                if (!dirty.contains(key) || now - entry.builtNanos < REBUILD_DEBOUNCE_NANOS) continue;
            }
            dirty.remove(key);
            building.add(key);
            inFlight.incrementAndGet();
            long taskEpoch = epoch;
            Object taskEngine = engine;
            try {
                workers.execute(() -> build(taskEpoch, taskEngine, key, minY, maxYExclusive, palette, sealCaves));
            } catch (RuntimeException rejected) {
                inFlight.decrementAndGet();
                building.remove(key);
                throw rejected;
            }
        }
    }

    /** Worker thread: copy one column out of Voxy and mesh it. */
    private void build(long taskEpoch, Object taskEngine, long key, int minY, int maxYExclusive,
                       VoxyLodMesher.Palette palette, boolean sealCaves) {
        DistantHorizonsCompat.LodMesh mesh = null;
        Throwable failure = null;
        try {
            if (taskEpoch == epoch) {
                mesh = meshCell(taskEngine, key, minY, maxYExclusive, palette, sealCaves);
            }
        } catch (Throwable t) {
            failure = t;
        } finally {
            inFlight.decrementAndGet();
            completed.add(new Completed(taskEpoch, key, mesh, failure));
        }
    }

    private DistantHorizonsCompat.LodMesh meshCell(Object taskEngine, long key, int minY, int maxYExclusive,
                                                   VoxyLodMesher.Palette palette, boolean sealCaves)
            throws ReflectiveOperationException {
        int lvl = cellLevel(key);
        int cx = cellX(key);
        int cz = cellZ(key);
        int sectionBlocks = VoxyLodMesher.SIZE << lvl;
        int firstSection = Math.floorDiv(minY, sectionBlocks);
        int lastSection = Math.floorDiv(maxYExclusive - 1, sectionBlocks);
        int stack = lastSection - firstSection + 1;
        long[][] sections = new long[stack][];
        long[][] west = new long[stack][];
        long[][] east = new long[stack][];
        long[][] north = new long[stack][];
        long[][] south = new long[stack][];
        boolean any = false;
        // Holding an engine reference makes Voxy's shutdown wait for this copy instead of freeing the
        // storage underneath it; the reference is released before any meshing work starts.
        api.engineAcquireRef.invoke(taskEngine);
        try {
            for (int s = 0; s < stack; s++) {
                int sy = firstSection + s;
                sections[s] = copySection(taskEngine, lvl, cx, sy, cz, -1, 0);
                any |= sections[s] != null;
            }
            if (any) {
                for (int s = 0; s < stack; s++) {
                    int sy = firstSection + s;
                    west[s] = copySection(taskEngine, lvl, cx - 1, sy, cz, 0, 31);
                    east[s] = copySection(taskEngine, lvl, cx + 1, sy, cz, 0, 0);
                    north[s] = copySection(taskEngine, lvl, cx, sy, cz - 1, 1, 31);
                    south[s] = copySection(taskEngine, lvl, cx, sy, cz + 1, 1, 0);
                }
            }
        } finally {
            api.engineReleaseRef.invoke(taskEngine);
        }
        if (!any) return null;
        VoxyLodMesher.Result result = VoxyLodMesher.mesh(sections,
                new VoxyLodMesher.Borders(west, east, north, south), lvl, palette, sealCaves);
        if (result.isEmpty()) return null;
        return new DistantHorizonsCompat.LodMesh(key, MESH_VERSION.incrementAndGet(),
                cx * sectionBlocks, firstSection * sectionBlocks, cz * sectionBlocks,
                sectionBlocks, 1 << lvl, result.opaque(), result.transparent());
    }

    /**
     * Copy a section's voxels, or one of its border planes, out of Voxy and release it again.
     *
     * @param planeAxis -1 for the whole section, 0 for the plane {@code x = planeIndex} (laid out
     *                  {@code y * 32 + z}), 1 for the plane {@code z = planeIndex} ({@code y * 32 + x})
     * @return the copy, or null when the section holds no data
     */
    private long[] copySection(Object taskEngine, int lvl, int x, int y, int z, int planeAxis, int planeIndex)
            throws ReflectiveOperationException {
        Object section = api.engineAcquireIfExists.invoke(taskEngine, lvl, x, y, z);
        if (section == null) return null;
        try {
            long[] raw = (long[]) api.sectionRawData.invoke(section);
            if (raw == null || raw.length != VoxyLodMesher.VOLUME) return null;
            if (planeAxis < 0) {
                long[] copy = raw.clone();
                for (long id : copy) {
                    if (VoxyLodMesher.blockId(id) != 0) return copy;
                }
                return null; // only air (possibly with light): nothing to mesh
            }
            long[] plane = new long[VoxyLodMesher.SIZE * VoxyLodMesher.SIZE];
            for (int py = 0; py < VoxyLodMesher.SIZE; py++) {
                for (int w = 0; w < VoxyLodMesher.SIZE; w++) {
                    int index = planeAxis == 0
                            ? VoxyLodMesher.sectionIndex(planeIndex, py, w)
                            : VoxyLodMesher.sectionIndex(w, py, planeIndex);
                    plane[py * VoxyLodMesher.SIZE + w] = raw[index];
                }
            }
            return plane;
        } finally {
            api.sectionRelease.invoke(section);
        }
    }

    /** Rebuild the published list from ready cells, keeping old geometry where its replacement is pending. */
    private void publish() {
        snapshotStale = false;
        HashSet<Long> desiredSet = new HashSet<>(desired);
        HashSet<Long> pending = new HashSet<>();
        HashSet<Long> pendingAncestors = new HashSet<>();
        for (long key : desired) {
            if (cells.containsKey(key)) continue;
            pending.add(key);
            int lvl = cellLevel(key);
            int x = cellX(key);
            int z = cellZ(key);
            for (int up = lvl + 1; up <= MAX_LEVEL; up++) {
                pendingAncestors.add(cellKey(up, x >> (up - lvl), z >> (up - lvl)));
            }
        }
        // An unselected cell survives only while it covers (or is covered by) a selected cell that has
        // not been meshed yet: moving never opens a hole, and replaced geometry is freed promptly.
        for (Iterator<Map.Entry<Long, Entry>> it = cells.entrySet().iterator(); it.hasNext(); ) {
            long key = it.next().getKey();
            if (desiredSet.contains(key) || building.contains(key)) continue;
            boolean keep = pendingAncestors.contains(key);
            if (!keep) {
                int lvl = cellLevel(key);
                int x = cellX(key);
                int z = cellZ(key);
                for (int up = lvl + 1; up <= MAX_LEVEL && !keep; up++) {
                    keep = pending.contains(cellKey(up, x >> (up - lvl), z >> (up - lvl)));
                }
            }
            if (!keep) it.remove();
        }
        ArrayList<DistantHorizonsCompat.LodMesh> meshes = new ArrayList<>(cells.size());
        for (Entry entry : cells.values()) {
            if (entry.mesh != null) meshes.add(entry.mesh);
        }
        meshes.sort(Comparator.comparingLong(DistantHorizonsCompat.LodMesh::key));
        List<DistantHorizonsCompat.LodMesh> previous = snapshot;
        if (sameMeshes(previous, meshes)) return;
        snapshot = List.copyOf(meshes);
        revision++;
    }

    private static boolean sameMeshes(List<DistantHorizonsCompat.LodMesh> a, List<DistantHorizonsCompat.LodMesh> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------
    // Cell selection (pure; unit-tested)
    // ------------------------------------------------------------------------------------------------

    static long cellKey(int level, int x, int z) {
        return ((long) level << 56) | ((x & 0xFFFFFFFL) << 28) | (z & 0xFFFFFFFL);
    }

    static int cellLevel(long key) {
        return (int) (key >>> 56);
    }

    static int cellX(long key) {
        return (int) (key << 8 >> 36);
    }

    static int cellZ(long key) {
        return (int) (key << 36 >> 36);
    }

    /**
     * Quadtree selection around the camera: gap-free and overlap-free by construction, finest near the
     * camera and one level coarser for each doubling of distance. Cells that lie wholly inside the inner
     * half of the vanilla render distance are left to real chunks. Returned nearest first, capped at
     * {@link #MAX_CELLS}.
     */
    static List<Long> selectCells(int camX, int camZ, int radiusBlocks, int vanillaRadiusBlocks) {
        ArrayList<long[]> out = new ArrayList<>();
        int top = VoxyLodMesher.SIZE << MAX_LEVEL;
        int minX = Math.floorDiv(camX - radiusBlocks, top);
        int maxX = Math.floorDiv(camX + radiusBlocks, top);
        int minZ = Math.floorDiv(camZ - radiusBlocks, top);
        int maxZ = Math.floorDiv(camZ + radiusBlocks, top);
        int skipRadius = Math.max(0, vanillaRadiusBlocks / 2);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                select(MAX_LEVEL, x, z, camX, camZ, radiusBlocks, skipRadius, out);
            }
        }
        out.sort(Comparator.comparingLong(entry -> entry[1]));
        int count = Math.min(out.size(), MAX_CELLS);
        ArrayList<Long> keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            keys.add(out.get(i)[0]);
        }
        return keys;
    }

    private static void select(int level, int x, int z, int camX, int camZ, int radius, int skipRadius,
                               List<long[]> out) {
        long size = (long) VoxyLodMesher.SIZE << level;
        long x0 = x * size;
        long z0 = z * size;
        long dx = Math.max(Math.max(x0 - camX, camX - (x0 + size)), 0L);
        long dz = Math.max(Math.max(z0 - camZ, camZ - (z0 + size)), 0L);
        long nearestSq = dx * dx + dz * dz;
        if (nearestSq > (long) radius * radius) return;
        long threshold = SUBDIVIDE_CELL_WIDTHS * size;
        if (level > 0 && nearestSq < threshold * threshold) {
            for (int cx = 0; cx < 2; cx++) {
                for (int cz = 0; cz < 2; cz++) {
                    select(level - 1, x * 2 + cx, z * 2 + cz, camX, camZ, radius, skipRadius, out);
                }
            }
            return;
        }
        long farX = Math.max(Math.abs(x0 - camX), Math.abs(x0 + size - camX));
        long farZ = Math.max(Math.abs(z0 - camZ), Math.abs(z0 + size - camZ));
        if (Math.max(farX, farZ) < skipRadius) return;
        out.add(new long[]{cellKey(level, x, z), nearestSq});
    }

    // ------------------------------------------------------------------------------------------------
    // Block / biome appearance
    // ------------------------------------------------------------------------------------------------

    /** Grows {@link VoxyLodMesher.Palette} tables as Voxy's mapper learns new block states and biomes. */
    private static final class PaletteBuilder {
        private static final int FALLBACK_RGB = 0x7D7D7D;
        private final Api api;
        private final Object mapper;
        private final ClientLevel level;
        private byte[] kind = {VoxyLodMesher.KIND_AIR};
        private byte[] material = {0};
        private int[] rgb = {0};
        private byte[] tint = {0};
        private int blocks = 1; // id 0 is air
        private int[] grass = new int[0];
        private int[] foliage = new int[0];
        private int[] water = new int[0];
        private int biomes;
        private long nextBiomeCheckNanos;
        private VoxyLodMesher.Palette published;

        PaletteBuilder(Api api, Object mapper, ClientLevel level) {
            this.api = api;
            this.mapper = mapper;
            this.level = level;
        }

        void update() throws ReflectiveOperationException {
            int count = ((Number) api.mapperBlockStateCount.invoke(mapper)).intValue();
            if (count > blocks) {
                kind = Arrays.copyOf(kind, count);
                material = Arrays.copyOf(material, count);
                rgb = Arrays.copyOf(rgb, count);
                tint = Arrays.copyOf(tint, count);
                for (int id = blocks; id < count; id++) {
                    classify(id);
                }
                blocks = count;
                published = null;
            }
            long now = System.nanoTime();
            if (now >= nextBiomeCheckNanos) {
                nextBiomeCheckNanos = now + 1_000_000_000L;
                Object[] entries = (Object[]) api.mapperGetBiomeEntries.invoke(mapper);
                if (entries.length > biomes) {
                    grass = Arrays.copyOf(grass, entries.length);
                    foliage = Arrays.copyOf(foliage, entries.length);
                    water = Arrays.copyOf(water, entries.length);
                    Registry<Biome> registry = level.registryAccess().lookupOrThrow(Registries.BIOME);
                    for (int id = biomes; id < entries.length; id++) {
                        Biome biome = null;
                        try {
                            String name = (String) api.biomeEntryName.get(entries[id]);
                            biome = registry.getOptional(Identifier.parse(name)).orElse(null);
                        } catch (RuntimeException ignored) {
                            // Unknown or malformed biome id: use neutral temperate colours below.
                        }
                        grass[id] = scale(biome != null ? biome.getGrassColor(0.0, 0.0) : 0x79C05A, 0.62f);
                        foliage[id] = scale(biome != null ? biome.getFoliageColor() : 0x59AE30, 0.55f);
                        water[id] = (biome != null ? biome.getWaterColor() : 0x3F76E4) & 0xFFFFFF;
                    }
                    biomes = entries.length;
                    published = null;
                }
            }
        }

        VoxyLodMesher.Palette snapshot() {
            VoxyLodMesher.Palette current = published;
            if (current == null) {
                // Arrays are only ever replaced by longer copies, never mutated below their old length
                // after publication, so handing clones to workers keeps them strictly immutable.
                current = new VoxyLodMesher.Palette(kind.clone(), material.clone(), rgb.clone(), tint.clone(),
                        grass.clone(), foliage.clone(), water.clone(), FALLBACK_RGB, VoxyLodMesher.MATERIAL_STONE);
                published = current;
            }
            return current;
        }

        private void classify(int id) {
            kind[id] = VoxyLodMesher.KIND_OPAQUE;
            material[id] = VoxyLodMesher.MATERIAL_STONE;
            rgb[id] = FALLBACK_RGB;
            tint[id] = VoxyLodMesher.TINT_NONE;
            BlockState state;
            try {
                state = (BlockState) api.mapperBlockStateFromId.invoke(mapper, id);
            } catch (Throwable t) {
                return; // keep the neutral opaque fallback
            }
            if (state == null || state.isAir()) {
                kind[id] = VoxyLodMesher.KIND_AIR;
                return;
            }
            try {
                classify(id, state);
            } catch (Throwable t) {
                // A modded block that cannot answer without a real world: neutral opaque matter.
                kind[id] = VoxyLodMesher.KIND_OPAQUE;
                material[id] = VoxyLodMesher.MATERIAL_STONE;
                rgb[id] = FALLBACK_RGB;
                tint[id] = VoxyLodMesher.TINT_NONE;
            }
        }

        private void classify(int id, BlockState state) {
            FluidState fluid = state.getFluidState();
            MapColor mapColor = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            int base = mapColor != null && mapColor != MapColor.NONE ? scale(mapColor.col, 0.86f) : FALLBACK_RGB;
            boolean solidFootprint = solidFootprint(state);
            if (!fluid.isEmpty() && !solidFootprint) {
                // Identity first, tag second: tags are bound late (and not at all for some modded fluids).
                if (fluid.getType().isSame(Fluids.LAVA) || fluid.typeHolder().is(FluidTags.LAVA)) {
                    kind[id] = VoxyLodMesher.KIND_OPAQUE;
                    material[id] = VoxyLodMesher.MATERIAL_LAVA;
                    rgb[id] = 0xD4610F;
                } else {
                    kind[id] = VoxyLodMesher.KIND_TRANSLUCENT;
                    material[id] = VoxyLodMesher.MATERIAL_WATER;
                    rgb[id] = 0x3F76E4;
                    tint[id] = VoxyLodMesher.TINT_WATER;
                }
                return;
            }
            if (state.getBlock() instanceof LeavesBlock || state.typeHolder().is(BlockTags.LEAVES)) {
                // A distant canopy reads as solid foliage; treating it as glass mirrors the forest.
                kind[id] = VoxyLodMesher.KIND_OPAQUE;
                material[id] = VoxyLodMesher.MATERIAL_LEAVES;
                rgb[id] = base;
                // Only the green families follow the biome; cherry, azalea blossoms etc. keep their colour.
                if (mapColor == MapColor.PLANT) tint[id] = VoxyLodMesher.TINT_FOLIAGE;
                return;
            }
            if (!solidFootprint) {
                // Plants, torches, rails, fences, panes...: far too small to be a whole voxel.
                kind[id] = VoxyLodMesher.KIND_AIR;
                return;
            }
            rgb[id] = base;
            if (!state.canOcclude()) {
                // Full-size but see-through: glass, ice, slime and friends.
                kind[id] = VoxyLodMesher.KIND_TRANSLUCENT;
                material[id] = VoxyLodMesher.MATERIAL_UNKNOWN;
                // Clear glass has no map colour at all; give it a neutral pale tint instead of mid grey.
                if (mapColor == null || mapColor == MapColor.NONE) rgb[id] = 0xDCE6F0;
                return;
            }
            kind[id] = VoxyLodMesher.KIND_OPAQUE;
            if (state.getLightEmission() >= 10) {
                material[id] = VoxyLodMesher.MATERIAL_ILLUMINATED;
            } else if (mapColor == MapColor.GRASS) {
                material[id] = VoxyLodMesher.MATERIAL_GRASS;
                tint[id] = VoxyLodMesher.TINT_GRASS;
            } else if (mapColor == MapColor.SNOW) {
                material[id] = VoxyLodMesher.MATERIAL_SNOW;
            } else if (mapColor == MapColor.METAL || mapColor == MapColor.GOLD || mapColor == MapColor.DIAMOND) {
                material[id] = VoxyLodMesher.MATERIAL_METAL;
            } else if (mapColor == MapColor.SAND) {
                material[id] = VoxyLodMesher.MATERIAL_SAND;
            } else if (mapColor == MapColor.DIRT) {
                material[id] = VoxyLodMesher.MATERIAL_DIRT;
            } else if (mapColor == MapColor.WOOD) {
                material[id] = VoxyLodMesher.MATERIAL_WOOD;
            } else {
                material[id] = VoxyLodMesher.MATERIAL_STONE;
            }
        }

        /** Whether the block fills most of its cell: full footprint and at least roughly half height. */
        private static boolean solidFootprint(BlockState state) {
            VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (shape.isEmpty()) return false;
            double width = shape.max(Direction.Axis.X) - shape.min(Direction.Axis.X);
            double depth = shape.max(Direction.Axis.Z) - shape.min(Direction.Axis.Z);
            double height = shape.max(Direction.Axis.Y) - shape.min(Direction.Axis.Y);
            return width * depth >= 0.9 && height >= 0.45;
        }

        private static int scale(int color, float factor) {
            int r = Math.clamp(Math.round(((color >> 16) & 0xFF) * factor), 0, 255);
            int g = Math.clamp(Math.round(((color >> 8) & 0xFF) * factor), 0, 255);
            int b = Math.clamp(Math.round((color & 0xFF) * factor), 0, 255);
            return (r << 16) | (g << 8) | b;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Reflective view of Voxy (resolved once; any missing member makes the whole bridge unavailable)
    // ------------------------------------------------------------------------------------------------

    private static final class Api {
        final Method commonGetInstance;
        final Method commonIsAvailable;
        final Method commonSetInstanceFactory;
        final Class<?> instanceFactoryType;
        final Constructor<?> clientInstanceConstructor;
        final Method worldIdentifierOf;
        final Method identifierGetOrCreateEngine;
        final Method engineIsLive;
        final Method engineAcquireIfExists;
        final Method engineSetDirtyCallback;
        final Method engineGetMapper;
        final Method engineAcquireRef;
        final Method engineReleaseRef;
        final Class<?> changeCallbackType;
        final Method sectionRawData;
        final Method sectionRelease;
        final Field sectionLvl;
        final Field sectionX;
        final Field sectionZ;
        final Method mapperBlockStateCount;
        final Method mapperBlockStateFromId;
        final Method mapperGetBiomeEntries;
        final Field biomeEntryName;
        final Field configInstance;
        final Field configIngestEnabled;
        final Method configSave;

        Api() throws ReflectiveOperationException {
            Class<?> common = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon");
            commonGetInstance = common.getMethod("getInstance");
            commonIsAvailable = common.getMethod("isAvailable");
            instanceFactoryType = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon$IInstanceFactory");
            commonSetInstanceFactory = common.getMethod("setInstanceFactory", instanceFactoryType);
            // initialize = false: constructing the class object must not run Voxy client code early.
            ClassLoader loader = common.getClassLoader();
            clientInstanceConstructor = Class.forName("me.cortex.voxy.client.VoxyClientInstance", false, loader)
                    .getConstructor();

            Class<?> identifier = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier", false, loader);
            worldIdentifierOf = identifier.getMethod("of", net.minecraft.world.level.Level.class);
            identifierGetOrCreateEngine = identifier.getMethod("getOrCreateEngine");

            Class<?> engine = Class.forName("me.cortex.voxy.common.world.WorldEngine", false, loader);
            changeCallbackType = Class.forName("me.cortex.voxy.common.world.WorldEngine$ISectionChangeCallback",
                    false, loader);
            engineIsLive = engine.getMethod("isLive");
            engineAcquireIfExists = engine.getMethod("acquireIfExists", int.class, int.class, int.class, int.class);
            engineSetDirtyCallback = engine.getMethod("setDirtyCallback", changeCallbackType);
            engineGetMapper = engine.getMethod("getMapper");
            engineAcquireRef = engine.getMethod("acquireRef");
            engineReleaseRef = engine.getMethod("releaseRef");

            Class<?> section = Class.forName("me.cortex.voxy.common.world.WorldSection", false, loader);
            sectionRawData = section.getMethod("_unsafeGetRawDataArray");
            sectionRelease = section.getMethod("release");
            sectionLvl = section.getField("lvl");
            sectionX = section.getField("x");
            sectionZ = section.getField("z");

            Class<?> mapper = Class.forName("me.cortex.voxy.common.world.other.Mapper", false, loader);
            mapperBlockStateCount = mapper.getMethod("getBlockStateCount");
            mapperBlockStateFromId = mapper.getMethod("getBlockStateFromBlockId", int.class);
            mapperGetBiomeEntries = mapper.getMethod("getBiomeEntries");
            biomeEntryName = Class.forName("me.cortex.voxy.common.world.other.Mapper$BiomeEntry", false, loader)
                    .getField("biome");

            Class<?> config = Class.forName("me.cortex.voxy.client.config.VoxyConfig", false, loader);
            configInstance = config.getField("CONFIG");
            configIngestEnabled = config.getField("ingestEnabled");
            configSave = config.getMethod("save");

            if (long[].class != sectionRawData.getReturnType()
                    || boolean.class != configIngestEnabled.getType()
                    || String.class != biomeEntryName.getType()
                    || !BlockState.class.isAssignableFrom(mapperBlockStateFromId.getReturnType())) {
                throw new NoSuchMethodException("Voxy data API has unexpected member types");
            }
        }
    }
}
