# Fork changes

Target: Minecraft 26.2 Fabric Caustica (`dev.comfyfluffy.caustica`).

## 1. Chinese UI

- Completed `src/main/resources/assets/caustica/lang/zh_cn.json`
- Completed `src/main/resources/assets/caustica/lang/zh_tw.json`
- Added new keys in `en_us.json` for the GPU-safety controls

Missing fog strings and leftover English labels (tonemap, POM, Voxy, DH, lighting intensity, settings title) are now translated.

## 2. Sodium settings integration

Sodium 0.9.x for 26.2 replaces vanilla Video Settings, so `VideoSettingsScreenMixin` never runs when Sodium is present.

- `compat/SodiumConfigEntry.java` implements Sodium `ConfigEntryPoint`
- Fabric entrypoint `sodium:config_api_user`
- `build.gradle` adds CaffeineMC Maven + `modCompileOnly net.caffeinemc:sodium-fabric-api:0.9.1+mc26.2`
- `fabric.mod.json` `suggests.sodium`

The Sodium page is an *external page* that opens Caustica's existing `RtVideoOptionsScreen` / `RtSharcOptionsScreen`. No second settings backend.

## 3. 5s timeout + VK Device lost

Crash signature from vanilla:

```
IllegalStateException: 5s timeout reached when waiting for VK semaphore
  at com.mojang.blaze3d.vulkan.VulkanCommandEncoder.submit
```

That wait is Minecraft's own 5-second cap. A long path-trace (first-frame AS build, volumetric clouds, high SPP) trips it. On Windows the OS TDR (~2s by default) can reset the GPU first and surface as `VK_ERROR_DEVICE_LOST`.

Changes:

- `rt/GpuWatchdog.java` — degrade window after slow composites / timeouts / device lost
- `RtComposite` reads SPP / bounces / volumetrics through the watchdog
- `WorldRenderScaler` times each composite
- `VulkanCommandEncoderMixin` stretches the 5s wait (default 15s, setting 5–60) and recovers a semaphore timeout by `vkDeviceWaitIdle` + degrade instead of an immediate crash
- `VulkanUtilsMixin` reports device-lost into the watchdog
- Debug settings: **GPU Timeout Guard** + **Submit Wait Timeout**

This cannot disable Windows TDR. If device-lost persists on Windows, raise `TdrDelay` in the registry *or* keep quality low (SPP 1, classic clouds, fog off, DLSS Performance).

## How to build

Needs JDK 25, Fabric Loom, Vulkan SDK (`glslangValidator`, `slangc`, `spirv-val`).

```bash
./gradlew build
```

Optional JVM args (already documented upstream):

```
-Xss2M
-XX:+UseZGC
```

## What I could not verify in this environment

- Full Gradle compile (no Minecraft 26.2 mappings / Vulkan SDK in the sandbox)
- In-game Sodium page click-through
- Exact vanilla constant used for the 5s wait (mixin tries both `5_000_000_000L` ns and `5000L` ms; unused constants are `require = 0`)
- WrapMethod on `submit` if Mojang added arguments in a later 26.2 snapshot — check the mixin apply log on first launch
