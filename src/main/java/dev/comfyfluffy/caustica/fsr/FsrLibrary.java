package dev.comfyfluffy.caustica.fsr;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/**
 * FFM bindings for the FSR shim ({@code fsrshim.dll}).
 *
 * <p>The shim wraps the AMD FidelityFX (ffx_api) Vulkan runtime behind a flat C ABI (see
 * {@code native/fsr_shim/fsr_shim.cpp}): every descriptor struct the ffx_api needs lives in the
 * shim, so Java only passes primitives and raw Vulkan handles (as {@code long} addresses), same
 * trade-off as {@code NgxLibrary}.
 */
public final class FsrLibrary {
    private static final Linker LINKER = Linker.nativeLinker();

    private final MethodHandle init;
    private final MethodHandle createUpscaler;
    private final MethodHandle destroyUpscaler;
    private final MethodHandle dispatchUpscale;
    private final MethodHandle queryRenderSize;
    private final MethodHandle queryJitterPhaseCount;
    private final MethodHandle queryJitterOffset;
    private final MethodHandle lastResult;

    private FsrLibrary(SymbolLookup lookup) {
        // int fsrshim_init(u64 vkDevice, u64 vkPhysicalDevice, u64 vkGetDeviceProcAddr, wchar* runtimePath)
        this.init = handle(lookup, "fsrshim_init",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS));
        // void* fsrshim_create_upscaler(u32 maxRW, u32 maxRH, u32 dispW, u32 dispH, u32 flags)
        this.createUpscaler = handle(lookup, "fsrshim_create_upscaler",
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        // int fsrshim_destroy_upscaler(void* handle)
        this.destroyUpscaler = handle(lookup, "fsrshim_destroy_upscaler",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        // int fsrshim_dispatch_upscale(handle, cmd, [color/depth/mv/out: image,fmt]*4, rw,rh,uw,uh,
        //                              jx, jy, frameMs, reset, cameraNear, cameraFar, cameraFovY)
        this.dispatchUpscale = handle(lookup, "fsrshim_dispatch_upscale",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT));
        // int fsrshim_query_render_size(u32 quality, u32 dispW, u32 dispH, u32* outRW, u32* outRH)
        this.queryRenderSize = handle(lookup, "fsrshim_query_render_size",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        // int fsrshim_query_jitter_phase_count(u32 renderW, u32 displayW, int* outCount)
        this.queryJitterPhaseCount = handle(lookup, "fsrshim_query_jitter_phase_count",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        // int fsrshim_query_jitter_offset(int index, int phaseCount, float* outX, float* outY)
        this.queryJitterOffset = handle(lookup, "fsrshim_query_jitter_offset",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.lastResult = handle(lookup, "fsrshim_last_result",
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
    }

    public static FsrLibrary load(Path dll) {
        return new FsrLibrary(SymbolLookup.libraryLookup(dll, Arena.global()));
    }

    private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
                lookup.find(name).orElseThrow(() -> new IllegalStateException("fsrshim missing export " + name)),
                desc);
    }

    public int init(long vkDevice, long vkPhysicalDevice, long vkDeviceProcAddr, MemorySegment runtimePath) {
        try {
            return (int) this.init.invokeExact(vkDevice, vkPhysicalDevice, vkDeviceProcAddr, runtimePath);
        } catch (Throwable t) {
            throw new RuntimeException("fsrshim_init failed", t);
        }
    }

    public MemorySegment createUpscaler(int maxRenderWidth, int maxRenderHeight,
                                        int displayWidth, int displayHeight, int flags) {
        try {
            return (MemorySegment) this.createUpscaler.invokeExact(
                    maxRenderWidth, maxRenderHeight, displayWidth, displayHeight, flags);
        } catch (Throwable t) {
            throw new RuntimeException("fsrshim_create_upscaler failed", t);
        }
    }

    public int destroyUpscaler(MemorySegment handle) {
        try {
            return (int) this.destroyUpscaler.invokeExact(handle);
        } catch (Throwable t) {
            throw new RuntimeException("fsrshim_destroy_upscaler failed", t);
        }
    }

    public int dispatchUpscale(MemorySegment handle, long cmd,
                               long colorImage, int colorFormat,
                               long depthImage, int depthFormat,
                               long mvImage, int mvFormat,
                               long outImage, int outFormat,
                               int renderWidth, int renderHeight, int upscaleWidth, int upscaleHeight,
                               float jitterX, float jitterY, float frameTimeMs, int reset,
                               float cameraNear, float cameraFar, float cameraFovY) {
        try {
            return (int) this.dispatchUpscale.invokeExact(handle, cmd,
                    colorImage, colorFormat, depthImage, depthFormat, mvImage, mvFormat,
                    outImage, outFormat,
                    renderWidth, renderHeight, upscaleWidth, upscaleHeight,
                    jitterX, jitterY, frameTimeMs, reset,
                    cameraNear, cameraFar, cameraFovY);
        } catch (Throwable t) {
            throw new RuntimeException("fsrshim_dispatch_upscale failed", t);
        }
    }

    public int queryRenderSize(int qualityMode, int displayWidth, int displayHeight,
                               MemorySegment outRenderWidth, MemorySegment outRenderHeight) {
        try {
            return (int) this.queryRenderSize.invokeExact(
                    qualityMode, displayWidth, displayHeight, outRenderWidth, outRenderHeight);
        } catch (Throwable t) {
            throw new RuntimeException("fsrshim_query_render_size failed", t);
        }
    }

    public int queryJitterPhaseCount(int renderWidth, int displayWidth, MemorySegment outPhaseCount) {
        try {
            return (int) this.queryJitterPhaseCount.invokeExact(renderWidth, displayWidth, outPhaseCount);
        } catch (Throwable t) {
            throw new RuntimeException("fsrshim_query_jitter_phase_count failed", t);
        }
    }

    public int queryJitterOffset(int index, int phaseCount, MemorySegment outX, MemorySegment outY) {
        try {
            return (int) this.queryJitterOffset.invokeExact(index, phaseCount, outX, outY);
        } catch (Throwable t) {
            throw new RuntimeException("fsrshim_query_jitter_offset failed", t);
        }
    }

    public int lastResult() {
        try {
            return (int) this.lastResult.invokeExact();
        } catch (Throwable t) {
            throw new RuntimeException("fsrshim_last_result failed", t);
        }
    }
}
