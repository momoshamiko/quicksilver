# Quicksilver (`mod/mcms-metal`)

A native Metal backend for Minecraft's renderpearl layer (Minecraft 26.3, Fabric, Java 25), cut from
[mcopt](https://github.com/noahdunnagan/mcopt) v0.3.0-alpha.2 (Apache-2.0), with its own engine for OptiFine and Iris shader
packs (ADR 0006). Where Metal can run (see "Where Metal runs"), it puts Metal first in the game's backend list, with OpenGL and
Vulkan (MoltenVK) behind it as fallbacks. Mixins make the seams around it work: Sodium draws through its Vulkan multi-draw path
on Metal, Sodium's geometry arenas release the debug-map texture each one registers when Sodium deletes them, and Distant
Horizons takes the paths it takes on Vulkan.

Source: this is the folder `mod/mcms-metal` of the MC Metal Shaders development repository, which is private. Its source
is public at https://github.com/momoshamiko/quicksilver, one snapshot per released jar. Paths below that start with
`docs/`, `bench/`, `results/` or `vendor/`, and the `RIL-` issue numbers, point into that repository and its tracker.

`.github/workflows/build.yml` builds each release jar on GitHub Actions from tag `v<version>`. The Modrinth file is
that release asset, and its `SHA256SUMS` gives the jar's hash (the packed `natives/libmcmsmetal.dylib` hash sits in
`libmcmsmetal.dylib.sha256`).

Names: the public name is Quicksilver (chosen 2026-10-16). Players see it as the mod id `quicksilver`, the shown
name "Quicksilver", the jar `quicksilver-<version>.jar`, the driver string in the game's log ("Using graphics backend Metal,
using drivers: Metal (Quicksilver 0.2.0, from mcopt 0.3.0-alpha.2)") and the MSL cache folder `<game dir>/quicksilver/`.
The project's internal name stays where players choose nothing: this folder, the package `dev.mcms.metal`, the
`-Dmcms.metal.*` properties, the mixin config `mcms-metal.mixins.json`, the native library `libmcmsmetal.dylib`, and the
`mcms-metal` tag at the start of the backend's log lines and error messages (bench scripts such as `bench/soak_check.py`
and `bench/texlog_report.py` read those lines). `fabric.mod.json` lists the old mod id `mcms-metal` under `breaks`, so
Fabric Loader refuses to start an old build and a new one together.

Icon: the words QUICK SILVER in white pixel letters on a black square, drawn in Illustrator on 2026-10-20. It replaces the
chrome QS square of 2026-10-17, whose Q was hard to read. The jar ships `src/main/resources/assets/quicksilver/icon.png`
(128x128, named by `fabric.mod.json` `icon` for Mod Menu; Sodium's Video Settings shows it on Quicksilver's page), and
`art/icon-512.png` (512x512) is a larger copy. Both are scaled down with a box filter from a 1822x1822 export of the
Illustrator file, which stays outside the repo: the export's two partly covered edge columns are cut off and one black
column is added, so the letters sit in the middle. The square is opaque, so it looks the same on any background.

## Build

Needs macOS on Apple silicon, JDK 25 and Xcode's command line tools (`clang`):

```
./gradlew build
```

In the development repository: `cd mod/mcms-metal && JAVA_HOME=$PWD/../../.tools/jdk-25/Contents/Home ./gradlew build`.
Gradle fetches the mixin targets (Sodium 0.9.3-beta.1, Distant Horizons 3.3.4) from Modrinth's Maven by version id; they
are compile-only and do not ship in the jar. The jar (`build/libs/quicksilver-0.2.0.jar`) packs `natives/libmcmsmetal.dylib`
(built by `clang` from `src/main/native/mcmetal.m` and `mcshaders.m`), `LICENSE` and `NOTICE`.

## Settings

With Sodium installed, Quicksilver has a page in Options, Video Settings (ADR 0002,
`docs/decisions/0002-settings-page.md`; `QuicksilverSodiumPage`, through Sodium's config API, entrypoint
`sodium:config_api_user`):

