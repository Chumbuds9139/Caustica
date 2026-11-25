# Changelog

## Unreleased

### Fixes

- **Enchanted armour is visible again.** Minecraft 26.3 draws enchanted armour (without a trim), enchanted
  tridents and enchanted shields without a banner in one pass that combines the item's texture with the
  glint. Caustica took that whole pass for the glint alone, so the armour showed only as a faint,
  moving shimmer. It now draws the armour, trident or shield itself, and the glint on top of it.
- **Invisible item frames and armour stands show what is on them.** Caustica left out every invisible
  entity, so an invisible item frame hid its item or map and an invisible armour stand hid its armour
  and held items. Like vanilla, invisibility now hides only the frame or the body. This also brings back
  armour and held items on invisible players and mobs, and the name tags of invisible armour stands that
  servers use for floating text.
- **Light blocks give light.** The invisible light block (`minecraft:light`) has no model, and Caustica's
  lights come from glowing surfaces, so a placed light block lit nothing (in hand it already worked).
  Each light block now adds a small invisible light at its centre, as bright and as warm as the same
  block held in hand, scaled by its level. It needs emitter sampling on (the default), and a light block
  in a 16×16×16 section that contains nothing else does not light yet.
- **Hurt mobs flash red again**, and a creeper about to explode flashes white. The colour is mixed into
  the mob's surface the way vanilla mixes it, before lighting, so the flash is lit by the scene like the
  rest of the mob. Armour does not flash, as in vanilla.
- **Night vision works.** Vanilla applies night vision by raising the floor of its lightmap, which ray
  tracing does not use, so the effect did nothing. It now adds a dim, even fill light, shaded per face
  like vanilla (tops brightest, undersides darkest), using the dimension's night-vision colour. Caves
  and nights become visible while daylight barely changes, and the effect flickers out over its last
  ten seconds like vanilla. Conduit Power gives the same light underwater.
- **Spectators inside blocks see into caves again.** Vanilla hides the back faces of blocks, which is
  what lets a spectator inside stone see the caves around them. Caustica drew every face from both
  sides, so the view showed the back of each cave's nearest wall instead. While a spectator's eye is
  inside a solid block, camera rays now pass through the back faces of blocks as in vanilla. Water and
  lava are left as they were, and Distant Horizons/Voxy terrain is not changed yet.

## 0.4.2 — 2026-11-22

### Downloads

- **Smaller JARs.** Besides the full JAR (about 104 MB), each release now has two smaller ones:
  - `-windows-lite` (about 26 MB): Windows only, without Intel XeSS, whose runtime is more than half of
    the full JAR. DLSS, FSR and NRD are all still included. Pick this one unless you use XeSS.
  - `-linux` (about 22 MB): Linux only.

  An upscaler that is left out simply does not appear in the options.

### Performance

- **The world ray-tracing pipeline is compiled in the background at the title screen.** After an update
  the driver has to turn the new shaders into GPU code once, which took about 30 s on a laptop RTX 4050.
  That used to freeze the first frame in a world; it now runs while you are in the menu and the world is
  loading, and the world picks up the finished pipeline. If you join before it is done, the game waits
  only for the part that is left.

### Stability

- **The SER guard also holds after a pause in rendering.** When rendering resumes after the window was
  out of focus (Pause When Unfocused), or after any other 3 s or more without a world frame, the indirect
  pass runs without reordering for 30 s, longer while the distant terrain rebuilds. A device loss came
  within four frames of such a resume. This only costs the reordering speed-up for that time.

## 0.4.1 — 2026-11-20

### Performance

- **Three speed-ups from the F8 performance test are now video options, on by default.** On a laptop
  RTX 4050 at four bounces with volumetric clouds and fog, together they took the ray-traced frame from
  about 61 ms to 38 ms. Each can be turned off.
  - **Early Path Termination**: Russian roulette from the first bounce at four or more bounces. It is
    unbiased, but multi-bounce light gets a little noisier.
  - **Simplified Sky in Rough Reflections**: after a diffuse or rough bounce, reflections use the cheaper
    sky, clouds and fog. Water and glass reflections keep full quality.
  - **Fast Volumetric Clouds**: the full-quality cloud march takes at most 24 samples instead of 44, and
    3 light samples instead of 5.
- **Sky Cache** (video option, on by default): the sky seen by bounced light and rough reflections is
  computed once per frame into a small direction map, instead of for every pixel and bounce.
- **Half-Resolution Camera Clouds** (video option, off by default): volumetric clouds you look at directly
  are computed once per 2×2 pixel block and blended over frames.
  - Moving the camera does not smear them.
  - They are only computed where the previous frame showed sky, so the option costs nothing in caves or
    indoors.
  - It saves about 0.3–2 ms with open sky.
- The primary pass no longer computes the sky and clouds for sky pixels. The second pass computes them for
  those pixels anyway.
- The world ray-tracing pipeline is compiled on several threads at once, which shortens the first world
  load after an update.

### Stability

- **Shader Execution Reordering (SER) guard.** When SER is on, the indirect pass switches to a variant
  without reordering while SER is most likely to fail:
  - for 30 s after a world change, and until the distant terrain has finished rebuilding;
  - while a large distant-terrain refresh streams in;
  - for two minutes after a refused hit.

  The indirect pass also checks each hit before and after reordering, and drops a hit that changed.
- **Acceleration structures**:
  - The TLAS check now requires every instance to point at a live BLAS by its exact address, which
    catches a BLAS that was destroyed too early.
  - Build scratch memory is borrowed from a pool instead of being allocated and freed for every build.

### Fixes

- **DLSS Frame Generation**: generated frames no longer turn black after a world change.
  - Frame generation now resets on camera jumps and after loading screens.
  - It starts over in every new world, including one entered from the main menu.
- While SER is held back, the indirect pass's GPU time is now included in the frame statistics.

### Performance test (F8)

- Each row is compared with baselines measured just before and just after it, and the first baseline
  settles for longer.
- Rows measured while SER was held back are marked.
- Vanilla's 30 FPS idle limit no longer caps the test.
- New probe rows split the cost of the clouds by the kind of ray that sees them.
