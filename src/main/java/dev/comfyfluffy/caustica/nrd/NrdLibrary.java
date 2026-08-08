package dev.comfyfluffy.caustica.nrd;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/**
 * FFM bindings for the NRD shim ({@code nrdshim.dll}).
 *
 * <p>The shim wraps NVIDIA NRD's REBLUR_DIFFUSE_SPECULAR denoiser (via NRD's own NRI Integration
 * layer) behind a flat C ABI (see {@code native/nrd_shim/nrd_shim.cpp}); every NRD/NRI struct lives
 * in the shim, so Java only passes primitives and raw Vulkan handles (as {@code long} addresses) —
 * the same trade-off as {@code NgxLibrary} and {@code FsrLibrary}.
 */
public final class NrdLibrary {
    private static final Linker LINKER = Linker.nativeLinker();

    private final MethodHandle init;
    private final MethodHandle newFrame;
    private final MethodHandle setSettings;
    private final MethodHandle setReblurSettings;
    private final MethodHandle denoise;
    private final MethodHandle destroy;
    private final MethodHandle lastResult;

    private NrdLibrary(SymbolLookup lookup) {
        // int nrdshim_init(u64 instance, u64 phys, u64 device, u64 gipa, u32 queueFamily,
        //                  u32 vkMinor, u32 w, u32 h)
        this.init = handle(lookup, "nrdshim_init",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        this.newFrame = handle(lookup, "nrdshim_new_frame", FunctionDescriptor.ofVoid());
        // int nrdshim_set_settings(f32[16] v2c, f32[16] v2cPrev, f32[16] w2v, f32[16] w2vPrev,
        //                          jx, jy, jpx, jpy, mvScaleX, mvScaleY, u32 frameIndex, i32 reset,
        //                          i32 enableValidation)
        this.setSettings = handle(lookup, "nrdshim_set_settings",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        // int nrdshim_set_reblur_settings(i32 hitDistReconMode, i32 maxAccum, i32 maxFastAccum)
        this.setReblurSettings = handle(lookup, "nrdshim_set_reblur_settings",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        // int nrdshim_denoise(u64 cmd, [image,fmt]x7: mv, normalRough, viewZ, diffIn, specIn, diffOut,
        //                     specOut, [validationImage, validationFmt])
        this.denoise = handle(lookup, "nrdshim_denoise",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
        this.destroy = handle(lookup, "nrdshim_destroy", FunctionDescriptor.ofVoid());
        this.lastResult = handle(lookup, "nrdshim_last_result",
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
    }

    public static NrdLibrary load(Path dll) {
        return new NrdLibrary(SymbolLookup.libraryLookup(dll, Arena.global()));
    }

    private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
                lookup.find(name).orElseThrow(() -> new IllegalStateException("nrdshim missing export " + name)),
                desc);
    }

    public int init(long vkInstance, long vkPhysicalDevice, long vkDevice, long vkGetInstanceProcAddr,
                    int queueFamilyIndex, int vkMinorVersion, int width, int height) {
        try {
            return (int) this.init.invokeExact(vkInstance, vkPhysicalDevice, vkDevice, vkGetInstanceProcAddr,
                    queueFamilyIndex, vkMinorVersion, width, height);
        } catch (Throwable t) {
            throw new RuntimeException("nrdshim_init failed", t);
        }
    }

    public void newFrame() {
        try {
            this.newFrame.invokeExact();
        } catch (Throwable t) {
            throw new RuntimeException("nrdshim_new_frame failed", t);
        }
    }

    public int setSettings(MemorySegment viewToClip, MemorySegment viewToClipPrev,
                           MemorySegment worldToView, MemorySegment worldToViewPrev,
                           float jitterX, float jitterY, float jitterPrevX, float jitterPrevY,
                           float mvScaleX, float mvScaleY, int frameIndex, int reset,
                           int enableValidation) {
        try {
            return (int) this.setSettings.invokeExact(viewToClip, viewToClipPrev, worldToView, worldToViewPrev,
                    jitterX, jitterY, jitterPrevX, jitterPrevY,
                    mvScaleX, mvScaleY, frameIndex, reset, enableValidation);
        } catch (Throwable t) {
            throw new RuntimeException("nrdshim_set_settings failed", t);
        }
    }

    /**
     * REBLUR tuning. {@code hitDistReconstructionMode}: 0 OFF, 1 AREA_3X3, 2 AREA_5X5; the frame
     * counts accept 0 = keep NRD's default. The shim remembers these across resize re-inits.
     */
    public int setReblurSettings(int hitDistReconstructionMode, int maxAccumulatedFrameNum,
                                 int maxFastAccumulatedFrameNum) {
        try {
            return (int) this.setReblurSettings.invokeExact(
                    hitDistReconstructionMode, maxAccumulatedFrameNum, maxFastAccumulatedFrameNum);
        } catch (Throwable t) {
            throw new RuntimeException("nrdshim_set_reblur_settings failed", t);
        }
    }

    public int denoise(long cmd,
                       long mvImage, int mvFormat,
                       long normalRoughnessImage, int normalRoughnessFormat,
                       long viewZImage, int viewZFormat,
                       long diffInImage, int diffInFormat,
                       long specInImage, int specInFormat,
                       long diffOutImage, int diffOutFormat,
                       long specOutImage, int specOutFormat,
                       long validationImage, int validationFormat) {
        try {
            return (int) this.denoise.invokeExact(cmd,
                    mvImage, mvFormat, normalRoughnessImage, normalRoughnessFormat,
                    viewZImage, viewZFormat, diffInImage, diffInFormat,
                    specInImage, specInFormat, diffOutImage, diffOutFormat,
                    specOutImage, specOutFormat, validationImage, validationFormat);
        } catch (Throwable t) {
            throw new RuntimeException("nrdshim_denoise failed", t);
        }
    }

    public void destroy() {
        try {
            this.destroy.invokeExact();
        } catch (Throwable t) {
            throw new RuntimeException("nrdshim_destroy failed", t);
        }
    }

    public int lastResult() {
        try {
            return (int) this.lastResult.invokeExact();
        } catch (Throwable t) {
            throw new RuntimeException("nrdshim_last_result failed", t);
        }
    }
}
