// Flat C ABI shim over NVIDIA NRD (Neural Radiance Denoisers) for the Caustica RT renderer.
//
// NRD is a C++/NRI library; Java's FFM can only bind a clean flat-C ABI, so this DLL wraps the
// REBLUR_DIFFUSE_SPECULAR denoiser behind ~8 primitive-argument functions (same trade-off as
// ngx_shim and fsr_shim). The heavy lifting is delegated to NRD's own Integration layer
// (NRDIntegration.hpp), which owns the NRI pipelines/descriptors and records the denoiser passes
// straight into a wrapped native command buffer.
//
// Resource contract with the renderer (all textures at render resolution, GENERAL layout — the
// layout Caustica keeps every image in, which is exactly what NRI's Layout::GENERAL describes):
//   IN_MV                    rg16f   non-jittered screen-space motion, previous - current, render px
//   IN_NORMAL_ROUGHNESS      rgba16f world normal xyz + LINEAR roughness in w (the NRD library is
//                                    built with NRD_NORMAL_ENCODING=4 / NRD_ROUGHNESS_ENCODING=1,
//                                    whose unpack is exactly xyz + w — the renderer's existing guide
//                                    format needs no repack)
//   IN_VIEWZ                 r32f    linear view depth, primary hit (sky = large = INF for NRD)
//   IN_DIFF_RADIANCE_HITDIST rgba16f YCoCg diffuse radiance + REBLUR-normalized hit distance
//   IN_SPEC_RADIANCE_HITDIST rgba16f YCoCg specular radiance + REBLUR-normalized hit distance
//   OUT_DIFF_RADIANCE_HITDIST rgba16f denoised (renderer combines both into the upscale source)
//   OUT_SPEC_RADIANCE_HITDIST rgba16f denoised

#include <vulkan/vulkan.h>

#include "NRI.h"
#include "Extensions/NRIHelper.h"
// NRIWrapperVK.h's AccelerationStructureVKDesc references nri::AccelerationStructureBits, which
// lives in NRIRayTracing.h — a header NRIWrapperVK.h does not include itself (it expects the
// includer to have pulled it in), so it must come first.
#include "Extensions/NRIRayTracing.h"
#include "Extensions/NRIWrapperVK.h"

#include "NRD.h"
#include "NRDIntegration.h"
// Header-only implementation of the Integration layer (method bodies live in the .hpp).
#include "NRDIntegration.hpp"

#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <vector>

// FSRSHIM-style verbose tracing (NRDSHIM_VERBOSE=1) — the shim runs inside the JVM via FFM, so a
// fault here surfaces as a bare crash on the Java side; trace entry/exit to localize it.
static const bool g_verbose = std::getenv("NRDSHIM_VERBOSE") != nullptr;
#define NRD_SHIM_LOG(...)                                  \
    do {                                                   \
        if (g_verbose) {                                   \
            std::fprintf(stderr, "[nrd_shim] " __VA_ARGS__); \
            std::fprintf(stderr, "\n");                    \
            std::fflush(stderr);                           \
        }                                                  \
    } while (0)

static nrd::Integration* g_integration = nullptr;
static nri::DeviceCreationVKDesc g_deviceDesc = {};
static std::vector<nri::QueueFamilyVKDesc> g_queueFamilies;
static uint32_t g_width = 0;
static uint32_t g_height = 0;
static int g_lastResult = 0;
// One stable denoiser id for the single REBLUR_DIFFUSE_SPECULAR instance.
static const nrd::Identifier DENOISER_ID = 0;

// Last REBLUR tuning Java sent. RecreateVK (resize) rebuilds the instance with NRD defaults, so the
// stored values are re-applied at the end of every nrdshim_init. Zeros mean "keep NRD's default".
static int g_hitDistReconstructionMode = 0; // nrd::HitDistanceReconstructionMode
static int g_maxAccumulatedFrameNum = 0;
static int g_maxFastAccumulatedFrameNum = 0;

static void applyStoredReblurSettings() {
    if (!g_integration) {
        return;
    }
    nrd::ReblurSettings settings = {}; // NRD defaults via member initializers
    settings.hitDistanceReconstructionMode = (nrd::HitDistanceReconstructionMode) g_hitDistReconstructionMode;
    if (g_maxAccumulatedFrameNum > 0) {
        settings.maxAccumulatedFrameNum = (uint32_t) g_maxAccumulatedFrameNum;
    }
    if (g_maxFastAccumulatedFrameNum > 0) {
        settings.maxFastAccumulatedFrameNum = (uint32_t) g_maxFastAccumulatedFrameNum;
    }
    nrd::Result result = g_integration->SetDenoiserSettings(DENOISER_ID, &settings);
    if (result != nrd::Result::SUCCESS) {
        std::fprintf(stderr, "[nrd_shim] SetDenoiserSettings failed: %d\n", (int) result);
        std::fflush(stderr);
    }
}

