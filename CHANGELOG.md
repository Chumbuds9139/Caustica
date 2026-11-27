# Changelog

## Unreleased

### New

- **Recommended settings for your GPU.** A new **Apply Recommended Settings** button at the top of the
  ray-tracing settings picks settings for your GPU and screen. A new install applies them by itself a few
  seconds into the first world and shows a notice. It waits that long so the driver can say whether DLSS
  Ray Reconstruction and Frame Generation run on the machine. An existing config is never changed unless
  you press the button.
  - GeForce RTX 40 and 50 cards get the settings tested on a laptop RTX 4050 at 1080p:
    - DLSS Ray Reconstruction with preset F, Frame Generation and Reflex;
    - SHaRC, volumetric clouds and fog.

    That ran at about 25 real frames a second, shown as about 50.
  - The DLSS mode is chosen so that the GPU traces about as many pixels, for its speed, as that laptop
    does at 1080p Performance.
    - Speed is estimated from the number of streaming multiprocessors the driver reports. A desktop card
      counts a little faster than a laptop card.
    - The larger of the window and its monitor counts as the resolution.
    - Ultra Performance is picked only at 1440p and above.
    - Examples: a laptop RTX 4050 gets Performance at 1080p and Ultra Performance at 2560×1600. A desktop
      RTX 4090 gets Quality at 1440p.
  - RTX 20 and 30 cards get DLSS with SHaRC and Reflex, but no Frame Generation, volumetric clouds or fog.
  - Other GPUs keep their upscaler and get SHaRC.
  - It never turns on Shader Execution Reordering.

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
  tracing does not use, so the effect did nothing. It now does two things. It lets auto exposure brighten
  a dark scene further, so moonlight and torchlight come up with their shadows intact. It also adds a dim
  fill light, sized to the current exposure, so it stays faint next to moonlight but still shows a dark
  corridor beside torches and a cave with no light at all. The fill is shaded per face like vanilla (tops
  brightest, undersides darkest) and darker in corners, at the foot of walls and under overhangs, like
  vanilla's smooth lighting. It uses the dimension's night-vision colour, and the effect flickers out over
  its last ten seconds like vanilla. Conduit Power does the same underwater.
- **A camera inside blocks sees into caves again**, in spectator mode and with a free-camera mod in
  any game mode. Vanilla hides the back faces of blocks, which is what lets a camera inside stone see
  the caves around it. Caustica drew every face from both sides, so the view showed the back of each
  cave's nearest wall instead. While the camera is inside a solid block, camera rays now pass through the
  back faces of blocks and fluids as in vanilla. Distant Horizons/Voxy terrain is not changed yet.
- **DLSS Frame Generation recovers from black generated frames by itself.** Every generated frame
  could come out black, flickering against the real ones until frame generation was switched off and
  on. In the one log that shows it, the only unusual event just before was a hit the hit guards had to
  drop (`[HitGuard]` in the log), though a later one in the same session caused nothing. As a
  precaution, frame generation now restarts after any such frame, the way switching it off and on does. It also pauses while resources reload (F3+T) and starts over with a fresh DLSS-G
  feature afterwards. Whatever the cause, it also compares a small sample of each generated frame with
  its real frame. If the generated frames stay black for about half a second, Caustica restarts frame
  generation. If they turn black again soon after, it creates a fresh feature. If that fails too, it
  shows each real frame twice until frame generation restarts. The log records every step, and the
  `[FG present-rate]` line now includes the sampled brightness. The DLSS-FG present-mode line now shows
  the mode the swapchain really uses (FIFO) rather than the one Minecraft asked for.

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
