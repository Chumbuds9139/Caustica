# Caustica — Minecraft 26.3

Hardware-ray-traced renderer for Minecraft's Vulkan backend. It replaces the vanilla world view
with path tracing and the vendor upscaling/denoising stack (DLSS Ray Reconstruction, DLSS Frame
Generation, FSR 3, XeSS, SVGF) while keeping Minecraft's UI and gameplay intact.

![Caustica ray-traced Minecraft scene](docs/gallery/banana.png)

## Features

- Vulkan hardware path-traced world rendering
- DLSS Ray Reconstruction, DLSS Frame Generation, FSR 3, XeSS and NRD/SVGF denoising, with a live
  Ray Reconstruction model-preset slider (Default / D / E / F)
- Blue-noise (void-and-cluster) dithering for the path tracer's per-vertex sampling decisions
- HDR output, SDR/HDR presentation paths and exposure control
- Dynamic entity rendering in the ray-traced scene
- Opt-in ray-traced first-person viewmodel: the hand and held item move into the traced scene, with POM,
  emission and scene lighting on held geometry, while staying shadow-free and above the blocks it swings
  through (`hand.ray-traced`, off by default)
- LabPBR-style material support, including toggleable subsurface scattering
- Deep settings UI: nearly every renderer feature has its own sub-screen with a "Reset to
  Defaults" button, plus a global one on the hub
- Weather-driven lighting: rain and thunderstorms dim the sun/moon and darken the sky
- Volumetric 3D clouds (classic vanilla-style or photoreal cumulus), volumetric fog with
  per-light god rays, water waves, parallax occlusion mapping
- Dedicated Nether, End and portal skyboxes
- OMM (Opacity Micro-Map) + SER (Shader Execution Reordering) optimizations
- Experimental SHaRC-style world-space radiance cache (toggleable, inspectable via debug view 13)

## Requirements

- **Vulkan graphics backend enabled**
- A GPU and driver with Vulkan ray tracing support
- NVIDIA RTX GPU and a supported driver for DLSS features
- An HDR-capable display and OS HDR mode for HDR output
- On Linux, an HDR-capable Wayland compositor and a native Wayland session for HDR output
- Install a LabPBR resource pack such as [SPBR](https://modrinth.com/resourcepack/spbr)

## Installation

1. Install Fabric Loader **0.19.5** for Minecraft **26.3**.
2. Install **Fabric API 0.161.0+26.3**.
3. Put the Caustica JAR in your Minecraft `mods` folder.
4. Launch the game with the Vulkan graphics backend.

## Building

Requires **JDK 25** and the SPIR-V shader toolchain (`glslangValidator`, `slangc`, `spirv-val`) —
either from the Vulkan SDK (`VULKAN_SDK`) or on `PATH`. Gradle **9.7.0**, Loom **1.18.2**
(no-remap mode: 26.3 ships official names), Loader **0.19.5** and Fabric API **0.161.0+26.3** are
pinned in `gradle.properties`.

```sh
./gradlew --no-daemon build            # JAR + SPIR-V shaders
./gradlew --no-daemon -p buildSrc test # ABI verifier and hook-contract tests
./gradlew --no-daemon check verifyMinecraftAbi verifyModJar
./gradlew runClient --args="--graphicsBackend VULKAN"
```

Vendor upscaler runtimes (NGX/DLSS, FidelityFX, NRD, XeSS) are **not committed**. They are built
from the official SDKs in CI into `build/native/<shim>/release/` and bundled from there; without
them the build still succeeds and the corresponding option is simply not offered at runtime.


## Compatibility

Caustica takes over the world renderer, so mods that heavily modify world rendering, shader
pipelines, post-processing or the Vulkan backend may conflict. UI-only mods are more likely to work.

| Mod | Status |
| --- | --- |
| **Sodium** 0.9.2 / 0.9.3 | Caustica appears as its own entry in Sodium's settings screen: a page that opens the full Ray Tracing hub, plus native pages for the renderer's options. |
| **Distant Horizons** 3.3.x | DH's LOD meshes are captured and ray traced beyond the loaded chunks. Caustica drives DH's upload queue itself while the vanilla world pass is replaced. |
| **Voxy** 0.2.20 (unmodified) | Voxy vetoes the Vulkan backend and only ships an OpenGL renderer. Caustica keeps Vulkan, leaves that renderer off, and ray traces the LOD voxels Voxy still ingests and stores. |

Details, limits and the relevant config keys are in
[docs/compat-sodium-voxy-dh.md](docs/compat-sodium-voxy-dh.md).

## Stability

Minecraft's Vulkan backend crashes when a frame's GPU work is not finished within five seconds
("5s timeout reached when waiting for VK semaphore" / "GPU timeout attempting to acquire next
frame"). A path tracer can exceed that while the GPU is perfectly healthy, so Caustica waits up to
`stability.gpu-timeout-seconds` (default 60) instead, logging what it is waiting for. A genuinely lost
device is still reported immediately.

A GPU that Windows actually resets (TDR) cannot be recovered, so Caustica also keeps frames from
getting that long in the first place: the TDR guard starts tracing at minimum cost, ramps up to the
configured samples/bounces only while the GPU keeps up, and sheds load when it falls behind
(`stability.tdr-guard`, on by default).

## License

Caustica's project-owned source code and documentation are licensed under the
**GNU Lesser General Public License v3.0 or later**; see [LICENSE.md](LICENSE.md), [COPYING](COPYING)
and [COPYING.LESSER](COPYING.LESSER).

Release artifacts may bundle NVIDIA DLSS/NGX SDK components under NVIDIA's own license terms; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