| Row | Values | What it does | Takes effect |
| --- | --- | --- | --- |
| Use Metal | On, Off | `-Dmcms.metal` | after a restart |
| Colors | Automatic, Vivid, Accurate | `-Dmcms.metal.colorspace` unset, `p3`, `srgb` | at once: the live layer is re-tagged (`mc_layer_colorspace`) |
| Metal HUD | Off, On | Apple's Metal Performance HUD: `MTL_HUD_ENABLED=1` at launch, then the layer's `developerHUDProperties` (`mc_layer_hud`, logging off) | turning it on: after a restart, unless the HUD was enabled at launch; then at once both ways |

Colors and Metal HUD are grey when the game does not run on Metal. While Use Metal is on, an overlay greys out Sodium's own
Graphics API row (`sodium:general.graphics_api`): Metal goes first whatever it says (`PreferredGraphicsApiMixin`). Where Metal
cannot run (next section), Use Metal is grey too, its tooltip names the reason, and the overlay is left out, so Sodium's row
chooses the backend.

The page saves `<game dir>/config/quicksilver.properties` (`metal`, `colors`, `hud`) on Apply; a launch never creates it. The
preLaunch entrypoint `QuicksilverSettings` reads it before Minecraft's classes load: it sets each system property the launch
command did not set, logs one `Quicksilver settings:` line per value it applies (or per launch flag that wins over the file),
and sets `MTL_HUD_ENABLED=1` with libc `setenv` (never overwriting a launcher's value) when the HUD is on and the game will draw
with Metal: Use Metal on and Metal possible here. Otherwise it logs that the variable stays unset, because MoltenVK presents
through a Metal layer too and would likely show Apple's HUD on Vulkan [inference, not seen]. A launch flag wins:
its row shows the flag's value, grey, with the flag named in the tooltip (`-Dmcms.metal.colorspace=none` shows as Accurate,
which an untagged layer looks like). Without a `hud` key the HUD setting follows the launcher (`MTL_HUD_ENABLED=1` keeps it on),
and a launcher value other than `1` holds the row grey. The benchmark's instances have no such file, so their `-D` configs run
as before.

### Shaders (the engine's screens)

Quicksilver's own shader engine (ADR 0006; it draws only on Metal) adds a "Shaders"
group to the page, two screens and three keys (`docs/engine/plan.md` E8):

| Where | What it does |
| --- | --- |
| Use shaders (page row) | on, off; at once. Grey under `-Dmcms.shaders` or on another backend; the tooltip says why |
| Shader packs (page button) | opens the pack screen |
| Upscaling (page row) | Off (the default), Quality, Performance (ADR 0008): the pack's world draws at the window size divided by 1.5 or 2, and MetalFX's spatial scaler makes the pack's final image full size (the engine's bilinear copy where MetalFX refuses, with one WARN). At once, without a reload. The block atlas and its normal and specular maps take a matching LOD bias. Grey under `-Dmcms.shaders.upscale` |
| Pack screen (`PackScreen`) | the zips and folders in `<game dir>/shaderpacks/` and "Shaders off"; the chosen pack's state (in use, loading, failed with the reason); Apply, Options, Open folder, Done. A launch flag locks the list and Apply, and a header line names it |
| Options screen (`OptionsScreen`) | the pack's own screens, built from its `shaders.properties` and lang files; Hand depth (0.0625, 0.125, 0.25) on the main screen; Reset, Cancel, Apply. Apply writes `shaderpacks/<pack>.txt` and reloads the pack when it is the one in use |
| Keys (Controls, category "Quicksilver shaders") | K toggles the shaders, R reloads the pack, U opens the pack screen; rebindable, saved in `options.txt` |

The file gains `shaders` (on, off), `shaderPack` (a file name in `shaderpacks/`), `handDepth` and `upscale` (off, quality, performance). The page's Apply, the pack
screen's Apply, the options screen's Apply and the toggle key all save it the same way, and each save writes every key. The
shader keys set no system property: `-Dmcms.shaders` and `-Dmcms.shaders.pack` win when the launch command sets them, and the
file's values apply otherwise, read at every frame, so a change applies without a restart. The pack's options file keeps the
names the pack does not declare when the screen writes it.

