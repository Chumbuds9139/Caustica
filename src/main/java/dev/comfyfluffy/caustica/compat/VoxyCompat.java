package dev.comfyfluffy.caustica.compat;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Optional Voxy integration. Two providers are supported behind one surface:
 *
 * <ul>
 *   <li><b>Native</b> ({@link VoxyNativeBridge}) — an unmodified Voxy release. Voxy refuses the Vulkan
 *       backend and only ships an OpenGL renderer, so Caustica keeps Vulkan alive, leaves Voxy's renderer
 *       off, and ray traces the LOD voxels Voxy's storage engine still ingests and persists.</li>
 *   <li><b>Legacy</b> — a Voxy build that exposes {@code me.cortex.voxy.client.compat.CausticaBridge}
 *       and hands over ready-made LOD quads.</li>
 * </ul>
 *
 * <p>No Voxy class appears in Caustica's constant pool, so Caustica still starts normally when Voxy is
 * absent or a different Voxy build is installed. Both providers publish immutable 64-byte LOD quads;
 * all Vulkan buffers, BLASes, TLAS instances, shading and lifetime management remain inside Caustica.</p>
 */
public final class VoxyCompat {
    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("voxy");
    private static final Api API = LOADED ? Api.create() : null;
    private static volatile List<DistantHorizonsCompat.LodMesh> snapshot = List.of();
    private static volatile long revision;
    private static volatile long observedSourceRevision = Long.MIN_VALUE;
    private static volatile int renderDistanceChunks;
    private static volatile boolean warned;

    // ---- Native provider (unmodified Voxy) ----
    private static volatile VoxyNativeBridge nativeBridge;
    private static volatile boolean nativeBridgeResolved;
    /** Set once Minecraft created its Vulkan device past Voxy's veto; from then on Voxy runs headless. */
    private static volatile boolean vulkanDeviceActive;
    private static volatile boolean headlessInstallFailed;

    private VoxyCompat() {
    }

