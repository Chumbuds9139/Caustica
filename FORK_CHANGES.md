# Fork changes

Target: Minecraft 26.2 Fabric Caustica (`dev.comfyfluffy.caustica`).

## 1. Chinese UI

- Completed `src/main/resources/assets/caustica/lang/zh_cn.json`
- Completed `src/main/resources/assets/caustica/lang/zh_tw.json`
- Added new keys in `en_us.json` for the GPU-safety controls

## 2. Sodium settings integration

Sodium 0.9.x for 26.2 replaces vanilla Video Settings, so `VideoSettingsScreenMixin` never runs when Sodium is present.

- `compat/SodiumConfigEntry.java` implements Sodium `ConfigEntryPoint`
- Fabric entrypoint `sodium:config_api_user`
- Sodium API is wired from `settings.gradle` as `modCompileOnly`
- `fabric.mod.json` `suggests.sodium`

## 3. 5s timeout + VK Device lost — root cause

Vanilla `VulkanCommandEncoder.submit` waits 5 seconds for the command buffer it just submitted.
Caustica records the RT composite into that same encoder (`encoder.execute` in `RtComposite.recordFrame`).
The 5s wait is therefore waiting for:

1. Per-entity / per-particle BLAS builds recorded **inline** on the graphics command buffer
2. A per-frame TLAS rebuild
3. `TraceRays` (SPP × bounces × volumetric cloud/fog marches)
4. DLSS-RR / NRD / FSR + copy onto the main target

Terrain section BLASes already run on Caustica's reserved compute queue and are published only
after that queue completes. They are not what the 5s wait is blocked on.

Windows TDR (~2s) killing that graphics packet surfaces as `VK_ERROR_DEVICE_LOST`.
Stretching the 5s wait does not shrink the packet. Swallowing a timeout and submitting again
is how a hang becomes device-lost.

This fork:

- Skips RT for ~30 frames after join / dimension change / atlas reload
- Then a cheap window: SPP 1, 2 bounces, no volumetric clouds/fog
- Caps new (non-refit) BLAS ops per graphics submit
- If the 5s wait still fires: disable RT and idle the device; do not retry

The durable fix still remaining is to submit the composite on Caustica's own queue and let
vanilla's encoder only blit an already-finished image.

## How to build

Needs JDK 25, Fabric Loom, Vulkan SDK (`glslangValidator`, `slangc`, `spirv-val`).

```bash
./gradlew build
```
