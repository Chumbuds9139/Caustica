# Sodium, Voxy and Distant Horizons

What Caustica does when these mods are installed, what was verified, and what the limits are.
Versions checked against: Sodium 0.9.2 / 0.9.3-beta.4, Voxy 0.2.20-beta, Distant Horizons 3.3.4, all
for Minecraft 26.3.

None of the three is required. Nothing from them is bundled, and no class of theirs is referenced
unless the mod is present.

## GPU timeout ("5s timeout" / device loss)

Vanilla's `VulkanCommandEncoder.submit()` waits at most five seconds for the submission two frames
back and throws `5s timeout reached when waiting for VK semaphore`; `VulkanGpuSurface.acquireNextTexture()`
does the same for the swapchain (`GPU timeout attempting to acquire next frame`). Both end the game.

For a path tracer the GPU can be alive and simply slow for longer than that: video memory
oversubscription, a driver compiling a ray-tracing pipeline, a large BVH refresh, the window being
occluded. `RtGpuWatchdog` therefore repeats the same vanilla wait in five-second slices:

* each slice still runs the real Vulkan call, so `VK_ERROR_DEVICE_LOST` surfaces on the next slice
  through the normal crash path with the `VK_EXT_device_fault` report;
* every slice that expires logs what Caustica has in flight and the VMA heap budgets — a heap marked
  "over budget" means video memory is oversubscribed and the render scale, samples per pixel or LOD
  distance should come down;
* after `stability.gpu-timeout-seconds` (default 60, 5–600; 5 restores vanilla behaviour) the vanilla
  exception is thrown exactly as before.

This removes the crash for a slow-but-alive GPU. It cannot revive a device the driver or the OS has
actually reset (for example a Windows TDR); that case is still fatal, as in vanilla.

## Sodium

Sodium replaces the vanilla Video Settings screen, which is where the "Ray Tracing Settings..." button
lives. Caustica registers a `sodium:config_api_user` entrypoint (`compat/sodium/CausticaSodiumConfig`),
so it shows up in Sodium's mod list with:

* **Ray Tracing Settings...** — opens the complete hub: every sub-screen, the per-page and global
  "Reset to Defaults", frame generation, SHaRC, and the Distant Horizons / Voxy tools.
* **Image & Upscaling / Display / World / Advanced** — native Sodium pages built from the same option
  rows the hub uses, so a change made there runs the same listener (config write, rebuild request).

Notes:

* Native pages follow Sodium's model: changes take effect when you press **Apply**.
* Sodium's "reset" restores the value the game started with. Factory defaults are the hub's
  "Reset to Defaults" buttons, which know each setting's real default and unit.
* Rows that depend on another choice (the per-upscaler quality rows, cloud coverage) are listed as
  they were when the game finished loading; use the hub for the screens that rearrange themselves.
* Sodium treats an exception during registration as fatal, so every row and page is built defensively
  and an unrepresentable row is skipped with a log line instead.

## Distant Horizons

DH's LOD vertex buffers are captured as they are built (`DistantHorizonsLodBufferMixin`) and turned
into BLASes by `RtDistantHorizonsTerrain`.

DH drains its render-thread task queue (buffer creation, upload, disposal) from a hook inside
`LevelRenderer`'s frame graph. While the path tracer owns the image that frame graph is cancelled, so
the queue was only serviced by DH's once-per-second fallback timer with a half-second budget: LOD
streaming crawled and every tick was a visible hitch. Caustica now calls
`RenderThreadTaskHandler.runRenderThreadTasks()` itself on every frame in which the vanilla world
pass is skipped.

## Voxy

Unmodified Voxy cannot run next to a Vulkan renderer for two reasons, both handled:

1. It wraps `GpuBackend.createDevice` and throws `Voxy does not presently support vulkan`, which makes
   Minecraft fall back to OpenGL — where Caustica is inactive. `MinecraftVoxyBackendMixin` wraps the same
   call from outside and, for the Vulkan backend only, calls the backend directly.
2. Its client initialisation and its renderer are OpenGL. On Vulkan, `VoxyClientMixin` replaces the
   initialisation with the one step the data engine needs (registering the instance factory) and
   `VoxyConfigMixin` answers `isRenderingEnabled()` with `false`, so Voxy's render system is never
   constructed. Voxy's saved settings are not modified.

Voxy's storage and ingest engine keeps working: loaded chunks are voxelised and persisted as usual.
`VoxyNativeBridge` reads that data (reflection only), selects a gap-free quadtree of cells around the
camera — 1-block voxels out to 256 blocks, one level coarser per doubling of distance — meshes each
cell on a worker thread with `VoxyLodMesher`, and hands the result to the same LOD proxy DH uses.

Config (`config/caustica.toml`, section `stability`):

| Key | Default | Meaning |
| --- | --- | --- |
| `voxy-vulkan-bridge` | `true` | Keep the Vulkan backend when Voxy is installed. `false` restores Voxy's veto (the game then runs on OpenGL without ray tracing). Needs a restart. |
| `voxy-lods` | `true` | Ray trace Voxy LODs (the "Voxy LODs" toggle in the RT settings). |
| `voxy-lod-distance-chunks` | `96` | LOD distance for ray tracing, 32–512. Separate from Voxy's own render distance on purpose. |

Limits:

* Voxy's own image features (its fog, SSAO, Iris integration) belong to its OpenGL renderer and are not
  used; the LODs are lit by the path tracer instead.
* LOD blocks are flat-coloured (map colour plus biome tint for grass, foliage and water) and classified
  into a handful of material classes. Blocks that do not fill most of a cell (plants, torches, fences,
  panes, carpets) are omitted.
* When both Voxy and Distant Horizons are installed, Voxy is preferred while it has data and DH is the
  fallback, as before.
* A Voxy build that exposes the older `CausticaBridge` class keeps using that path unchanged.

## What has and has not been verified

Verified without a GPU: the mod compiles against 26.3 and passes its test suite and ABI contract; the
Sodium registration sequence was executed against Sodium 0.9.3's real implementation classes; the Voxy
bridge was run against a real Voxy 0.2.20 `WorldEngine` (API resolution, voxel layout, block
classification, section reference counting, change notifications); and the whole mixin set was applied
by Fabric's Knot loader with Sodium 0.9.3-beta.4, Voxy 0.2.20 and Distant Horizons 3.3.4 installed
together, confirming every injection lands and that Caustica's `createDevice` wrapper sits outside
Voxy's.

Not verified: anything that needs a running game on ray-tracing hardware — the image, frame pacing,
memory use at large LOD distances, and behaviour with other mods installed alongside.