    /**
     * Whether Caustica should override Voxy's refusal of the Vulkan backend. True only for an unmodified
     * Voxy (the legacy bridge build does not veto), with ray tracing enabled and the bridge not disabled
     * in {@code stability.voxy-vulkan-bridge}.
     */
    public static boolean vulkanBridgeRequested() {
        if (!LOADED || API != null) return false;
        try {
            return CausticaConfig.Rt.ENABLED.value() && CausticaConfig.Rt.Stability.VOXY_VULKAN_BRIDGE.value();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Record that the Vulkan device exists although Voxy is installed (see MinecraftVoxyBackendMixin). */
    public static void markVulkanDeviceActive() {
        vulkanDeviceActive = true;
        CausticaMod.LOGGER.info("Voxy is installed: keeping the Vulkan backend for ray tracing. Voxy's OpenGL "
                + "renderer stays off and its LOD data is ray traced by Caustica instead.");
    }

    /** True when Voxy must not touch OpenGL: the game is running on Vulkan through Caustica's bridge. */
    public static boolean vulkanDeviceActive() {
        return vulkanDeviceActive;
    }

    /**
     * Replace Voxy's OpenGL-probing client initialisation on Vulkan with the single step its data engine
     * needs. Failure leaves Voxy cleanly unavailable (no instance, no ingest) rather than half-initialised.
     */
    public static void installHeadlessVoxy() {
        VoxyNativeBridge bridge = nativeBridge();
        try {
            if (bridge == null) throw new IllegalStateException("Voxy data API unavailable");
            bridge.installHeadlessInstanceFactory();
            CausticaMod.LOGGER.info("Voxy data engine started without its OpenGL renderer");
        } catch (Throwable t) {
            headlessInstallFailed = true;
            CausticaMod.LOGGER.warn("Could not start Voxy's data engine on the Vulkan backend; Voxy stays "
                    + "inactive this session (the game itself is unaffected)", t);
        }
    }

    private static VoxyNativeBridge nativeBridge() {
        if (!LOADED || API != null) return null;
        if (!nativeBridgeResolved) {
            synchronized (VoxyCompat.class) {
                if (!nativeBridgeResolved) {
                    nativeBridge = VoxyNativeBridge.create();
                    nativeBridgeResolved = true;
                }
            }
        }
        return nativeBridge;
    }

    /** The native provider when it can actually deliver LODs in this session, else null. */
    private static VoxyNativeBridge usableNativeBridge() {
        if (!vulkanDeviceActive || headlessInstallFailed) return null;
        return nativeBridge();
    }

    public static boolean enabled() {
        return API != null || usableNativeBridge() != null;
    }

    /** Whether the installed provider is currently enabled in Voxy's persistent config. */
    public static boolean active() {
        Api api = API;
        if (api == null) {
            return usableNativeBridge() != null && CausticaConfig.Rt.Stability.VOXY_LODS.value();
        }
        try {
            return Boolean.TRUE.equals(api.configuredEnabled.invoke(null));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean setActive(boolean enabled) {
        Api api = API;
        if (api == null) {
            if (usableNativeBridge() == null) return false;
            CausticaConfig.Rt.Stability.VOXY_LODS.set(enabled);
            return true;
        }
        try {
            api.setConfiguredEnabled.invoke(null, enabled);
            if (!enabled) clearLocalSnapshot();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean ingestEnabled() {
        Api api = API;
        if (api == null) {
            VoxyNativeBridge bridge = usableNativeBridge();
            return bridge != null && bridge.ingestEnabled();
        }
        try {
            return Boolean.TRUE.equals(api.configuredIngestEnabled.invoke(null));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean setIngestEnabled(boolean enabled) {
        Api api = API;
        if (api == null) {
            VoxyNativeBridge bridge = usableNativeBridge();
            return bridge != null && bridge.setIngestEnabled(enabled);
        }
        try {
            api.setConfiguredIngestEnabled.invoke(null, enabled);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static int configuredRenderDistanceChunks() {
        Api api = API;
        if (api == null) {
            VoxyNativeBridge bridge = usableNativeBridge();
            return bridge != null ? bridge.configuredRenderDistanceChunks() : 32;
        }
        try {
            return Math.clamp(((Number) api.configuredRenderDistanceChunks.invoke(null)).intValue(), 32, 512);
        } catch (Throwable ignored) {
            return 32;
        }
    }

    public static boolean setConfiguredRenderDistanceChunks(int chunks) {
        Api api = API;
        if (api == null) {
            VoxyNativeBridge bridge = usableNativeBridge();
            return bridge != null && bridge.setConfiguredRenderDistanceChunks(chunks);
        }
        try {
            api.setConfiguredRenderDistanceChunks.invoke(null, Math.clamp(chunks, 32, 512));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Poll the provider on the render thread; the RT planning worker only reads the immutable result. */
    public static void tick() {
        Api api = API;
        if (api == null) {
            VoxyNativeBridge bridge = usableNativeBridge();
            if (bridge != null) bridge.tick(CausticaConfig.Rt.Stability.VOXY_LODS.value());
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.gameRenderer == null) {
            if (!snapshot.isEmpty()) {
                snapshot = List.of();
                revision++;
            }
            observedSourceRevision = Long.MIN_VALUE;
            renderDistanceChunks = 0;
            return;
        }
        try {
            var camera = minecraft.gameRenderer.mainCamera().position();
            int vanillaChunks = minecraft.options.renderDistance().get();
            Object value = api.poll.invoke(null, camera.x, camera.y, camera.z, vanillaChunks,
                    minecraft.level.getMinY(), minecraft.level.getMaxY());
            long sourceRevision = ((Number) api.revision.invoke(null)).longValue();
            renderDistanceChunks = ((Number) api.renderDistanceChunks.invoke(null)).intValue();
            if (sourceRevision == observedSourceRevision) return;
            List<?> meshes = value instanceof List<?> list ? list : List.of();
            ArrayList<DistantHorizonsCompat.LodMesh> converted = new ArrayList<>(meshes.size());
            for (Object mesh : meshes) {
                if (mesh == null) continue;
                Access access = api.access(mesh.getClass());
                converted.add(new DistantHorizonsCompat.LodMesh(
                        ((Number) access.key.invoke(mesh)).longValue(),
                        ((Number) access.version.invoke(mesh)).longValue(),
                        ((Number) access.originX.invoke(mesh)).intValue(),
                        ((Number) access.originY.invoke(mesh)).intValue(),
                        ((Number) access.originZ.invoke(mesh)).intValue(),
                        ((Number) access.width.invoke(mesh)).intValue(),
                        ((Number) access.dataPointWidth.invoke(mesh)).intValue(),
                        (byte[]) access.opaque.invoke(mesh),
                        (byte[]) access.transparent.invoke(mesh)));
            }
            snapshot = List.copyOf(converted);
            observedSourceRevision = sourceRevision;
            revision++;
        } catch (Throwable failure) {
            if (!snapshot.isEmpty()) {
                snapshot = List.of();
                revision++;
            }
            observedSourceRevision = Long.MIN_VALUE;
            if (!warned) {
                warned = true;
                CausticaMod.LOGGER.warn("Voxy is installed but its Caustica LOD bridge failed; "
                        + "use the bundled no-Sodium Voxy build", failure);
            }
        }
    }

    public static List<DistantHorizonsCompat.LodMesh> meshes() {
        VoxyNativeBridge bridge = API == null ? usableNativeBridge() : null;
        return bridge != null ? bridge.meshes() : snapshot;
    }

    public static long revision() {
        VoxyNativeBridge bridge = API == null ? usableNativeBridge() : null;
        return bridge != null ? bridge.revision() : revision;
    }

    public static int renderDistanceChunks() {
        VoxyNativeBridge bridge = API == null ? usableNativeBridge() : null;
        return bridge != null ? bridge.renderDistanceChunks() : renderDistanceChunks;
    }

    public static boolean reset() {
        Api api = API;
        if (api == null) {
            VoxyNativeBridge bridge = usableNativeBridge();
            // Only the render thread may touch the bridge's bookkeeping; off-thread callers (none today)
            // are answered without a reset rather than racing it.
            if (bridge == null || !com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) return false;
            bridge.reset();
            return true;
        }
        clearLocalSnapshot();
        try {
            api.reset.invoke(null);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void clearLocalSnapshot() {
        snapshot = List.of();
        revision++;
        observedSourceRevision = Long.MIN_VALUE;
        renderDistanceChunks = 0;
    }

    private record Access(Method key, Method version, Method originX, Method originY, Method originZ,
                          Method width, Method dataPointWidth, Method opaque, Method transparent) {
        static Access create(Class<?> type) throws ReflectiveOperationException {
            return new Access(type.getMethod("key"), type.getMethod("version"),
                    type.getMethod("originX"), type.getMethod("originY"), type.getMethod("originZ"),
                    type.getMethod("width"), type.getMethod("dataPointWidth"),
                    type.getMethod("opaque"), type.getMethod("transparent"));
        }
    }

    private static final class Api {
        final Method poll;
        final Method revision;
        final Method renderDistanceChunks;
        final Method reset;
        final Method configuredEnabled;
        final Method setConfiguredEnabled;
        final Method configuredIngestEnabled;
        final Method setConfiguredIngestEnabled;
        final Method configuredRenderDistanceChunks;
        final Method setConfiguredRenderDistanceChunks;
        private volatile Class<?> meshType;
        private volatile Access meshAccess;

        private Api(Class<?> bridge) throws ReflectiveOperationException {
            poll = bridge.getMethod("poll", double.class, double.class, double.class,
                    int.class, int.class, int.class);
            revision = bridge.getMethod("revision");
            renderDistanceChunks = bridge.getMethod("renderDistanceChunks");
            reset = bridge.getMethod("reset");
            configuredEnabled = bridge.getMethod("configuredEnabled");
            setConfiguredEnabled = bridge.getMethod("setConfiguredEnabled", boolean.class);
            configuredIngestEnabled = bridge.getMethod("configuredIngestEnabled");
            setConfiguredIngestEnabled = bridge.getMethod("setConfiguredIngestEnabled", boolean.class);
            configuredRenderDistanceChunks = bridge.getMethod("configuredRenderDistanceChunks");
            setConfiguredRenderDistanceChunks =
                    bridge.getMethod("setConfiguredRenderDistanceChunks", int.class);
        }

        static Api create() {
            try {
                Class<?> bridge = Class.forName("me.cortex.voxy.client.compat.CausticaBridge");
                Method available = bridge.getMethod("available");
                if (!Boolean.TRUE.equals(available.invoke(null))) return null;
                return new Api(bridge);
            } catch (Throwable ignored) {
                return null;
            }
        }

        Access access(Class<?> type) throws ReflectiveOperationException {
            Access current = meshAccess;
            if (current != null && meshType == type) return current;
            synchronized (this) {
                if (meshAccess == null || meshType != type) {
                    meshAccess = Access.create(type);
                    meshType = type;
                }
                return meshAccess;
            }
        }
    }
}
