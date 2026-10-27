# Quicksilver (`mod/mcms-metal`)

A native Metal backend for Minecraft's renderpearl layer (Minecraft 26.3, Fabric, Java 25), cut from
[mcopt](https://github.com/noahdunnagan/mcopt) v0.3.0-alpha.2 (Apache-2.0). Where Metal can run (see "Where Metal runs"), it
puts Metal first in the game's backend list, with OpenGL and Vulkan (MoltenVK) behind it as fallbacks. Mixins make the seams around it work:
Sodium draws through its Vulkan multi-draw path on Metal, Vitrail's shader engine runs instead of turning itself
off on a non-Vulkan backend, Vitrail can fill the mip chains of a pack's colour targets on Metal, since M4
Vitrail's compute passes, storage images and storage buffers run on Metal, Sodium's geometry arenas release the
debug-map texture each one registers when Sodium deletes them, and Distant Horizons takes the paths it takes on Vulkan.

Source: this is the folder `mod/mcms-metal` of the MC Metal Shaders development repository, which is private. Its source
is public at https://github.com/momoshamiko/quicksilver, one snapshot per released jar. Paths below that start with
`docs/`, `bench/`, `results/` or `vendor/`, and the `RIL-` issue numbers, point into that repository and its tracker.

Names: the public name is Quicksilver (chosen 2026-10-16). Players see it as the mod id `quicksilver`, the shown
name "Quicksilver", the jar `quicksilver-<version>.jar`, the driver string in the game's log ("Using graphics backend Metal,
using drivers: Metal (Quicksilver 0.1.1, from mcopt 0.3.0-alpha.2)") and the MSL cache folder `<game dir>/quicksilver/`.
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
Gradle fetches the mixin targets (Sodium 0.9.3-beta.1, Vitrail 0.13.0-beta, Distant Horizons 3.3.4) from Modrinth's
Maven by version id; they are compile-only and do not ship in the jar. The jar (`build/libs/quicksilver-0.1.1.jar`) packs
`natives/libmcmsmetal.dylib` (built by `clang` from `src/main/native/mcmetal.m`), `LICENSE`, `NOTICE`, `LICENSE-GPL-3.0`
and `LICENSE-LGPL-3.0`.

## Settings

With Sodium installed, Quicksilver has a page in Options, Video Settings, next to Vitrail's (ADR 0002,
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
| `mcms.metal.passLoadTest` | off | test only (after M5): a render pass whose label contains the given text opens each colour attachment it does not clear with load action "don't care" instead of "load" (for example `world0/`: Vitrail's Overworld deferred, composite and final programs). It measures what loading the old contents costs passes that write every pixel; a pass that leaves pixels unwritten shows garbage there |
| `mcms.metal.colorspace` | `none`, or `p3` when the mod `mac-colorspace-rollback` is loaded | the colour space the layer tags its frames with (RIL-481): `none` (no tag; CAMetalLayer.h: "If nil, no colormatching occurs", yet the window's sRGB then decides, see below), `srgb` (matched from sRGB to the colour space of the layer's context, "typically the display's colorspace"; what MoltenVK asks for) or `p3` (matched from Display P3); any other value throws when the surface is made. Without the property the layer follows `mac-colorspace-rollback`, which gives OpenGL windows and the Vulkan swapchain the Display P3 look and does nothing on Metal (chosen 2026-10-17; the surface then logs "mcms-metal colorspace: the layer is tagged Display P3, because mac-colorspace-rollback is loaded"). Each configure whose answer changes logs `mcms-metal colorspace: layer <name>, window <name>, screen <name>` (the layer's colour space, its window's and that window's screen's). Measured on 2026-10-17 (`results/2026-10-17-colors`): `none` and `srgb` show the same values on the display (matched from sRGB, as OpenGL and Vulkan do without a colour mod), and `p3` shows the frames' values as Display P3. The settings page's Colors row sets it (Automatic: unset, Vivid: `p3`, Accurate: `srgb`) and re-tags the live layer at once; a launch flag wins |

Every frame's command buffers are watched: one the GPU fails is counted, and the render thread logs "N command buffer(s) failed
on the GPU so far" with the last `MTLCommandBufferError` code (the first ten, then each time the count passes a power of two;
M5, spec S4). The opt-in present queue's command buffers are not counted.

Shader packs: every depth image the translated MSL samples with a depth comparison (`sampler2DShadow`) gets a constexpr
comparison sampler (linear, clamp to edge, LEQUAL, base level), the same one Vitrail binds on Vulkan.

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
`TRANSLATION_VERSION` 4. Evidence: `results/2026-10-19-pad-photon` (Photon draws on Metal; Complementary's and BSL's
translated sources differ from the old build only in Vitrail's one-channel `ofCoverage` output).

Compute and storage (M4): a pack's compute module becomes an MSL kernel and a compute pipeline (`MetalCompute`: one set,
binding i at buffer and texture slot i, samplers numbered as above, the local size from the SPIR-V), dispatched on the
frame's compute encoder (`mcmetal.m`: opened by the first dispatch or clear, ended by any other encoder or the commit).
Storage images are 1D, 2D or 3D private textures with shader read and write (`MetalStorage.newImage`), cleared by
built-in zero-filling kernels (Metal clears a texture only as a render pass load action), moved with box copies; storage
buffers are private `MTLBuffer`s zeroed with a blit fill. Metal tracks these resources across encoders and command
buffers on the one queue, which orders what Vitrail orders with Vulkan barriers. A render pass binds a pack's storage
images and buffers by name: the frontend binds a placeholder of the declared kind under the name, and `MetalRenderPass`
binds what `MetalHooks.storage` answers for it instead, as Vitrail's `VulkanRenderPassMixin` does while it pushes
descriptors. A colour target a compute writes as `colorimgN` is created with shader-write usage while Vitrail's
`TextureUsage` asks for it.

The Vitrail seam for compute and storage (M4, applied only when Vitrail is loaded): Vitrail 0.12.0-beta's `PackCompute`,
`ComputeShader`, `StorageImages`, `StorageBuffers` and `GpuRecording` record Vulkan commands and return without a word on
any other device. `VitrailPackComputeMixin`, `VitrailComputePassMixin` and `VitrailComputeShaderMixin` send its compute
roads (the shadow computes at the head of the frame, the computes before a full screen pass or standing alone) to
`dev.vitrail.render.McmsMetalCompute`, which binds the same names in the same order on Metal; `VitrailStorageImagesMixin`
and `VitrailStorageBuffersMixin` send allocation, clears, moves and lookups to `dev.mcms.metal.vitrail.MetalStorageImages`
and `MetalStorageBuffers`, which follow Vitrail's own rules (which volume follows the camera, the clear-at-birth budget,
the log lines); `VitrailGpuRecordingMixin` ends the open pass and defers destruction through this backend's encoder;
`VitrailGpuFormatsMixin` answers which formats a shader can write (every colour format). `McmsMetalCompute` lives in
Vitrail's package because the classes it calls are package-private there. Vitrail's own files are not changed.
Evidence: `results/2026-10-12-m4-compute` (a self-checking test pack), `-m4-profiles`, `-m4-pictures-vh`, `-m4-pictures-cl`.