extern "C" {

#if defined(_WIN32)
#define NRD_SHIM_EXPORT __declspec(dllexport)
#else
#define NRD_SHIM_EXPORT __attribute__((visibility("default")))
#endif

NRD_SHIM_EXPORT int nrdshim_last_result() {
    return g_lastResult;
}

// Wraps the renderer's existing Vulkan device into NRI and (re)creates the NRD integration for the
// given render resolution. queueFamilyIndex is the graphics queue family; vkMinorVersion is the
// Vulkan 1.x minor (NRD requires >= 2). Also used for resizes: RecreateVK destroys and rebuilds.
NRD_SHIM_EXPORT int nrdshim_init(unsigned long long vkInstance, unsigned long long vkPhysicalDevice,
                                 unsigned long long vkDevice, unsigned long long vkGetInstanceProcAddr,
                                 unsigned int queueFamilyIndex, unsigned int vkMinorVersion,
                                 unsigned int width, unsigned int height) {
    NRD_SHIM_LOG("init: instance=%p phys=%p device=%p queueFamily=%u vk1.%u size=%ux%u",
            (void*) vkInstance, (void*) vkPhysicalDevice, (void*) vkDevice,
            queueFamilyIndex, vkMinorVersion, width, height);
    if (g_integration) {
        g_integration->Destroy();
        delete g_integration;
        g_integration = nullptr;
    }

    const nrd::LibraryDesc& libraryDesc = *nrd::GetLibraryDesc();
    NRD_SHIM_LOG("NRD library v%u.%u.%u, %u denoisers, normalEncoding=%u roughnessEncoding=%u",
            libraryDesc.versionMajor, libraryDesc.versionMinor, libraryDesc.versionBuild,
            libraryDesc.supportedDenoisersNum,
            (unsigned) libraryDesc.normalEncoding, (unsigned) libraryDesc.roughnessEncoding);

    g_queueFamilies.clear();
    g_queueFamilies.push_back({1, nri::QueueType::GRAPHICS, queueFamilyIndex});

    g_deviceDesc = {};
    g_deviceDesc.vkInstance = (VKHandle) vkInstance;
    g_deviceDesc.vkDevice = (VKHandle) vkDevice;
    g_deviceDesc.vkPhysicalDevice = (VKHandle) vkPhysicalDevice;
    g_deviceDesc.queueFamilies = g_queueFamilies.data();
    g_deviceDesc.queueFamilyNum = (uint32_t) g_queueFamilies.size();
    g_deviceDesc.minorVersion = (uint8_t) vkMinorVersion;
    // NRI remaps SPIR-V descriptor bindings using the same offsets NRD's shaders were compiled with;
    // the Integration layer reads the same values for its pipeline layout, so both sides agree.
    g_deviceDesc.vkBindingOffsets.sRegister = libraryDesc.spirvBindingOffsets.samplerOffset;
    g_deviceDesc.vkBindingOffsets.tRegister = libraryDesc.spirvBindingOffsets.textureOffset;
    g_deviceDesc.vkBindingOffsets.bRegister = libraryDesc.spirvBindingOffsets.constantBufferOffset;
    g_deviceDesc.vkBindingOffsets.uRegister = libraryDesc.spirvBindingOffsets.storageTextureAndBufferOffset;

    nrd::IntegrationCreationDesc integrationDesc = {};
    std::snprintf(integrationDesc.name, sizeof(integrationDesc.name), "Caustica REBLUR");
    integrationDesc.resourceWidth = (uint16_t) width;
    integrationDesc.resourceHeight = (uint16_t) height;
    // Caustica records the next frame while the previous may still execute (descriptor ring), so
    // keep NRD's per-frame descriptor pools separated across the in-flight window.
    integrationDesc.queuedFrameNum = 3;
    // Per-Denoise descriptor caching only: the renderer owns every input/output image and recreates
    // them on resize (a full RecreateVK happens then), so lifetime-wide caching would just pin
    // descriptors against images that are about to be destroyed.
    integrationDesc.enableWholeLifetimeDescriptorCaching = false;
    // Barriers + synchronization are coordinated by the renderer around the denoise call; the shim
    // never submits anything itself.
    integrationDesc.autoWaitForIdle = false;

    nrd::InstanceCreationDesc instanceDesc = {};
    nrd::DenoiserDesc denoisers[] = {{DENOISER_ID, nrd::Denoiser::REBLUR_DIFFUSE_SPECULAR}};
    instanceDesc.denoisers = denoisers;
    instanceDesc.denoisersNum = 1;

    g_integration = new nrd::Integration();
    nrd::Result result = g_integration->RecreateVK(integrationDesc, instanceDesc, g_deviceDesc);
    g_lastResult = (int) result;
    if (result != nrd::Result::SUCCESS) {
        std::fprintf(stderr, "[nrd_shim] RecreateVK failed: %d\n", (int) result);
        std::fflush(stderr);
        g_integration->Destroy();
        delete g_integration;
        g_integration = nullptr;
        return (int) result;
    }

    g_width = width;
    g_height = height;
    // A resize rebuilds the instance with NRD defaults; re-apply whatever tuning Java last sent.
    applyStoredReblurSettings();
    NRD_SHIM_LOG("init: OK (memory %.1f Mb)", g_integration->GetTotalMemoryUsageInMb());
    return 0;
}

// Stores + applies REBLUR tuning (see applyStoredReblurSettings). Safe before init: the values are
// remembered and applied when nrdshim_init creates the instance. hitDistReconstructionMode uses the
// nrd::HitDistanceReconstructionMode values (0 OFF, 1 AREA_3X3, 2 AREA_5X5); the frame counts accept
// 0 = keep NRD default.
NRD_SHIM_EXPORT int nrdshim_set_reblur_settings(int hitDistReconstructionMode,
                                                int maxAccumulatedFrameNum,
                                                int maxFastAccumulatedFrameNum) {
    g_hitDistReconstructionMode = hitDistReconstructionMode;
    g_maxAccumulatedFrameNum = maxAccumulatedFrameNum;
    g_maxFastAccumulatedFrameNum = maxFastAccumulatedFrameNum;
    applyStoredReblurSettings();
    return 0;
}

// Frame start: advances NRD's internal frame index (constant-buffer ring slot).
NRD_SHIM_EXPORT void nrdshim_new_frame() {
    if (g_integration) {
        g_integration->NewFrame();
    }
}

// Per-frame common settings. Matrices are column-major float[16], non-jittered: the level
// projection with Vulkan's NDC y-flip undone (NRD assumes y-up NDC) and the unmodified
// rotation x translate worldToView (Minecraft's view space is already the +Z-forward LH space
// NRD expects — it converts RH inputs itself). Jitter is in PIXEL units (NRD's [-0.5, 0.5]
// contract); motion vector scale converts the renderer's render-pixel MVs into the UV space
// NRD expects.
NRD_SHIM_EXPORT int nrdshim_set_settings(const float* viewToClip, const float* viewToClipPrev,
                                         const float* worldToView, const float* worldToViewPrev,
                                         float jitterX, float jitterY,
                                         float jitterPrevX, float jitterPrevY,
                                         float mvScaleX, float mvScaleY,
                                         unsigned int frameIndex, int reset, int enableValidation) {
    if (!g_integration) {
        return -1;
    }
    nrd::CommonSettings settings = {};
    std::memcpy(settings.viewToClipMatrix, viewToClip, 16 * sizeof(float));
    std::memcpy(settings.viewToClipMatrixPrev, viewToClipPrev, 16 * sizeof(float));
    std::memcpy(settings.worldToViewMatrix, worldToView, 16 * sizeof(float));
    std::memcpy(settings.worldToViewMatrixPrev, worldToViewPrev, 16 * sizeof(float));
    settings.motionVectorScale[0] = mvScaleX;
    settings.motionVectorScale[1] = mvScaleY;
    settings.motionVectorScale[2] = 0.0f; // 2D screen-space motion
    settings.cameraJitter[0] = jitterX;
    settings.cameraJitter[1] = jitterY;
    settings.cameraJitterPrev[0] = jitterPrevX;
    settings.cameraJitterPrev[1] = jitterPrevY;
    settings.resourceSize[0] = (uint16_t) g_width;
    settings.resourceSize[1] = (uint16_t) g_height;
    settings.resourceSizePrev[0] = (uint16_t) g_width;
    settings.resourceSizePrev[1] = (uint16_t) g_height;
    settings.rectSize[0] = (uint16_t) g_width;
    settings.rectSize[1] = (uint16_t) g_height;
    settings.rectSizePrev[0] = (uint16_t) g_width;
    settings.rectSizePrev[1] = (uint16_t) g_height;
    settings.viewZScale = 1.0f;
    // The renderer writes sky viewZ at 1e6; keep the validity cutoff below that.
    settings.denoisingRange = 500000.0f;
    settings.frameIndex = frameIndex;
    settings.accumulationMode = reset ? nrd::AccumulationMode::CLEAR_AND_RESTART
                                      : nrd::AccumulationMode::CONTINUE;
    settings.isMotionVectorInWorldSpace = false;
    settings.enableValidation = enableValidation != 0;
    nrd::Result result = g_integration->SetCommonSettings(settings);
    g_lastResult = (int) result;
    return (int) result;
}

static nrd::Resource makeResource(unsigned long long image, int vkFormat) {
    nrd::Resource resource = {};
    resource.vk.image = (VKNonDispatchableHandle) image;
    resource.vk.format = (VKEnum) vkFormat;
    // The renderer leaves every image in GENERAL layout after the trace pass (storage writes); NRD
    // inserts its own transitions from this declared state and, with restoreInitialState, hands
    // them back in the same state.
    resource.state.access = nri::AccessBits::SHADER_RESOURCE_STORAGE;
    resource.state.layout = nri::Layout::GENERAL;
    resource.state.stages = nri::StageBits::COMPUTE_SHADER;
    return resource;
}

// Records the REBLUR_DIFFUSE_SPECULAR denoise into the renderer's (currently recording) command
// buffer. All seven textures are render-resolution; formats are the raw VkFormat enums. When
// validationImage != 0 it is bound as OUT_VALIDATION and REBLUR renders its 16-viewport diagnostic
// overlay into it (requires enableValidation in the common settings).
NRD_SHIM_EXPORT int nrdshim_denoise(unsigned long long cmd,
                                    unsigned long long mvImage, int mvFormat,
                                    unsigned long long normalRoughnessImage, int normalRoughnessFormat,
                                    unsigned long long viewZImage, int viewZFormat,
                                    unsigned long long diffInImage, int diffInFormat,
                                    unsigned long long specInImage, int specInFormat,
                                    unsigned long long diffOutImage, int diffOutFormat,
                                    unsigned long long specOutImage, int specOutFormat,
                                    unsigned long long validationImage, int validationFormat) {
    if (!g_integration) {
        return -1;
    }

    nrd::ResourceSnapshot snapshot;
    // Restore GENERAL after the denoise so the renderer's subsequent barriers stay valid.
    snapshot.restoreInitialState = true;
    nrd::Resource mvResource = makeResource(mvImage, mvFormat);
    nrd::Resource normalResource = makeResource(normalRoughnessImage, normalRoughnessFormat);
    nrd::Resource viewZResource = makeResource(viewZImage, viewZFormat);
    nrd::Resource diffInResource = makeResource(diffInImage, diffInFormat);
    nrd::Resource specInResource = makeResource(specInImage, specInFormat);
    nrd::Resource diffOutResource = makeResource(diffOutImage, diffOutFormat);
    nrd::Resource specOutResource = makeResource(specOutImage, specOutFormat);
    snapshot.SetResource(nrd::ResourceType::IN_MV, mvResource);
    snapshot.SetResource(nrd::ResourceType::IN_NORMAL_ROUGHNESS, normalResource);
    snapshot.SetResource(nrd::ResourceType::IN_VIEWZ, viewZResource);
    snapshot.SetResource(nrd::ResourceType::IN_DIFF_RADIANCE_HITDIST, diffInResource);
    snapshot.SetResource(nrd::ResourceType::IN_SPEC_RADIANCE_HITDIST, specInResource);
    snapshot.SetResource(nrd::ResourceType::OUT_DIFF_RADIANCE_HITDIST, diffOutResource);
    snapshot.SetResource(nrd::ResourceType::OUT_SPEC_RADIANCE_HITDIST, specOutResource);
    if (validationImage != 0) {
        nrd::Resource validationResource = makeResource(validationImage, validationFormat);
        snapshot.SetResource(nrd::ResourceType::OUT_VALIDATION, validationResource);
    }

    nri::CommandBufferVKDesc cmdDesc = {};
    cmdDesc.vkCommandBuffer = (VKHandle) cmd;
    cmdDesc.queueType = nri::QueueType::GRAPHICS;

    nrd::Identifier denoisers[] = {DENOISER_ID};
    g_integration->DenoiseVK(denoisers, 1, cmdDesc, snapshot);
    return 0;
}

NRD_SHIM_EXPORT void nrdshim_destroy() {
    if (g_integration) {
        g_integration->Destroy();
        delete g_integration;
        g_integration = nullptr;
    }
    g_width = 0;
    g_height = 0;
}

} // extern "C"