Under water (RIL-571): `fabric.mod.json` turns off Sodium's mixin rule `mixin.features.render.world.sky` through Sodium's
mod overrides (`custom`, `sodium:options`). That mixin makes the game skip its sky pass whenever the camera is in a fluid.
Iris 1.11.7 turns the same rule off (Sodium's log at start: "added by mods [iris]"), so a pack's sky and stars show from
under water; without a pack, the game draws its sky under water as it does without Sodium. While a pack draws the world,
`SodiumEngineFogCullMixin` also turns Sodium's fog occlusion off, so terrain past the game's fog end (the water fog, and
also the Nether's fog, a boss fog, lava and blindness, not measured) stays drawn, as Iris draws a pack's far terrain
under water.

## Where Metal runs

Minecraft 26.3 tries the next backend in its list only when one throws a `BackendCreationException` (`Minecraft.<init>`, javap
of the client jar); any other throwable ends the start. So Quicksilver offers Metal only where it can run (`MetalSupport`,
decided once at preLaunch): macOS 15 or newer (`os.version`) on Apple silicon (`os.arch` `aarch64`), because
`libmcmsmetal.dylib` is built for arm64 and macOS 15, and not while Iris is installed (in either of its modes), because Iris
draws only through OpenGL: Iris 1.11.7 picks its mode from `options.txt` alone and casts the device's backend to OpenGL's (`IrisRenderSystem.getGlDevice`). Elsewhere
`PreferredGraphicsApiMixin` leaves the list as it is, the game starts as without Quicksilver, and one line says why
(`Quicksilver: Metal stays off, so the game starts with its own graphics backends: ...`). Where Metal is offered and the device
still cannot be made, for example when the library does not load, `MetalBackend.createDevice` turns the error into a
`BackendCreationException`, so the game logs "Failed to create backend Metal" and tries OpenGL and Vulkan (RIL-497).

## Properties (system properties, `-D`)

| Property | Default | What it does |
| --- | --- | --- |
| `mcms.metal` | `true` | `false` opts out of the whole backend (OpenGL/Vulkan as vanilla picks). The settings page's Use Metal row sets it through `config/quicksilver.properties` (Settings above); a launch flag wins |
| `mcms.shaders` | unset (the file's `shaders`, off without one) | `on` or `off`: the shader engine draws the chosen pack. Wins over the file; the page's row and the toggle key then show it, locked |
| `mcms.shaders.pack` | unset (the file's `shaderPack`) | the pack's file name in `<game dir>/shaderpacks/`. Wins over the file; the pack screen then shows it, locked |
| `mcms.shaders.upscale` | unset (the file's `upscale`, off without one) | `off`, `quality` or `performance` (ADR 0008), read every frame: wins over the file while it names a value (the bench harness sets it per scene); the page's row then shows it, locked |
| `mcms.shaders.upscaler` | `metalfx` | profiling only (ADR 0008): `bilinear` upscales with the engine's copy draw instead of MetalFX, `refuse` makes MetalFX refuse as an unsupported device does (the fallback's own run) |
| `mcms.shaders.upscaleFactor` | unset | profiling only (ADR 0008's equal-look comparison): a scale above 1 and at most 2 replaces the Upscaling setting's while the setting is not Off |
| `mcms.shaders.standardRoad`, `mcms.shaders.mathMode` | off, `relaxed` | profiling only (E9): `standardRoad=true` sends every full-screen program down the standard road (glslang and SPIRV-Cross); `mathMode=fast` or `safe` compiles the packs' libraries in that math mode instead of relaxed |
| `mcms.shaders.loadOutputs`, `mcms.shaders.keepDeadTargets` | off, off | profiling only (E9): `loadOutputs=true` makes every full-screen pass load the targets it overwrites (no "don't care" loads); `keepDeadTargets=true` runs every pass and stores every output, the targets no program reads too |
| `mcms.metal.fence` | `true` | a fence made right after a submit names that submit, so a uniform-ring slot frees a frame sooner |
| `mcms.metal.pace` | `true` | with vsync off, present only the last frame that makes each refresh |
| `mcms.metal.paceAdapt` | `true` | the pacer's margin learns the compositor's latch from scanout times |
| `mcms.metal.earlyCommit` | `true` | with VSync on, the frame's work goes to the GPU before the render thread waits for a drawable (new in mcms-metal); GPU times read from a frame's last command buffer (`trace` average, `gpuTimes`, `jfr`) then cover only the present |
| `mcms.metal.vsyncPace` | `true` | with VSync on, the render thread waits for the display's next refresh before it fetches the drawable, so each frame takes one refresh; a late frame presents at once, also when the frames are slower than the refresh. The grid comes from a display link on the display that shows the game's window (checked about once a second and moved when the window changes display, RIL-476; through M5 it was the main display; not tested with two displays), with the game's own presents' `presentedTime` when the link falls silent (not on displays that report none, nor with `presentQueue`). After a stretch without a grid, frames queue ahead of the display; a late frame after a long drawable wait then waits two refreshes, which drains them without a repeated refresh (M5, `results/2026-10-13-m5-vsync-screen`, `-m5-vsync-recover`, `-m5-vsync-lead`) |
| `mcms.metal.vsyncPaceOffsetMs` | `2` | how long after the refresh that wait ends: the drawable a frame needs often comes back only after the refresh |
| `mcms.metal.memLog` | off | seconds between `mcms-metal mem:` lines (RSS, physical footprint, Metal `currentAllocatedSize`, failed command buffers so far, and `objects=`: the Metal objects of each kind that still exist, count/bytes, from the backend's allocation functions) for spec S4 (`bench/soak_check.py`) |
| `mcms.metal.textureLog` | `false` | a `mcms-metal texture:` line for each texture created (handle, size, format, usage, label, the first three callers outside the backend and the renderpearl API) and closed (handle; handles are reused, so match them in log order): who keeps textures alive (spec S4, `results/2026-10-13-m5-texlog`) |
| `mcms.metal.texelCache` | `true` | texel-buffer views kept across frames |
| `mcms.metal.mslCache` | `true` | translated MSL kept on disk under `<game dir>/quicksilver/msl-cache` (`mcms.metal.mslCacheDir` overrides the directory; before 2026-10-16 the folder was `<game dir>/mcms-metal/msl-cache`) |
| `mcms.metal.arenaReserve` | `268435456` (256 MiB) | one buffer for Sodium's next big geometry arena, provisioned off the render thread; `0` turns it off |
| `mcms.metal.inFlight` | `2` | frames in flight |
| `mcms.metal.presentQueue`, `paceSync`, `paceMarginMs`, `drawables`, `lazyClears`, `frameDepthDiscard`, `cbdiag`, `trace`, `gpuTimes`, `stats`, `waitStats`, `jfr`, `frameLog`, `encLog`, `dumpMsl`, `overrideMsl`, `uncachedMsl`, `gpuCount`, `passTest`, `latency` | off | diagnostics and experiments, unchanged from mcopt, except that the `stats` line and the `gpu` lines of `trace` are printed with `println` (Minecraft's log keeps only `println` output), that `frameLog` with `gpuTimes` adds each present's `presentedTime` (`presentedUs`, M5), and that `encLog` (with `frameLog`; `enclog.csv`, read by `bench/pass_report.py`) keeps the game's pass label of each render encoder and also timestamps the frame's blit encoders, naming the mip chains they fill (after M5; `trace`'s `gpu` lines list those blit encoders too, so their encoder numbers differ from M5's) |
| `mcms.metal.vsyncLowLatency` | off | experiment after M5 (`true` or empty for a 1.5 ms margin, or the margin in ms): with VSync on, the VSync-on wait moves from before the drawable to after the frame's present is committed, before the game polls input for the next frame, and ends at the next refresh minus the second longest wake-to-GPU-done time of the last 32 frames plus the margin, so the next frame is sampled about that lead before it is shown; a frame slower than its lead is shown a refresh late. The `vsyncPaceTest` switches do not act on it |
| `mcms.metal.vsyncPaceTest` (with `vsyncPaceTestAt`, `vsyncPaceTestFor`) | off | test only: mode 1 or 2 takes the VSync-on wait's grid away from `vsyncPaceTestAt` (default 90) for `vsyncPaceTestFor` (default 10) seconds after the first wait (mode 1: no refresh period from the display link; mode 2: no grid at all). Separate properties because Prism's `instance.cfg` cannot hold a comma in `JvmArgs` |
| `mcms.metal.passLoadTest` | off | test only (after M5): a render pass whose label contains the given text opens each colour attachment it does not clear with load action "don't care" instead of "load" (for example `world0/`: a pack's Overworld passes). It measures what loading the old contents costs passes that write every pixel; a pass that leaves pixels unwritten shows garbage there |
| `mcms.metal.colorspace` | `none`, or `p3` when the mod `mac-colorspace-rollback` is loaded | the colour space the layer tags its frames with (RIL-481): `none` (no tag; CAMetalLayer.h: "If nil, no colormatching occurs", yet the window's sRGB then decides, see below), `srgb` (matched from sRGB to the colour space of the layer's context, "typically the display's colorspace"; what MoltenVK asks for) or `p3` (matched from Display P3); any other value throws when the surface is made. Without the property the layer follows `mac-colorspace-rollback`, which gives OpenGL windows and the Vulkan swapchain the Display P3 look and does nothing on Metal (chosen 2026-10-17; the surface then logs "mcms-metal colorspace: the layer is tagged Display P3, because mac-colorspace-rollback is loaded"). Each configure whose answer changes logs `mcms-metal colorspace: layer <name>, window <name>, screen <name>` (the layer's colour space, its window's and that window's screen's). Measured on 2026-10-17 (`results/2026-10-17-colors`): `none` and `srgb` show the same values on the display (matched from sRGB, as OpenGL and Vulkan do without a colour mod), and `p3` shows the frames' values as Display P3. The settings page's Colors row sets it (Automatic: unset, Vivid: `p3`, Accurate: `srgb`) and re-tags the live layer at once; a launch flag wins |

Every frame's command buffers are watched: one the GPU fails is counted, and the render thread logs "N command buffer(s) failed
on the GPU so far" with the last `MTLCommandBufferError` code (the first ten, then each time the count passes a power of two;
M5, spec S4). The opt-in present queue's command buffers are not counted.

Depth comparisons: every depth image the translated MSL samples with a depth comparison (`sampler2DShadow`) gets a constexpr
comparison sampler (linear, clamp to edge, LEQUAL, base level).

Wide sampler tables (M4): a Metal stage has 16 sampler slots (`[[sampler(0)]]` to `[[sampler(15)]]`). Each stage of a
pipeline numbers its samplers without gaps, in binding order, and a texture the stage only fetches from (`texelFetch`,
`textureSize`) gets a constexpr nearest sampler and no slot (`SpirvResources` reads which ones from the SPIR-V;
`MetalPipeline.samplerSlots`, `fetchSamplers`); textures and buffers keep slot = binding. `MetalRenderPass` binds a texture's
sampler at the slot each stage numbered (`mc_r_texture_slots`). A stage that samples more than 16 textures is refused with
a message naming them: argument buffers would lift the limit, and this backend has none. `TRANSLATION_VERSION` 3.

Fragment outputs (2026-10-19, RIL-495): Metal refuses a render pipeline whose fragment function writes fewer colour
components than its target's pixel format has ("fragment shader color output does not have enough components for the
pixel format"); Vulkan leaves the missing components undefined. Photon v1.4 writes `vec3` into RGBA8 and RGBA16F targets.
The translation pads every fragment output to four components (SPIRV-Cross `pad_fragment_output_components`), as MoltenVK
does, so the padded components hold what MoltenVK's do; a target with fewer components keeps only its own.
`TRANSLATION_VERSION` 4. Evidence: `results/2026-10-19-pad-photon` (Photon on Metal, then through Vitrail).

Vitrail, until E10: from M0c to E9, mixins let Vitrail's shader engine run on this backend (its compute passes, storage
images and buffers, mip chains and device report; evidence `results/2026-10-11-m0c-dims`, `results/2026-10-12-m0c-device`
and `results/2026-10-12-m4-*`), with four files adapted from Vitrail under LGPL-3.0-only. E10 removed them all: Quicksilver's
own engine draws the shader packs (`docs/engine/plan.md`), and `fabric.mod.json` breaks Vitrail, so the game does not start
with both.

Sodium's arena textures (RIL-477, applied only when Sodium is loaded): Sodium 0.9.3's `BufferArena` constructor (alpha.1 and beta.1)
registers a 200x200 debug-map `DynamicTexture` in the game's `TextureManager` under a name made from the arena's identity
hash, and nothing in Sodium releases it, so every arena left one behind until shutdown (278,528 bytes of Metal texture plus
160,000 bytes of CPU pixels; three per long teleport in the soak, `results/2026-10-13-m5-texlog`). `SodiumBufferArenaAccessor`
reads that name, and `SodiumSingleOwnerArenaMixin` (`deleteSingleOwner` of a dedicated arena) and `SodiumSharedArenaMixin`
(`deleteShared`) release it from the `TextureManager` where Sodium closes the arena's buffer, which closes the texture and
its pixels. Sodium's own files are not changed. The same Sodium code runs on every backend, so the leak is not Metal's
[inference: measured on Metal only].

Distant Horizons (RIL-483, applied only when DH is loaded; written against DH 3.3.4 for 26.3, the release in the play-test
modpack, `bench/mods.lock.json`): DH's `MinecraftRenderWrapper.getMcRenderingApi()` answers VULKAN only for the backend
name "Vulkan" and OPEN_GL for every other name. On Metal, DH's light-map mixin then casts the light map's texture to the
OpenGL backend's `GlTexture` and throws (`results/2026-10-14-pack-on-metal`, run 1). `DistantHorizonsRenderApiMixin`
makes the name "Metal" count as Vulkan there, so DH hands the light map over as a renderpearl `GpuTexture` and draws
with its Blaze3D engine (the default on 26.3) through renderpearl. On Metal its far terrain looks as it does on Vulkan,
without shaders and with Complementary's DH programs (then through Vitrail, `results/2026-10-14-dh-metal`). DH's OpenGL engine
still cannot run on Metal; DH refuses it at startup, as it does on Vulkan. Only DH 3.3.4 gets the mixin.

## Provenance

Derived from mcopt v0.3.0-alpha.2 by Noah Dunnagan (Apache-2.0); every source file taken from mcopt carries a header naming
its origin and what changed here. The cut removed mcopt's own-terrain, far-terrain, recorder, frame-capture and CPU-profile
hooks (see `docs/research/m0a-gap-list.md`); `MetalHooks`/`PassDelegate` stay in the jar: the shader engine redirects passes
through them, and its draw log reads their encoder events (`docs/engine/plan.md` E4). mcopt's LICENSE and NOTICE ship in the
jar, as Apache-2.0 asks.

## Licences

The mod is Apache-2.0 (`LICENSE`, `NOTICE`). Until the E10 cutover, four files adapted Vitrail's own code to Metal under
LGPL-3.0-only, and the jars of that time carried the GPL-3.0 and LGPL-3.0 texts; the public source at
https://github.com/momoshamiko/quicksilver keeps their history. The mod holds no Sodium code: its Sodium mixins change Sodium
at run time, and players get Sodium (PolyForm Shield 1.0.0) from its own page. The reading behind this is in
`docs/research/m0a-gap-list.md` (M0a-4) and on RIL-487; it is not legal advice.