Mip chains: before a pack program reads a colour target at a lod, Vitrail asks the encoder to fill that target's
mip chain through its `MipmapCommands` interface, which it adds to Vulkan's encoder with a mixin.
`VitrailMipmapCommandsMixin` adds it to this backend's encoder (only when Vitrail is loaded), and
`MetalEncoder.generateMipmaps` fills the chain with Metal's `generateMipmapsForTexture` (2x2 linear reduction). Depth
and integer formats answer false, and Vitrail then keeps reading them at the base level, as before. Without the
chain, BSL's exposure read one pixel and its End came out darker on Metal (`results/2026-10-11-m0c-dims`).

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
without shaders and with Complementary's DH programs through Vitrail (`results/2026-10-14-dh-metal`). DH's OpenGL engine
still cannot run on Metal; DH refuses it at startup, as it does on Vulkan. Only DH 3.3.4 gets the mixin.

Device report: Vitrail asks the device its packs' features depend on in `VulkanBackendMixin.vitrail$deviceFeatures`,
which runs only around Mojang's Vulkan device creation; unserved, `BufferBlending.served=false` refuses packs requiring
`PER_BUFFER_BLENDING` and folds per-buffer `blend.*` directives into one whole-program function.
`VitrailDeviceReportMixin` (applied only when Vitrail is loaded) serves the answers for an Apple GPU under native
Metal at this backend's device creation, before any pack is read, and logs one INFO line
(`mcms-metal device report on Metal: ...`). One rule: where an answer comes from SPIRV-Cross or from the Apple GPU
itself, it is the answer MoltenVK gives on this Mac, because the road to Metal runs through the same translator; where
an answer would route Vitrail into Vulkan-only machinery, it is refused. The answers: per-target blending yes (Metal's
per-attachment `MTLRenderPipelineColorAttachmentDescriptor`, filled per target from renderpearl's `ColorTargetState` in
`MetalPipeline.describe` and `mcmetal.m`'s `mc_pipeline_new`); geometry stage no (Metal has none); wide sampler sets
off (a Vulkan push-descriptor road that would call `vkUpdateDescriptorSets`); the three vendor GLSL extensions MoltenVK
exposes on this Mac present, every other one absent; subgroup operations absent from the vertex, geometry and
tessellation-evaluation stages (SPIRV-Cross converts simdgroup operations in fragment and compute only, the answer
MoltenVK gives here); the float packing rewrite on (the bug it dodges was SPIRV-Cross's MSL for those builtins, and
SPIRV-Cross is this backend's translator too); oversize `shared` declarations moved into a buffer yes (Metal caps
threadgroup memory; `McmsMetalCompute` applies Vitrail's move since M4). Evidence and the test packs that prove the
blending answer: `results/2026-10-12-m0c-device`.

## Provenance

Derived from mcopt v0.3.0-alpha.2 by Noah Dunnagan (Apache-2.0); every source file taken from mcopt carries a header naming
its origin and what changed here. The cut removed mcopt's own-terrain, far-terrain, recorder, frame-capture and CPU-profile
hooks (see `docs/research/m0a-gap-list.md`); `MetalHooks`/`PassDelegate` stay in the jar: the pass hooks are inert, and the
storage seams of `MetalHooks` are installed by the Vitrail mixins since M4. mcopt's LICENSE
and NOTICE ship in the jar, as Apache-2.0 asks.

## Licences

The mod is Apache-2.0 (`LICENSE`, `NOTICE`), with one exception: four files adapt Vitrail's own code to Metal, say so in
their header and are LGPL-3.0-only, Vitrail's licence: `dev/vitrail/render/McmsMetalCompute.java`,
`dev/mcms/metal/vitrail/MetalStorageImages.java`, `dev/mcms/metal/vitrail/MetalStorageBuffers.java` and
`dev/mcms/metal/mixin/VitrailComputeShaderMixin.java`. They ship in this jar (decided 2026-10-20). As LGPL-3.0 §4 asks,
the jar carries the GPL-3.0 and LGPL-3.0 texts (`LICENSE-GPL-3.0` and `LICENSE-LGPL-3.0`, verbatim from gnu.org; the LGPL
text is byte-identical to Vitrail's `LICENSE`), `NOTICE` says that Vitrail is used and is covered by the LGPL-3.0, and
the source of every released jar is public at https://github.com/momoshamiko/quicksilver, so anyone can change those
files and build the jar again. The mod holds no Sodium code: its Sodium mixins change Sodium at run time, and players get
Sodium (PolyForm Shield 1.0.0) from its own page. The reading behind this is in `docs/research/m0a-gap-list.md` (M0a-4)
and on RIL-487; it is not legal advice.
