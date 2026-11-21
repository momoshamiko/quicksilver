// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.mcms.metal.MetalBridge;
import dev.mcms.glsl.msl.ProgramRequest.UniformBlock;
import dev.mcms.metal.distant.DistantHorizonsDriver;
import dev.mcms.metal.MetalHooks;
import dev.mcms.metal.QuicksilverSettings;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.pack.DimensionPrograms;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.ResolvedProgram;
import dev.mcms.shaders.pack.ShaderProperties;
import dev.mcms.shaders.pack.StageFile;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.compute.ComputeKernel;
import dev.mcms.shaders.runtime.distant.DistantCallGate;
import dev.mcms.shaders.runtime.distant.DistantFrame;
import dev.mcms.shaders.runtime.distant.DistantProjection;
import dev.mcms.shaders.runtime.distant.DistantHorizonsRuntime;
import dev.mcms.shaders.runtime.distant.DistantUniforms;
import dev.mcms.shaders.runtime.distant.FrontendPassFlag;
import dev.mcms.shaders.runtime.gbuffers.EngineRedirector;
import dev.mcms.shaders.runtime.gbuffers.GbufferDelegate;
import dev.mcms.shaders.runtime.gbuffers.DrawLog;
import dev.mcms.shaders.runtime.gbuffers.GbufferPhase;
import dev.mcms.shaders.runtime.gbuffers.GbufferPlans;
import dev.mcms.shaders.runtime.gbuffers.Horizon;
import dev.mcms.shaders.runtime.gbuffers.PhaseTargets;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import dev.mcms.shaders.runtime.uniforms.FrameInputs;
import dev.mcms.shaders.runtime.shadow.ShadowCamera;
import dev.mcms.shaders.runtime.shadow.ShadowTargets;
import dev.mcms.shaders.runtime.shadow.ShadowFrame;
import dev.mcms.shaders.runtime.shadow.ShadowEntityDispatch;
import dev.mcms.shaders.runtime.shadow.ShadowPassOps;
import dev.mcms.shaders.runtime.shadow.ShadowPassColours;
import dev.mcms.shaders.runtime.shadow.ShadowComposites;
import dev.mcms.shaders.runtime.shadow.ShadowFrameDriver;
import dev.mcms.shaders.runtime.shadow.ShadowLists;
import dev.mcms.shaders.runtime.shadow.ShadowPassSequence;
import dev.mcms.shaders.runtime.uniforms.FrameUniforms;
import dev.mcms.shaders.runtime.blockentity.BlockEntityRouter;
import dev.mcms.shaders.runtime.hand.HandRenderer;
import dev.mcms.shaders.runtime.terrain.SodiumAdapter;
import dev.mcms.shaders.runtime.terrain.TerrainEngine;
import dev.mcms.shaders.runtime.buffers.StorageBuffers;
import dev.mcms.shaders.runtime.textures.AtlasSizes;
import dev.mcms.shaders.runtime.textures.CustomTextureSet;
import dev.mcms.shaders.runtime.textures.GameResources;
import dev.mcms.shaders.pack.textures.TextureImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import org.jspecify.annotations.Nullable;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The engine's lifecycle (ADR 0006 section 3; plan.md E3): off, loading (the pack read, its programs translated and
 * compiled in the background while the vanilla picture shows), active, failed (vanilla picture, one ERROR line; the
 * toast comes with E8). The engine runs only when the game draws with our Metal backend and a pack is selected and
 * switched on (since E10 Vitrail cannot load beside it). A generation counter drops every background load a newer
 * request replaced (pack switch, engine off, resource reload); those releases also free the targets and pipelines.
 *
 * <p>The two frame hooks (the {@code EngineGameRendererMixin}): frame start, right after {@code GameRenderer.render}'s
 * resize call, where the frame uniforms are captured and updated and the main target's size is read; and right after
 * {@code renderLevel}, where the passes run (see {@link EngineFrame}). While a set loads the game draws its vanilla
 * picture, after a dimension change too; once a set is active the other dimensions' sets compile in the background,
 * so a later change switches at once.
 */
public final class Engine {

	private static final Logger LOG = LoggerFactory.getLogger("Quicksilver");
	private static final Engine INSTANCE = new Engine();

	/** The engine's own copy program (the world copy when colortex0's format differs from the main target's). */
	private static final String COPY_MSL = """
		#include <metal_stdlib>
		using namespace metal;

		// Quicksilver: original code (ADR 0006), Apache-2.0. A full-screen triangle from the vertex id that samples the
		// main colour into the target, upright: the y flip matches the translated programs' vertex epilogue
		// (ProgramEmitter.vertexResult: p.y = -p.y), so between the two OpenGL-row-order textures the copy keeps the
		// picture the right way up (Metal's framebuffer origin is the top-left; without the flip the draw reverses the
		// row order, which turned Complementary's world copy upside down, its colortex0Format differing from the main
		// colour's format).
		struct QsCopyOut {
			float4 position [[position]];
			float2 uv;
		};

		vertex QsCopyOut qs_copy_vs(uint vid [[vertex_id]]) {
			float2 corner = float2(float((vid << 1u) & 2u), float(vid & 2u));
			QsCopyOut out;
			float2 position = corner * 2.0f - 1.0f;
			out.position = float4(position.x, -position.y, 0.0f, 1.0f);
			out.uv = corner;
			return out;
		}

		fragment float4 qs_copy_fs(QsCopyOut in [[stage_in]], texture2d<float> source [[texture(0)]]) {
		constexpr sampler qs(filter::linear, address::clamp_to_edge, coord::normalized);
			return source.sample(qs, in.uv);
		}
		""";

	public enum State { OFF, LOADING, ACTIVE, FAILED }

	/** No targets (the feedback sets of a frame without a world: nothing is copied, nothing allocated per frame). */
	private static final int[] NO_TARGETS = new int[0];

	private final AtomicLong generation = new AtomicLong();
	private final AtomicReference<Pending> pendingSet = new AtomicReference<>();
	/** A pack the loader read, waiting for the frame that makes it the engine's model ({@link #takePendingModel}; RIL-564). */
	private final AtomicReference<LoadedModel> pendingModel = new AtomicReference<>();
	/**
	 * Finished sets of the dimensions that are not the foreground one, by folder (plan.md E3: switch at once), each with
	 * its generation. Guarded by its own lock: the generation check and the put are one step, and turning off clears it
	 * under the same lock (the review).
	 */
	private final Map<String, Ready> readyByFolder = new HashMap<>();
	/**
	 * The folders with a background load in flight, each with the generation it was requested in (a folder is never
	 * compiled twice at once). A load removes only its own marker, so a late load of an older generation never removes
	 * a newer request's marker for the same folder (D3).
	 */
	final Map<String, Long> loading = new ConcurrentHashMap<>();
	/** Releases a set's pipelines: the production path frees them natively; tests count the releases. */
	private Consumer<DimensionSet> releaser = Engine::releaseSet;
	/** Makes the engine's copy pipeline for a target format (0 when refused); tests replace it to refuse one. */
	private BiFunction<Long, Integer, Long> copyPipelines = Engine::copyPipeline;
	/** Whether the render device a pending set was compiled for still exists (tests replace it: no device in a JVM). */
	private BooleanSupplier deviceReady = () -> backend() != null;
	/** The folder of the set the foreground waits for; its loads land in {@link #pendingSet}, the others in ready. */
	volatile @Nullable String foregroundFolder;
	private volatile State state = State.OFF;
	private volatile @Nullable String packName;
	/**
	 * Why the last load failed, in words the pack screen and the failure toast show (E8), or null. Written before the state
	 * turns FAILED (both volatile), so a reader that sees FAILED sees the reason; cleared when a load starts or the engine
	 * turns off.
	 */
	private volatile @Nullable String failureReason;
	/** Whether this failure's toast was shown (render thread): one toast per failed load. */
	private boolean failureToastShown;
	private @Nullable PackModel model;
	private @Nullable CustomUniforms custom;
	private @Nullable FrameUniforms frameUniforms;
	private @Nullable ByteBuffer frameBytes;
	private @Nullable ByteBuffer customBytes;
	/**
	 * Distant Horizons (plan.md E7): whether DH 3.3.4 draws through the pack in this session (set at load; it sets the DISTANT_HORIZONS
	 * define and the depth targets), the frame state the DH draws take their matrices from, whether DH drew its opaque terrain this
	 * frame, and the scratch matrix of DH's projection.
	 */
	private boolean distantActive;
	private final DistantFrame distant = new DistantFrame();
	private final Matrix4f distantMatrix = new Matrix4f();
	private boolean distantDrawn;
	private boolean distantLogged;
	/** Refuses a DH call that would run inside an open render pass (DistantCallGate); one WARN per session. */
	private final DistantCallGate distantGate = new DistantCallGate();

	/** Whether DH draws through the pack and has set its projection once: its values serve QsDh from then on. */
	private boolean distantRendered() {
		return this.distantActive && this.distant.projected();
	}

	/** The QsDh block's layout (the slot every program reads while DH runs; DistantUniforms). */
	private static final UniformBlock DISTANT_BLOCK = DistantUniforms.block(EngineSlots.DISTANT_HORIZONS);
	private @Nullable DimensionSet active;
	/** The active set's frame path (one per active set, the E3 review; closed with the set's pipelines). */
	private @Nullable EngineFrame frame;
	private final PassBlocks passBlocks = new PassBlocks();
	/** The game's texture atlases (E6-atlassize): the per-draw atlasSize and the block atlas's size, as refreshAtlases read them. */
	private final AtlasSizes atlases = new AtlasSizes();
	private final EngineFrame.Main mainColour = new EngineFrame.Main();
	/** ADR 0008: the game's clear colour for the render-size stand-in, filled per frame (no allocation in the frame path). */
	private final float[] standInClear = new float[4];
	/**
	 * Whether {@link #standInClear} holds a colour the game cleared its main target to: kept for the frames in which a pass
	 * that is not redirected (the game's per-tick lightmap update) wrote the pending clear before the after-level hook.
	 */
	private boolean standInClearKnown;
	/** This frame's encoder object (the BlockUpload and PassBlocks read it; set at the frame's start). */
	private @Nullable Object frameEncoder;
	private @Nullable Targets targets;
	private @Nullable ShadowTargets shadowTargets;
	/** E5: the shadow frame's operations (the production binding), its run and its content switches; set with the set's shadows. */
	private @Nullable ShadowPassOps shadowOps;
	/** E5: the shadow entities' dispatcher (its own submit storage and feature dispatcher); with the set's shadows. */
	private @Nullable ShadowEntityDispatch shadowDispatch;
	/** E5: the shadow frame with its failure rule; set with the set's shadows, dropped for the set on a failed step. */
	private @Nullable ShadowFrameDriver shadowDriver;
	/** E5: the shadow entity dispatch's factory: the game's by default; a test supplies one built without the game. */
	private java.util.function.Function<GbufferDelegate, ShadowEntityDispatch> shadowDispatches =
		d -> new ShadowEntityDispatch(Minecraft.getInstance(), d);
	/** E5 review item 17: whether a set with no shadow pass has cleared its shadow maps (once per set). */
	private boolean shadowsCleared;
	/** E4: the active set's gbuffers plans, delegate and redirector, installed and uninstalled with the set. */
	private @Nullable GbufferPlans plans;
	private @Nullable GbufferDelegate delegate;
	/** E4: the horizon draw of the active set (null while no world is drawn); closed with the set's GPU objects. */
	private @Nullable Horizon horizon;
	private @Nullable EngineRedirector redirector;
	/** Whether the takeover serves the world (E3's world copy ends then; the phase hook runs then). */
	private boolean worldDrawn;
	/** Per gbuffers phase (by ordinal) and attachment group: the group's layout in attachment order, and the write handles it hands out. */
	private int[][][] phaseLayouts = new int[GbufferPhase.values().length][][];
	private long[][][] phaseHandles = new long[GbufferPhase.values().length][][];
	/** The main colour view handle of this frame (the redirector matches the game's passes against it). */
	private long mainView;
	/** This frame's solid-hand step (the render pass captured per frame, the runnable reused: no per-frame object). */
	private final SolidHand solidHand = new SolidHand();
	/** DH's transparent layer inside the phase hook (reused: no per-frame object). */
	private final Runnable transparentInPhase = this::drawTransparentInPhase;
	/** Whether the once-per-session line for the in-phase water was logged. */
	private boolean inPhaseWaterLogged;
	/** Whether the in-phase water failed once: later frames then draw it after the level. */
	private boolean inPhaseWaterFailed;
	/** The backend state of the suspended pass, one holder for the engine's life (see drawTransparentInPhase). */
	private final Object passSuspension = MetalBridge.newPassSuspension();
	/** The frontend's in-pass flag as suspended, one holder for the engine's life. */
	private final FrontendPassFlag.Saved frontendFlag = new FrontendPassFlag.Saved();
	private long noiseTexture;
	/** Whether {@link #noiseTexture} is the engine's own (generated) texture rather than the pack's custom noise (E6-B). */
	private boolean noiseOwned;
	/** The pack's custom textures of the installed set (E6-B), or null while none is installed. */
	private @Nullable CustomTextureSet customs;
	/** The pack's shader storage buffers of the installed set (E6-B; screen-sized ones follow the view), or null. */
	private @Nullable StorageBuffers storage;
	/** The engine's default normal and specular textures (1x1): the gbuffers samplers no PBR pack supplies (SamplerSources). */
	private long normalsDefault, specularDefault, lightmapDefault;
	/** The sampler of a program's atlas or lightmap name in a game pipeline that binds none (nearest, clamp to edge). */
	private long untexturedSampler;
	private long copyPipeline;
	/** The copy pipeline of the main colour's format (the no-final copy when the formats differ). */
	private long finalCopyPipeline;
	private @Nullable ExecutorService loader;
	/** How long shutdown waits for a background load to stop (a build stops before its next Metal call: well inside this). */
	private static final long LOADER_STOP_SECONDS = 10;
	/** The targets' size: the render size (ADR 0008), the window's divided by {@link #targetScale}. */
	private int targetWidth = -1;
	private int targetHeight = -1;
	/**
	 * The render scale the targets were made for (ADR 0008): the Upscaling setting's, latched once per frame, while the
	 * active set serves the world; 1 otherwise (E3's sets draw no world of their own, the game draws it at the window size).
	 */
	private float targetScale = 1f;
	/** Whether MetalFX refused this set's upscale: the engine's copy draw upscales instead (one WARN per set). */
	private boolean upscaleFallback;
	/**
	 * Profiling and the fallback's own run (ADR 0008): {@code -Dmcms.shaders.upscaler=bilinear} upscales with the engine's
	 * copy draw, {@code =refuse} makes MetalFX refuse as an unsupported device does; MetalFX's spatial scaler otherwise.
	 */
	private static final String UPSCALER = System.getProperty("mcms.shaders.upscaler", "metalfx");
	/**
	 * Profiling (ADR 0008's equal-look comparison): {@code -Dmcms.shaders.upscaleFactor=<f>}, f above 1 and at most 2, replaces
	 * the Upscaling setting's scale while the setting is not Off; 0 (inert) when unset or out of range.
	 */
	private static final float UPSCALE_FACTOR = upscaleFactor(System.getProperty("mcms.shaders.upscaleFactor"));
	/** Set when the device refused the scaled targets: upscaling stays off for the session (ADR 0008, one WARN). */
	private boolean upscaleRefused;
	/** Whether the spatial scaler was asked for (the release then frees it; device-free paths never load the binding). */
	private boolean spatialUsed;
	private @Nullable String loadedDimension;
	private boolean captureBroken;
	private boolean sawFirstLoad;

	/** The engine for the game is {@link #get()}; tests make their own (the device-free tests and the GPU switch test). */
	Engine() {
	}

	/** A finished background load waiting for the frame, with the request it belongs to. */
	private record Pending(long generation, DimensionSet set) {
	}

	/** A finished set in the store, with the generation it was loaded for (a stored set never outlives its generation). */
	private record Ready(long generation, DimensionSet set) {
	}

	/** A pack the loader read for a request: the model, whether this load serves Distant Horizons, the read's time. */
	private record LoadedModel(long generation, PackModel model, boolean distant, long readMillis) {
	}

	/** The main target as the engine's install and loader need it: the device context, the size and the raw format. */
	record Surface(long ctx, int width, int height, int format) {
	}

	/** The engine singleton; the mixin is its only caller. */
	public static Engine get() {
		return INSTANCE;
	}

	/** Replaces the set release (the GPU switch test counts each set's releases, then frees the pipelines itself). */
	void setReleaser(Consumer<DimensionSet> releaser) {
		this.releaser = releaser;
	}

	/** Replaces the copy-pipeline maker (the install test refuses a pipeline to reach the failure path). */
	void setCopyPipelines(BiFunction<Long, Integer, Long> copyPipelines) {
		this.copyPipelines = copyPipelines;
	}

	/** Replaces the device check of a pending set (the switch test runs without a device; the gate test keeps the default). */
	void setDeviceReadyForTest(BooleanSupplier ready) {
		this.deviceReady = ready;
	}

	/** Restores the default device check: the game's device is a Metal backend (the gate test's clean-up). */
	void restoreDeviceReadyForTest() {
		this.deviceReady = () -> backend() != null;
	}

	/**
	 * Test seam: the engine as an install leaves it, active with this pack's model and no device (the clouds decision reads
	 * the state and the model; {@link #shutdown} turns it off again).
	 */
	void activeWithModelForTest(PackModel model) {
		this.model = model;
		this.state = State.ACTIVE;
	}

	/** Blocks the background loader until the latch opens (the switch test holds a load in flight deterministically). */
	void holdLoaderForTest(java.util.concurrent.CountDownLatch latch) {
		loader().submit(() -> {
			try {
				latch.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
	}

	/** Returns once every load submitted so far has run (the loader runs in order; this is a marker at the end). */
	void awaitLoaderIdle() {
		try {
			loader().submit(() -> {}).get();
		} catch (InterruptedException | java.util.concurrent.ExecutionException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The current generation (tests use it to complete a load of the current request). */
	long currentGeneration() {
		return this.generation.get();
	}

	public State state() {
		return this.state;
	}

	/** The pack the engine serves or loads ({@code shaderpacks/} file or folder name), or null while it is off. */
	public @Nullable String packName() {
		return this.packName;
	}

	/** Why the last load of {@link #packName()} failed, while the state is FAILED; null otherwise. */
	public @Nullable String failureReason() {
		return this.state == State.FAILED ? this.failureReason : null;
	}

	/**
	 * Loads the selected pack again from the next frame (E8: the options screen's Apply, the reload key): the set is dropped,
	 * and the next frame reads {@code shaderpacks/<pack>.txt} and the settings anew, so a failed pack is tried again too.
	 * Render thread. Does nothing while the engine is off.
	 */
	public void reloadPack() {
		if (this.state == State.OFF) {
			return;
		}
		String pack = this.packName;
		turnOff();
		this.packName = pack;
		LOG.info("[mcms-shaders] pack {} reloads (asked from the screens or the reload key)", pack);
	}

	/**
	 * Why the engine cannot draw shader packs in this session, in words the screens show (E8), or null when it can: the engine
	 * draws only on Quicksilver's Metal backend.
	 */
	public static @Nullable String cannotRunReason() {
		if (backend() == null) {
			return "The game is not running on Metal now";
		}
		return null;
	}

	/** {@code MC_HAND_DEPTH} of the loaded pack (the hand programs' projection uses the same value), or the default. */
	public float handDepth() {
		PackModel loaded = this.model;
		return loaded != null ? loaded.macros().handDepth() : StandardMacros.DEFAULT_HAND_DEPTH;
	}

	/** Marks the load failed with its reason (the caller logs the ERROR); the reason is written first (see failureReason). */
	private void fail(String reason) {
		this.failureReason = reason;
		this.state = State.FAILED;
	}

	/** A throwable's message for a failure reason: its own message, or its class name when it has none. */
	private static String messageOf(Throwable t) {
		String message = t.getMessage();
		return message != null && !message.isBlank() ? message : t.getClass().getSimpleName();
	}

	/**
	 * The frame-start hook (right after the resize call of {@code GameRenderer.render}, bytecode 61; the mixin's
	 * Javadoc records the offsets). Cheap and allocation-free while the engine is off.
	 */
	public void frameStart(Minecraft mc, RenderTarget main, float partialTick) {
		if (!QuicksilverSettings.shadersOn() || QuicksilverSettings.shaderPackName() == null
			|| !this.deviceReady.getAsBoolean()) {
			if (this.state != State.OFF) {
				turnOff();
			}
			return;
		}
		String pack = QuicksilverSettings.shaderPackName();
		if (!pack.equals(this.packName)) {
			turnOff();
			this.packName = pack;
		}
		if (this.state == State.FAILED) {
			if (!this.failureToastShown) {
				this.failureToastShown = true;  // one toast per failed load (E8; the toast E3 deferred)
				FailureToast.show(mc, this.packName, this.failureReason);
			}
			releaseAfterFailure();
			return;
		}
		// The surface is built only where a load, a switch or a pending set needs it (D2: no record per frame).
		if (this.state == State.OFF) {
			String refusal = sodiumRefusal();
			if (refusal != null) {
				fail(refusal);  // one ERROR for the set's load (ADR 0007): the vanilla picture shows
				LOG.error("[mcms-shaders] {} (the vanilla picture shows)", refusal);
				return;
			}
			beginLoad(mc);
		}
		if (this.pendingModel.get() != null) {
			takePendingModel(mc, surfaceOf(main));
		}
		if (this.state == State.ACTIVE && this.targets != null) {
			// ADR 0008: the frame's render scale, latched once here; the after-level hook draws with the targets' scale.
			float scale = frameScale(this.worldDrawn);
			if (renderSize(main.width, scale) != this.targetWidth || renderSize(main.height, scale) != this.targetHeight
					|| scale != this.targetScale) {
				resizeTargets(main.width, main.height, scale, MetalBridge.pixelFormat(main.getColorTexture().getFormat()));
			}
			String dimension = dimensionOf(mc);
			if (!dimension.equals(this.loadedDimension)) {
				dimensionChanged(dimension, surfaceOf(main));
			}
		}
		this.mainView = MetalBridge.viewHandle(main.getColorTextureView());
		if (this.redirector != null) {
			this.redirector.mainColour(this.mainView);
		}
		if (this.pendingSet.get() != null) {
			takePendingSet(surfaceOf(main));
		}
		// After the set and dimension changes (ADR 0008 review): a set that installs on this frame gets its uniforms at its own
		// render size, as its targets have it.
		boolean sized = this.state == State.ACTIVE && this.targets != null;
		FrameUniforms uniforms = this.frameUniforms;
		if (uniforms != null) {
			uniforms.setLodBias(sized ? lodBiasOf(this.targetScale) : 0f);  // ADR 0008: the atlas lookups' sharper mip choice
		}
		captureUniforms(mc, partialTick, sized ? this.targetWidth : main.width, sized ? this.targetHeight : main.height);
		DrawLog.frame(this.delegate != null && this.state == State.ACTIVE);
	}

	/**
	 * Why a set may not load on the installed Sodium (ADR 0007, decision 2 and 3), or null when it may. Without Sodium
	 * there is no terrain extension to check. The allow-list comes first: an off-list release never reaches the terrain
	 * registration (E4 review item 6: nothing Sodium-facing loads, one ERROR). The layout registers after it, on the
	 * render thread, so the globals block is read before its check.
	 */
	private static @Nullable String sodiumRefusal() {
		String release = SodiumAdapter.release();
		if (release == null) {
			return null;
		}
		if (!SodiumAdapter.allowedRelease(release)) {
			return sodiumRefusal(release, null);
		}
		TerrainEngine.registerLayout();
		return sodiumRefusal(release, TerrainEngine.globalsFailure(TerrainEngine.globals()));
	}

	/**
	 * The decision of {@link #sodiumRefusal()} for a known release and globals check: a release off the allow-list
	 * refuses (the message names it), then a failed globals check refuses the set's load (its message is the reason).
	 *
	 * @param release       Sodium's release word (build part stripped), or null without Sodium
	 * @param globalsFailure why Sodium's globals block fails the engine's check, or null when it passes
	 * @return the refusal, or null when the set may load
	 */
	static @Nullable String sodiumRefusal(@Nullable String release, @Nullable String globalsFailure) {
		if (release == null) {
			return null;
		}
		if (!SodiumAdapter.allowedRelease(release)) {
			return "Sodium " + release + " is not on the engine's allow-list (ADR 0007)";
		}
		return globalsFailure;
	}

	/** The solid hand between the phase hook's splits: the delegate's {@code HAND} window around E4-C's draw. */
	private final class SolidHand implements Runnable {
		Object renderPass;
		GbufferDelegate.HandDraws draws;

		/** The kind window's body, one object for the engine's life (a steady-state frame allocates nothing). */
		private final Runnable inKind = () -> this.draws.drawSolidHand(this.renderPass);

		@Override
		public void run() {
			GbufferDelegate active = Engine.this.delegate;
			this.draws = active != null ? active.handDraws() : null;
			if (this.draws != null) {
				active.passKind(dev.mcms.shaders.runtime.gbuffers.PassKind.HAND, this.inKind);
			}
		}
	}

	/** The main target as the install and the loader need it: the device context, the size and the raw format. */
	static Surface surfaceOf(RenderTarget main) {
		return new Surface(MetalBridge.ctx(backend()), main.width, main.height,
			MetalBridge.pixelFormat(main.getColorTexture().getFormat()));
	}

	/** One axis of the render size at a scale (ADR 0008): the window's size divided by it, rounded, at least 1. */
	static int renderSize(int window, float scale) {
		return scale == 1f ? window : Math.max(1, Math.round(window / scale));
	}

	/** The LOD bias of a render scale (ADR 0008): log2(1 / scale), so the mip choice matches the window size; 0 at scale 1. */
	static float lodBiasOf(float scale) {
		return scale == 1f ? 0f : (float) (-Math.log(scale) / Math.log(2.0));
	}

	/**
	 * The scale a set draws at (ADR 0008): the Upscaling setting's while the set serves the world and no refusal turned
	 * upscaling off for the session; 1 otherwise (a set that serves no world leaves the game's world at the window size).
	 * {@link #UPSCALE_FACTOR} replaces a setting other than Off.
	 */
	private float frameScale(boolean servesWorld) {
		if (!servesWorld || this.upscaleRefused) {
			return 1f;
		}
		float scale = QuicksilverSettings.upscale().scale;
		return scale != 1f && UPSCALE_FACTOR > 0f ? UPSCALE_FACTOR : scale;
	}

	/** {@link #UPSCALE_FACTOR}'s value: above 1 and at most 2, else 0. */
	static float upscaleFactor(@Nullable String text) {
		if (text == null) {
			return 0f;
		}
		try {
			float f = Float.parseFloat(text.trim());
			return f > 1f && f <= 2f ? f : 0f;
		} catch (NumberFormatException e) {
			return 0f;
		}
	}

	/**
	 * The after-level hook (right after {@code GameRenderer.render}'s {@code renderLevel} call, bytecode 261): the
	 * frame's composite hook — the gbuffers flips, {@code composite} and {@code final} (E3's world copy first when
	 * the pack draws no world of its own; see {@link EngineFrame#compositeHook}). With the render scale above 1 (ADR 0008),
	 * {@code final} writes the targets' render-size stand-in for the main colour, and the upscale then writes the main colour.
	 */
	public void afterLevel(RenderTarget main) {
		DimensionSet set = this.active;
		Targets targets = this.targets;
		EngineFrame frame = this.frame;
		if (this.state != State.ACTIVE || set == null || targets == null || this.model == null || frame == null) {
			return;
		}
		Object encoder = backend();
		if (encoder == null) {
			return;
		}
		if (MetalBridge.inRenderPass(encoder)) {
			// The plan's assertion: no game render pass is open at the hook. Logged, not thrown: a throw would stop
			// the frame; the engine's passes are skipped and the vanilla picture stays (ADR 0006 section 4).
			LOG.error("[mcms-shaders] a game render pass is open at the engine's after-level hook; the frame's passes are skipped");
			return;
		}
		GpuTexture colour = main.getColorTexture();
		// Per-frame state only: the encoder handles and the main colour. The frame object, its blocks, buffers and
		// label memory belong to the active set (the E3 review: nothing per-frame is allocated here).
		this.passBlocks.beginFrame(encoder);
		int format = MetalBridge.pixelFormat(colour.getFormat());
		long upscaleInput = targets.upscaleInput();
		if (upscaleInput != 0) {
			this.mainColour.set(upscaleInput, format, this.targetWidth, this.targetHeight, null);
			// ADR 0008: a final that may leave texels unwritten clears the stand-in to the game's clear colour (the last one
			// seen when another pass already wrote this frame's).
			if (MetalBridge.pendingColorClear(colour, this.standInClear)) {
				this.standInClearKnown = true;
			}
			this.mainColour.standInClear(this.standInClearKnown ? this.standInClear : null);
		} else {
			this.mainColour.set(MetalBridge.textureHandle(colour), format, main.width, main.height, colour);
		}
		drawDistantWater();
		long enc = MetalBridge.enc(encoder);
		frame.compositeHook(enc, encoder, targets, this.mainColour, this.passBlocks);
		if (upscaleInput != 0) {
			upscale(enc, encoder, frame, upscaleInput, colour, main.width, main.height);
		}
	}

	/**
	 * Writes the whole main colour from the render-size picture (ADR 0008): MetalFX's spatial scaler, or after its refusal (one
	 * WARN per set) the engine's copy draw, a bilinear upscale. The main colour's pending clear is dropped first: the upscale
	 * writes every pixel, and the first GUI pass would otherwise take the clear.
	 */
	private void upscale(long enc, Object encoder, EngineFrame frame, long input, GpuTexture colour, int width, int height) {
		MetalBridge.dropPendingClear(encoder, colour);
		long output = MetalBridge.textureHandle(colour);
		if (!this.upscaleFallback && !"bilinear".equals(UPSCALER)) {
			this.spatialUsed = true;
			int result = ShaderNative.spatialScale(enc, input, output, "refuse".equals(UPSCALER));
			if (result >= 0) {
				if (result == ShaderNative.SPATIAL_BUILT) {
					LOG.info("[mcms-shaders] MetalFX's spatial scaler built: {} x {} to {} x {}", this.targetWidth,
						this.targetHeight, width, height);
				}
				return;
			}
			this.upscaleFallback = true;
			LOG.warn("[mcms-shaders] MetalFX's spatial scaler refused the upscale ({}); the engine's bilinear copy upscales instead",
				ShaderNative.spatialRefusal(result));
		}
		frame.upscaleCopy(enc, input, output, width, height);
	}

	/**
	 * The shadow frame (E5; {@code EngineShadowFrameMixin}, {@code LevelRenderer.render} bytecode 79, before
	 * {@code submitFeatures}): runs the shadow pass for this frame while a set with shadows is active. A step that throws
	 * (any {@code Throwable}) is logged once and drops the frame for the rest of the set, which also detaches the light's lists
	 * (ADR 0006, section 4): the frame keeps the vanilla picture.
	 */
	public void shadowFrameHook(net.minecraft.client.renderer.state.level.CameraRenderState camera) {
		ShadowFrameDriver driver = this.shadowDriver;
		ShadowPassOps ops = this.shadowOps;
		if (this.state != State.ACTIVE) {
			return;
		}
		EngineFrame head = this.frame;  // E6: the frame head (setup, begin, the custom images' clears) runs before the shadow pass
		Targets headTargets = this.targets;
		Object headEncoder = backend();
		if (head != null && headTargets != null && headEncoder != null && !MetalBridge.inRenderPass(headEncoder)) {
			this.passBlocks.beginFrame(headEncoder);
			head.frameHead(MetalBridge.enc(headEncoder), headTargets, this.passBlocks);
		}
		if (driver == null || !driver.active() || ops == null) {
			clearShadowMapsOnce();  // a set whose shadow pass does not run keeps its maps cleared (E5 review item 17)
			return;
		}
		Object encoder = backend();
		if (encoder == null) {
			return;
		}
		if (MetalBridge.inRenderPass(encoder)) {
			LOG.error("[mcms-shaders] a game render pass is open at the shadow hook; the shadow pass is skipped");
			return;
		}
		try {
			ops.begin(encoder, camera);
			// The frame's transient blocks upload on the encoder of the frame's current pass: the shadow pass comes first in
			// the frame, so its encoder is the one the frame block is staged on (the camera's clear hook begins its own).
			this.passBlocks.beginFrame(encoder);
		} catch (Throwable t) {
			driver.drop("the shadow pass's open", t);
			return;
		}
		driver.run();
	}

	/**
	 * Clears the shadow maps once for a set whose shadow pass does not run (a set that serves no world): the maps then hold
	 * their clear values. No-op while a shadow pass exists, and once done for the set.
	 */
	private void clearShadowMapsOnce() {
		ShadowTargets shadow = this.shadowTargets;
		if (shadow == null || this.shadowsCleared || !clearsMapsWithoutPass(this.shadowDriver)) {
			return;
		}
		Object encoder = backend();
		if (encoder == null || MetalBridge.inRenderPass(encoder)) {
			return;
		}
		shadow.clear(MetalBridge.enc(encoder));
		this.shadowsCleared = true;
	}

	/**
	 * Whether the set's maps are cleared by the frame rather than by a shadow pass: no shadow pass is installed, or the one
	 * installed was dropped (E5 review items 4 and 17). The maps then hold their clear values.
	 */
	static boolean clearsMapsWithoutPass(@Nullable ShadowFrameDriver driver) {
		return driver == null || !driver.active();
	}

	/**
	 * A shadow list failed to build (a mixin's catch, named by {@code list}): the set's shadow frame is dropped, which logs the
	 * failure and detaches both lists. With no frame, the lists are detached here.
	 */
	public static void shadowListFailed(String list, Throwable cause) {
		ShadowFrameDriver driver = get().shadowDriver;
		if (driver != null) {
			driver.drop(list, cause);
			return;
		}
		LOG.error("[mcms-shaders] {} failed; the set's shadows are skipped from now on", list, cause);
		ShadowLists.detach();
	}

	/** The installed shadow frame's driver, null with no shadow pass (tests read it). */
	@Nullable ShadowFrameDriver shadowDriverForTest() {
		return this.shadowDriver;
	}

	/** The active set's shadow targets, null when the set reads no shadow map (tests read it). */
	@Nullable ShadowTargets shadowTargetsForTest() {
		return this.shadowTargets;
	}

	/** Replaces the shadow entity dispatch's factory: tests supply dispatches built without the game. */
	void setShadowDispatchForTest(java.util.function.Function<GbufferDelegate, ShadowEntityDispatch> factory) {
		this.shadowDispatches = factory;
	}

	/**
	 * The frame-graph clear hook (a mixin at the head of {@code LevelRenderer.lambda$render$0}, the "clear" pass;
	 * plan.md E4's frame-order bullet): the pack targets' clears, the depth targets' clears to 1.0, the {@code
	 * prepare} passes and the sky and opaque phases' feedback copies ({@link EngineFrame#clearHook}). Inert while no
	 * set is active.
	 */
	public void clearHook() {
		EngineFrame frame = this.frame;
		try {
			drawClearHook();
		} finally {
			if (frame != null) {
				frame.endFrameHead();  // the frame's head window closes whether or not this hook drew (EngineFrame.endFrameHead)
			}
		}
	}

	/** The clear hook's work: {@link #clearHook} runs it and then closes the frame's head window. */
	private void drawClearHook() {
		Targets targets = this.targets;
		EngineFrame frame = this.frame;
		FrameUniforms uniforms = this.frameUniforms;
		if (this.state != State.ACTIVE || targets == null || this.model == null || frame == null || uniforms == null) {
			return;
		}
		Object encoder = backend();
		if (encoder == null) {
			return;
		}
		if (MetalBridge.inRenderPass(encoder)) {
			// The clear pass clears textures, so no render pass is open; a pass here means an earlier hook leaked one.
			LOG.error("[mcms-shaders] a game render pass is open at the engine's clear hook; the frame's clears are skipped");
			return;
		}
		this.passBlocks.beginFrame(encoder);
		targets.refreshFogClear(uniforms);  // colortex0's default clear: this frame's fog colour (plan.md E4)
		frame.clearHook(MetalBridge.enc(encoder), encoder, targets, this.passBlocks);
	}

	/**
	 * The phase hook (a mixin after {@code lambda$addMainPass$0}'s {@code executeSolid} call, bytecode 174, before
	 * {@code executeClassicTransparency}, 187; plan.md E4's phases bullet): ends the opaque phase inside the game's
	 * one "Solid" pass — the frame's phase hook runs the documented sequence ({@link EngineFrame#phaseHook}), with
	 * this frame's solid hand (E4-C's registered hand draws inside the delegate's {@code HAND} kind window; none
	 * prepared: null) and the delegate moved to the translucent phase, whose attachment layout the splits and the
	 * hand draw into. Skipped while the takeover serves no world: the game's pass is then the game's own (E3's
	 * interim order).
	 */
	public void phaseHook(Object renderPass, Object worldFrame) {
		Targets targets = this.targets;
		EngineFrame frame = this.frame;
		GbufferDelegate delegate = this.delegate;
		if (!this.worldDrawn || this.state != State.ACTIVE || targets == null || frame == null || delegate == null) {
			return;
		}
		Object encoder = backend();
		if (encoder == null) {
			return;
		}
		// The block entities' solid phase draws before the depth copies: they are opaque-phase geometry, so depthtex2
		// holds the depth with them drawn (the E4 routing rules' split; the world frame's identity is recorded here).
		BlockEntityRouter.get().drawSolid(renderPass, worldFrame);
		// The solid hand (and the opaque split it needs) only when this frame's hand was prepared (E4 review item 9).
		boolean handPrepared = HandRenderer.get().solidHandPrepared() && delegate.handDraws() != null;
		if (handPrepared) {
			this.solidHand.renderPass = renderPass;
		}
		frame.phaseHook(MetalBridge.enc(encoder), encoder, targets, this.passBlocks,
			handPrepared ? this.solidHand : null, this.transparentInPhase);
	}

	/**
	 * Whether the game's classic transparency answers while the takeover serves the world ({@code GameRenderer.
	 * useImprovedTransparency} false, plan.md E4: the OIT targets and passes stay out of the frame graph and the level's
	 * one pass takes the classic path the phase hook splits). Without a world the game answers itself (E4 review item 1).
	 */
	public boolean useClassicTransparency() {
		return this.state == State.ACTIVE && this.worldDrawn;
	}

	/**
	 * Whether the game's vanilla clouds are off while this engine serves a set whose shaders.properties says {@code clouds=off}
	 * (plan E6). The mixin on the game's cloud renderer skips its draws while this holds. {@code clouds=fast} and
	 * {@code clouds=fancy} change nothing: the OptiFine doc gives the video setting the higher priority.
	 */
	public boolean skipsVanillaClouds() {
		return this.state == State.ACTIVE && this.model != null && this.model.properties().disablesVanillaClouds();
	}

	/**
	 * Whether this engine serves a set whose shaders.properties switches the game's sun or moon off (RIL-569; the
	 * shaders.properties docs, "Features / Options": {@code sun=false}, {@code moon=false}, their quads in
	 * {@code gbuffers_skytextured}), as Iris 1.11.7 does. A directive the pack does not set keeps the game's drawing.
	 * Iris draws the stars under {@code stars=false} too, so the engine does (results/2026-11-19-ril569-night), and the
	 * vignette and the water overlay have a rule of their own ({@link #hidesScreenOverlays}).
	 */
	public boolean hidesVanilla(String directive) {
		return this.state == State.ACTIVE && this.model != null
			&& Boolean.FALSE.equals(this.model.properties().booleans().get(directive));
	}

	/**
	 * Whether the game's vignette and its water texture over the screen are skipped: while this engine serves a set,
	 * whatever its {@code vignette} and {@code underwaterOverlay} say, as Iris 1.11.7 draws neither over a pack with the
	 * directive unset or false (results/2026-11-19-ril569-day and -night: the corners as bright as the middle at midnight,
	 * where the game darkens them, and an untinted view from inside the water).
	 */
	public boolean hidesScreenOverlays() {
		return this.state == State.ACTIVE && this.model != null;
	}

	/**
	 * The tilt in degrees the game's sky renderer adds to the sun, moon and stars while this engine serves a set (the
	 * pack's {@code sunPathRotation}), 0 otherwise: the vanilla celestial bodies follow the same tilted path as the
	 * sunPosition, moonPosition and shadow-light uniforms (EngineCelestialTiltMixin, E7).
	 */
	public float celestialTilt() {
		return this.state == State.ACTIVE ? this.frameUniforms.sunPathRotation() : 0f;
	}

	/**
	 * Whether the engine draws the hand (the hand mixins and HandRenderer ask it): the engine is active and its takeover
	 * serves the world, so the hand's passes are the takeover's. Otherwise the game draws the hand its own way.
	 */
	public boolean drawsHand() {
		return this.state == State.ACTIVE && this.worldDrawn;
	}

	/**
	 * Whether the engine routes block entities into its own storage (the E4 routing rules' split): the engine is
	 * active and its takeover serves the world. Otherwise the game's {@code submitBlockEntities} call runs as it is.
	 */
	public boolean drawsBlockEntities() {
		return this.state == State.ACTIVE && this.worldDrawn;
	}

	/**
	 * Whether Sodium culls the camera's terrain to the view frustum: false only while the active set says
	 * {@code frustum.culling=false} (RIL-560; {@code SodiumEngineCullingMixin}). True while no set serves the world.
	 */
	public boolean frustumCulling() {
		PackModel model = this.model;
		return !(this.state == State.ACTIVE && this.worldDrawn && model != null
			&& Boolean.FALSE.equals(model.properties().booleans().get("frustum.culling")));
	}

	/**
	 * Whether Sodium's fog occlusion may shorten the camera's terrain search to the fog's end (RIL-571;
	 * {@code SodiumEngineFogCullMixin}): false while a set serves the world, true otherwise. Iris 1.11.7 draws a pack's
	 * terrain past the game's fog: from under water it shows the far hills (results/2026-11-19-ril569-day, scene
	 * overlays-water), where the game ends the water fog at its {@code WATER_FOG_END_DISTANCE} scaled by the player's water
	 * vision (javap of the 26.3 jar) and Sodium culls every section past that end. The pack, not the game's fog, decides
	 * what is seen.
	 */
	public boolean fogCullsTerrain() {
		return !(this.state == State.ACTIVE && this.worldDrawn);
	}

	/**
	 * The opaque phase's begin (the mixin before the level's Solid pass opens; {@link EngineFrame#opaqueHook}): the
	 * sky's pictures move to the read textures the opaque programs sample, and the opaque feedback targets load. Skipped
	 * while the takeover serves no world (the game's own pass then, and no gbuffers copies).
	 */
	public void opaqueHook() {
		this.distantDrawn = false;
		Targets targets = this.targets;
		EngineFrame frame = this.frame;
		if (!this.worldDrawn || this.state != State.ACTIVE || targets == null || frame == null) {
			return;
		}
		Object encoder = backend();
		if (encoder == null) {
			return;
		}
		long enc = MetalBridge.enc(encoder);
		frame.opaqueHook(enc, targets);
		drawDistantTerrain(enc, encoder, frame);
	}

	/**
	 * Whether the engine draws Distant Horizons' terrain through the pack this frame (plan.md E7): DH 3.3.4 draws through the pack in
	 * this session, the set has a {@code dh_terrain} program, and the takeover serves the world. DH's own post-solid terrain calls are
	 * then suppressed ({@code DistantHorizonsClientApiMixin}). DH may also ask from another thread (its render proxy reads the near
	 * clip, {@code DistantHorizonsNearClipMixin}), so the plans and targets are read once each.
	 */
	public boolean servesDistant() {
		GbufferPlans plans = this.plans;
		Targets targets = this.targets;
		return this.distantActive && this.state == State.ACTIVE && this.worldDrawn && plans != null && plans.servesDistant()
			&& targets != null && targets.distantDepthWanted();
	}

	/**
	 * DH's opaque terrain of the frame, drawn before the solid world (plan.md E7): the DISTANT phase's mip chains refill, DH's own
	 * {@code renderLods} runs with its transparency deferred ({@link DistantHorizonsDriver#renderTerrain}), and {@code dhDepthTex1} takes
	 * a copy of the opaque depth. Its passes are redirected into the DISTANT phase. Runs only with no game render pass open
	 * ({@link DistantCallGate}): DH's uniform writes need that, and this point is before the main pass opens.
	 */
	private void drawDistantTerrain(long enc, Object encoder, EngineFrame frame) {
		if (!servesDistant()) {
			return;
		}
		if (!this.distantGate.allow(MetalBridge.inRenderPass(encoder), "opaque")) {
			return;
		}
		if (!this.distantLogged) {
			this.distantLogged = true;
			LOG.info("[mcms-shaders] Distant Horizons terrain draws through the pack's dh_terrain and dh_water this frame");
		}
		frame.distantHook(enc, this.targets);
		this.distantDrawn = DistantHorizonsDriver.renderTerrain(this.distant);
		if (this.distantDrawn) {
			frame.distantDepthCopy(enc, this.targets);
		}
	}

	/**
	 * DH's transparent layer inside the phase hook (Iris's order: {@code dh_water} runs before the game's translucent draws). The game's
	 * pass is open here: its backend state is suspended ({@link MetalBridge#suspendPass}) and the renderpearl frontend's in-pass flag is
	 * cleared ({@link FrontendPassFlag}), so DH's pass can open; both are restored after DH's pass has been submitted, and the phase
	 * hook's split then reopens the game's pass on the translucent attachments. The layer's depth test reads the solid depth, since the
	 * game's translucent draws have not run yet. Clears {@link #distantDrawn}, so {@link #drawDistantWater} (the fallback after the
	 * level, for frames whose phase hook did not run) draws nothing. A frame whose flag cannot be read keeps the fallback. A throw from
	 * DH's call is logged once and sets {@link #inPhaseWaterFailed}: that frame's water is lost, the hook still finishes (restore, phase
	 * switch, split), and the frames after it draw the water after the level until the next set is installed.
	 */
	private void drawTransparentInPhase() {
		Object encoder = backend();
		if (!this.distantDrawn || !servesDistant() || encoder == null || this.inPhaseWaterFailed || !FrontendPassFlag.available()) {
			return;
		}
		this.distantDrawn = false;
		if (!this.inPhaseWaterLogged) {
			this.inPhaseWaterLogged = true;
			LOG.info("[mcms-shaders] Distant Horizons water draws inside the phase hook (Iris's order: before the game's translucents)");
		}
		boolean drawLog = DrawLog.on();
		String before = drawLog ? MetalBridge.passDescription(encoder) : null;
		MetalBridge.suspendPass(encoder, this.passSuspension);
		FrontendPassFlag.suspend(this.frontendFlag);
		try {
			DistantHorizonsDriver.renderTransparent(this.distant);
		} catch (Throwable failure) {
			// Logged once: this frame's water is lost, and later frames draw it after the level (this hook then keeps out of the way). The
			// frame itself continues: the restore, the phase switch and the split still run.
			this.inPhaseWaterFailed = true;
			LOG.error("[mcms-shaders] Distant Horizons water in the phase hook failed; later frames draw it after the level", failure);
		} finally {
			FrontendPassFlag.resume(this.frontendFlag);
			MetalBridge.resumePass(encoder, this.passSuspension);
		}
		if (drawLog) {
			String after = MetalBridge.passDescription(encoder);
			StringBuilder sb = DrawLog.line();
			sb.append("PASSRESTORE suspended before={").append(before).append("} after={").append(after).append("} identical=")
				.append(before.equals(after)).append(" frontendInPass=").append(FrontendPassFlag.inPass());
			DrawLog.emit(sb);
		}
	}

	/**
	 * DH's transparent terrain after the level: the fallback for a frame whose phase hook did not draw it ({@link #drawTransparentInPhase}
	 * is the primary, Iris's order). Its passes are redirected into the TRANSLUCENT phase by DH's deferred call
	 * ({@link DistantHorizonsDriver#renderTransparent}). Here the game's translucent draws have run, so the water blends over them, and
	 * the live depth holds the game's translucent depth: {@code dh_water} reads the solid copy under {@code depthtex0}
	 * ({@code GbufferDelegate.depthNameFor}) on this path. Draws nothing when the phase hook already drew it this frame.
	 */
	private void drawDistantWater() {
		if (!this.distantDrawn) {
			return;
		}
		this.distantDrawn = false;
		if (servesDistant()) {
			DistantHorizonsDriver.renderTransparent(this.distant);
		}
	}

	/**
	 * Which DH terrain pass the next render pass is (plan.md E7; the render head of DH's terrain render): the opaque pass draws into the
	 * DISTANT phase, the transparent one into the TRANSLUCENT phase.
	 */
	public void distantPass(boolean opaque) {
		EngineRedirector redirector = this.redirector;
		if (redirector != null) {
			redirector.distantPass(!opaque);
		}
	}

	/**
	 * DH's projection of the frame (plan.md E7), from the head of DH's opaque terrain render: DH's matrix as its row-major array, the clip
	 * planes, and the render distance in blocks.
	 */
	public void distantProjection(float[] dhProjection, float near, float far, int renderDistanceBlocks) {
		if (!servesDistant()) {
			return;
		}
		this.distant.setProjection(DistantProjection.fromRowMajor(dhProjection, this.distantMatrix), near, far, renderDistanceBlocks);
		// The frame's QsDh block may already be uploaded with the previous values this frame (a program bound it before the opaque hook,
		// e.g. the sky's): the next bind uploads it again, with this frame's projection.
		this.passBlocks.distantStamp = -1;
	}

	/** The offset of the Distant Horizons draw in progress (DH's model position, before each draw; plan.md E7). */
	public void distantDrawOffset(float x, float y, float z) {
		this.distant.setDrawOffset(x, y, z);
	}

	/** The targets both layouts name, in the first layout's order (the sky writes what the opaque programs sample). */
	private static int[] shared(int[] written, int[] read) {
		int[] out = new int[written.length];
		int count = 0;
		for (int target : written) {
			for (int r : read) {
				if (r == target) {
					out[count++] = target;
					break;
				}
			}
		}
		return java.util.Arrays.copyOf(out, count);
	}

	/** The pack's alpha-test table ({@code alphaTest.<program>}); tests and the frame block read it. */
	Map<String, ShaderProperties.AlphaTest> alphaTests() {
		return this.model != null ? this.model.properties().alphaTests() : Map.of();
	}

	/**
	 * The active set's engine block slices: the frame block is uploaded once per frame and again whenever a pass's
	 * alpha-test reference ({@code alphaTest.<program>}, the documented default 0.1) changes the block's
	 * {@code alphaTestRef} field, the custom block once (it holds no per-pass value). Transient memory lives one
	 * frame, so each distinct reference's slice is refreshed at its first pass of every frame, into a holder this
	 * set keeps (the E3 review: a steady-state frame allocates nothing).
	 */
	private final class PassBlocks implements EngineFrame.PassBlocks {
		/** The distinct alpha-test references seen so far (a short list: most packs use one or two). */
		private float[] refs = new float[4];
		private long[][] holders = new long[4][];
		/** Which frame each holder was last uploaded in (transient memory lives one frame only). */
		private int[] stamps = new int[4];
		private int refCount;
		private int stamp;
		private long @Nullable [] customBlock;
		private int customStamp = -1;
		/** The QsDh block's staging bytes, holder and the frame it was last uploaded in (E7). */
		private @Nullable ByteBuffer distantBytes;
		private final long[] distantHolder = new long[2];
		private int distantStamp = -1;

		/** A new frame begins: every slice refreshes at its first pass of this frame. */
		void beginFrame(Object encoder) {
			Engine.this.frameEncoder = encoder;
			this.stamp++;
		}

		@Override
		public long @Nullable [] frameBlock(String program) {
			if (Engine.this.frameUniforms == null || Engine.this.frameBytes == null) {
				return null;
			}
			ShaderProperties.AlphaTest test = alphaTests().get(program);
			float ref = test != null && !test.off() ? (float) test.ref() : 0.1f;
			for (int i = 0; i < this.refCount; i++) {
				if (this.refs[i] == ref) {
					return refresh(i);
				}
			}
			Engine.this.frameUniforms.setAlphaTestRef(ref);
			Engine.this.frameBytes.clear();
			Engine.this.frameUniforms.write(Engine.this.frameBytes);
			if (this.refCount == this.refs.length) {
				float[] moreRefs = new float[this.refCount * 2];
				long[][] moreHolders = new long[this.refCount * 2][];
				int[] moreStamps = new int[this.refCount * 2];
				System.arraycopy(this.refs, 0, moreRefs, 0, this.refCount);
				System.arraycopy(this.holders, 0, moreHolders, 0, this.refCount);
				System.arraycopy(this.stamps, 0, moreStamps, 0, this.refCount);
				this.refs = moreRefs;
				this.holders = moreHolders;
				this.stamps = moreStamps;
			}
			long[] uploaded = new long[2];
			MetalBridge.transientUniform(Engine.this.frameEncoder, Engine.this.frameBytes, uploaded);
			this.refs[this.refCount] = ref;
			this.holders[this.refCount] = uploaded;
			this.stamps[this.refCount] = this.stamp;
			this.refCount++;
			return uploaded;
		}

		/** Refreshes a reference's holder when its transient slice is from an earlier frame. */
		private long[] refresh(int index) {
			if (this.stamps[index] != this.stamp) {
				Engine.this.frameUniforms.setAlphaTestRef(this.refs[index]);
				Engine.this.frameBytes.clear();
				Engine.this.frameUniforms.write(Engine.this.frameBytes);
				MetalBridge.transientUniform(Engine.this.frameEncoder, Engine.this.frameBytes, this.holders[index]);
				this.stamps[index] = this.stamp;
			}
			return this.holders[index];
		}

		@Override
		public long @Nullable [] customBlock() {
			if (Engine.this.custom != null && Engine.this.customBytes != null && Engine.this.custom.size() > 0) {
				if (this.customStamp != this.stamp) {
					Engine.this.customBytes.clear();
					Engine.this.custom.write(Engine.this.customBytes);
					if (this.customBlock == null) {
						this.customBlock = new long[2];
					}
					MetalBridge.transientUniform(Engine.this.frameEncoder, Engine.this.customBytes, this.customBlock);
					this.customStamp = this.stamp;
				}
				return this.customBlock;
			}
			return null;
		}

		/**
		 * The QsDh block ({@code DistantUniforms}, E7), uploaded once per frame and declared by every program: while DH draws
		 * through the pack and has rendered once, the values of its last render; otherwise (no DH, or DH before its first
		 * render) Iris's values without DH, which the frame uniforms hold (measured with mcms-uniforms, 2026-11-16: zero planes
		 * would divide zero by zero in Bliss's depth helpers). Null before the frame's encoder exists.
		 */
		@Override
		public long @Nullable [] distantBlock() {
			FrameUniforms uniforms = Engine.this.frameUniforms;
			boolean rendered = Engine.this.distantRendered();
			if (Engine.this.frameEncoder == null || (!rendered && uniforms == null)) {
				return null;
			}
			if (this.distantBytes == null) {
				this.distantBytes = ByteBuffer.allocateDirect(DistantUniforms.size(DISTANT_BLOCK)).order(ByteOrder.LITTLE_ENDIAN);
			}
			if (this.distantStamp != this.stamp) {
				this.distantBytes.clear();
				if (rendered) {
					DistantFrame d = Engine.this.distant;
					DistantUniforms.write(DISTANT_BLOCK, d.nearPlane(), d.farPlane(), d.renderDistanceBlocks(), d.projection(),
						d.projectionInverse(), d.previousProjection(), this.distantBytes);
				} else {
					uniforms.writeDistant(DISTANT_BLOCK, this.distantBytes);
				}
				MetalBridge.transientUniform(Engine.this.frameEncoder, this.distantBytes, this.distantHolder);
				this.distantStamp = this.stamp;
			}
			return this.distantHolder;
		}
	}

	private void captureUniforms(Minecraft mc, float partialTick, int width, int height) {
		if (this.state != State.ACTIVE && this.state != State.LOADING) {
			return;
		}
		FrameUniforms uniforms = this.frameUniforms;
		if (uniforms == null) {
			return;
		}
		FrameInputs inputs;
		try {
			inputs = FrameInputs.capture(mc, partialTick, width, height);
		} catch (RuntimeException e) {
			// A capture bug (a state this engine did not expect): the block keeps its last values and the frame
			// draws with them; one WARN names the cause (ADR 0006 section 4's rule).
			if (!this.captureBroken) {
				this.captureBroken = true;
				LOG.warn("[mcms-shaders] the frame uniform capture failed; the frame block keeps its last values ({})",
					e.toString());
			}
			return;
		}
		if (inputs == null) {
			// A frame without a level or player (the title screen while a set is loaded): nothing to capture, the
			// block keeps its last values (E3-B's contract: capture returns null there instead of throwing).
			return;
		}
		this.captureBroken = false;
		uniforms.update(inputs);
		if (distantRendered()) {
			uniforms.distant(this.distant);  // the custom uniforms read DH's values (its last render's) instead of Iris's without DH
		}
		// The block atlas's size, from the table refreshAtlases filled (the custom uniforms read it once per frame).
		uniforms.setBlockAtlas(this.atlases.blockWidth(), this.atlases.blockHeight());
		if (this.custom != null) {
			this.custom.update(uniforms);
		}
	}

	/** The backend's encoder object, or null when the game does not draw with our Metal backend. */
	private static @Nullable Object backend() {
		// tryGetDevice reads the field (null before the game made a device); getDevice would throw, about 2 KB a call
		// (javap, 26.3: RenderSystem.tryGetDevice is getstatic DEVICE; FrontendGpuDevice.createCommandEncoder returns a field).
		GpuDevice device = RenderSystem.tryGetDevice();
		if (!(device instanceof FrontendGpuDevice frontend)
			|| !(frontend.createCommandEncoder() instanceof FrontendCommandEncoder command)) {
			return null;
		}
		return MetalBridge.encoder(command.backend());
	}

	private void turnOff() {
		beginRequest();  // first: a store put racing this one sees the moved generation and releases its set
		GbufferDelegate.runReleaseListeners();  // E4: the side buffers, and the hand's own release listener
		HandRenderer.get().close();  // E4-C: the hand dispatcher and its staged buffer (idempotent; shutdown comes here too)
		DrawLog.stop();  // E4 review item 13: an open draw-log block ends (the frame hook does not run while off)
		releaseGpu();
		this.state = State.OFF;
		this.packName = null;
		this.failureReason = null;
		this.failureToastShown = false;
		this.model = null;
		this.custom = null;
		this.frameUniforms = null;
		this.frameBytes = null;
		this.customBytes = null;
		this.loadedDimension = null;
		this.targetWidth = -1;
		this.targetHeight = -1;
		this.targetScale = 1f;
		this.pendingModel.set(null);  // a model read for a request that the turn-off ended is not kept (E9 review)
		Pending pending = this.pendingSet.getAndSet(null);
		if (pending != null) {
			this.releaser.accept(pending.set());
		}
		this.foregroundFolder = null;
		synchronized (this.readyByFolder) {
			for (Ready ready : this.readyByFolder.values()) {
				this.releaser.accept(ready.set());
			}
			this.readyByFolder.clear();
		}
		this.loading.clear();
	}

	/** The active set, detached: the caller decides whether it is released or kept (null when none is active). */
	private @Nullable DimensionSet detachActive() {
		DimensionSet old = this.active;
		this.active = null;
		return old;
	}

	/**
	 * Frees the GPU objects of the frame (targets with their depth textures, noise, copy pipelines, the frame's labels,
	 * the gbuffers takeover) and releases the active set, if any (engine off, pack switch, resource reload, a failed
	 * install, a failed resize). The takeover goes first: nothing draws through the engine while its objects die.
	 */
	private void releaseGpu() {
		this.standInClearKnown = false;  // ADR 0008: the next set reads the game's clear colour afresh
		MetalHooks.setRedirector(null);
		GbufferDelegate.install(null);
		this.redirector = null;
		if (this.spatialUsed) {
			ShaderNative.spatialRelease();  // ADR 0008: the spatial scaler's own GPU memory goes with the set's targets
			this.spatialUsed = false;
		}
		if (this.delegate != null) {
			this.delegate.close();  // E4 review item 2: the native draw scratch is freed with the takeover
			this.delegate = null;
		}
		if (this.horizon != null) {
			this.horizon.close();  // the horizon's buffers die with the takeover (the frame no longer draws it)
			this.horizon = null;
		}
		this.plans = null;  // the active set owns its plans: they are released with the set (releaseSet)
		this.worldDrawn = false;
		if (this.frame != null) {
			this.frame.close();  // the set's label memory
			this.frame = null;
		}
		if (this.targets != null) {
			this.targets.release();  // the colour and the depth targets
			this.targets = null;
		}
		ShadowLists.detach();  // the light's lists and draw bindings, and the shadow entity list (E5)
		ShadowComposites.get().detach();  // the set's shadowcomp passes (E5)
		if (this.shadowDispatch != null) {
			this.shadowDispatch.close();
			this.shadowDispatch = null;
		}
		this.shadowDriver = null;
		if (this.shadowOps != null) {
			this.shadowOps.close();  // the shadow pass's own globals and section-time buffers
		}
		this.shadowOps = null;
		if (this.shadowTargets != null) {
			this.shadowTargets.release();  // the shadow maps and colour buffers
			this.shadowTargets = null;
		}
		if (this.noiseTexture != 0) {
			if (this.noiseOwned) {
				MetalBridge.release(this.noiseTexture);  // a pack's own noise is the custom set's, released with it
			}
			this.noiseTexture = 0;
		}
		if (this.customs != null) {
			this.customs.close();
			this.customs = null;
		}
		if (this.storage != null) {
			this.storage.close();
			this.storage = null;
		}
		if (this.normalsDefault != 0) {
			MetalBridge.release(this.normalsDefault);
			this.normalsDefault = 0;
		}
		if (this.specularDefault != 0) {
			MetalBridge.release(this.specularDefault);
			this.specularDefault = 0;
		}
		if (this.lightmapDefault != 0) {
			MetalBridge.release(this.lightmapDefault);
			this.lightmapDefault = 0;
		}
		if (this.untexturedSampler != 0) {
			MetalBridge.release(this.untexturedSampler);
			this.untexturedSampler = 0;
		}
		if (this.copyPipeline != 0) {
			MetalBridge.release(this.copyPipeline);
			this.copyPipeline = 0;
		}
		if (this.finalCopyPipeline != 0) {
			MetalBridge.release(this.finalCopyPipeline);
			this.finalCopyPipeline = 0;
		}
		DimensionSet old = detachActive();
		if (old != null) {
			this.releaser.accept(old);
		}
	}

	/**
	 * Starts a load (plan.md E3). The pack is read on the loader (E9, RIL-564): the toggle key, the reload key and Apply hold
	 * the game for no read (Complementary's took 1.8 to 3.7 s on the render thread, E8). Here, on the render thread, the file
	 * is checked and the read's inputs taken from their owners (Distant Horizons' state, the hand depth); the model comes back
	 * the way a set does, under the generation counter, and {@link #takePendingModel} starts the set.
	 */
	private void beginLoad(Minecraft mc) {
		String pack = this.packName;
		if (pack == null) {
			return;
		}
		this.failureReason = null;
		this.failureToastShown = false;
		this.state = State.LOADING;
		long gen = beginRequest();
		Path dir = FabricLoader.getInstance().getGameDir().resolve("shaderpacks");
		Path file = dir.resolve(pack);
		if (!Files.exists(file)) {
			fail("the pack is not in the shaderpacks folder");
			LOG.error("[mcms-shaders] shader pack {} not found in {} (the engine stays off and the vanilla picture shows)",
				pack, dir);
			return;
		}
		// Distant Horizons draws through the pack in this session when DH 3.3.4 runs and the pack has a dh_ program. The
		// DISTANT_HORIZONS and DH_BLOCK_* defines and the engine's own DH depth follow that (plan.md E7, decision 8); until
		// the model lands, no set of this load serves DH.
		this.distantActive = false;
		TerrainEngine.distantServed(false);
		boolean distantRuntime = DistantHorizonsRuntime.active();
		// E8: the player's Hand depth (QuicksilverSettings) is MC_HAND_DEPTH for this load; the hand projection reads it back.
		float handDepth = QuicksilverSettings.handDepth();
		loader().submit(() -> readPack(gen, pack, file, dir.resolve(pack + ".txt"), distantRuntime, handDepth));
	}

	/** The loader's half of {@link #beginLoad}: reads the option values and the pack, and hands the model to the frame. */
	private void readPack(long gen, String pack, Path file, Path optionFile, boolean distantRuntime, float handDepth) {
		if (this.generation.get() != gen) {
			return;  // a newer request replaced this one before its turn
		}
		Properties options = new Properties();
		if (Files.isRegularFile(optionFile)) {
			try (StringReader in = new StringReader(Files.readString(optionFile))) {
				options.load(in);
			} catch (IOException | IllegalArgumentException e) {
				LOG.warn("[mcms-shaders] could not read the pack's option values {} ({})", optionFile, e.toString());
			}
		}
		long readStart = System.nanoTime();
		try (PackSource source = PackSource.open(file)) {
			boolean distant = distantRuntime && PackModel.declaresDistantPrograms(source);
			PackModel model = PackModel.load(source, StandardMacros.engine(distant, handDepth), "en_us", options);
			if (this.generation.get() == gen) {
				// A model a newer request replaced is not kept (E9 review: it would stay in memory while shaders are off). The
				// check runs again after the set: a request or turnOff between the first check and the set takes the model back.
				LoadedModel mine = new LoadedModel(gen, model, distant, (System.nanoTime() - readStart) / 1_000_000L);
				this.pendingModel.set(mine);
				if (this.generation.get() != gen) {
					this.pendingModel.compareAndSet(mine, null);
				}
			}
		} catch (Throwable e) {
			// Every throwable, as submitSet catches them (E9 review): an Error inside the loader's future would otherwise leave the
			// engine in LOADING with no log line.
			if (this.generation.get() == gen) {
				fail("the pack could not be read: " + messageOf(e));
				LOG.error("[mcms-shaders] shader pack {} failed to load: {} (the vanilla picture shows)", pack, e.toString());
			}
		}
	}

	/**
	 * The frame's half of a load: the model of the newest request becomes the engine's, and its foreground set starts
	 * compiling. A model a newer request replaced, or one that lands after the load failed or the engine turned off, is
	 * dropped.
	 */
	private void takePendingModel(Minecraft mc, Surface surface) {
		LoadedModel loaded = this.pendingModel.getAndSet(null);
		if (loaded == null || loaded.generation() != this.generation.get() || this.state != State.LOADING) {
			return;
		}
		String pack = this.packName;
		PackModel model = loaded.model();
		if (!model.unsupportedRequiredFeatures().isEmpty()) {
			// A required feature flag this engine lacks refuses the pack (iris.features.required, plan E6).
			fail("the pack needs features this engine does not have: " + String.join(", ", model.unsupportedRequiredFeatures()));
			LOG.error("[mcms-shaders] pack {} requires feature flags this engine lacks: {} (the vanilla picture shows)", pack,
				model.unsupportedRequiredFeatures());
			return;
		}
		this.distantActive = loaded.distant();
		TerrainEngine.distantServed(this.distantActive);  // DH's LOD colours follow the pack's shade rule only while it serves DH
		if (!model.problems().isEmpty()) {
			LOG.warn("[mcms-shaders] pack {} loaded with {} problems (first: {})", pack, model.problems().size(),
				model.problems().get(0));
		}
		LOG.info("[mcms-shaders] pack {} read in {} ms (loader)", pack, loaded.readMillis());
		prepareLoad(model);
		String dimension = dimensionOf(mc);
		this.loadedDimension = dimension;
		LOG.info("[mcms-shaders] pack {} loading (dimension {}, {} x {})", pack, programsOf(model, dimension).label(),
			surface.width(), surface.height());
		this.foregroundFolder = folderOf(programsOf(model, dimension));
		submitSet(loaded.generation(), programsOf(model, dimension), surface, true);
	}

	/** The loaded pack's per-run state: the model, the custom uniforms and the frame's uniform blocks (direct buffers). */
	void prepareLoad(PackModel model) {
		this.model = model;
		this.custom = CustomUniforms.of(model);  // null while the pack defines no custom uniforms (or E3-B is not merged)
		this.frameUniforms = new FrameUniforms();
		// Direct buffers: MetalBridge.transientUniform copies from the buffer's native address (a heap buffer has none).
		this.frameBytes = ByteBuffer.allocateDirect(FrameUniforms.size()).order(ByteOrder.LITTLE_ENDIAN);
		this.customBytes = ByteBuffer.allocateDirect(this.custom != null && this.custom.size() > 0 ? this.custom.size() : 16)
			.order(ByteOrder.LITTLE_ENDIAN);
	}

	/**
	 * Compiles one dimension's set on the background loader (translate, libraries, pipelines; no texture). The folder
	 * is marked loading until its set lands (or its load fails), so no second load of it starts meanwhile. The frame's
	 * own set ({@code foreground}) compiles on all spare cores; another dimension's set compiles beside the game on fewer,
	 * minimum-priority workers. Both stop when a newer request replaces this one: a load whose request is already
	 * replaced returns before it compiles anything (E4 review item 5).
	 */
	private void submitSet(long gen, DimensionPrograms programs, Surface surface, boolean foreground) {
		String folder = folderOf(programs);
		String pack = this.packName;
		PackModel model = this.model;
		CustomUniforms custom = this.custom;
		Map<Integer, String> formats = programs.union().colorFormats();
		long ctx = surface.ctx();
		int mainFormat = surface.format();
		this.loading.put(folder, gen);
		List<RenderPipeline> pipelines = gamePipelines();  // read here, on the render thread: the loader only uses the list
		BooleanSupplier replaced = () -> this.generation.get() != gen;
		GbufferPlans.Options options = foreground ? GbufferPlans.Options.foreground(replaced)
			: GbufferPlans.Options.background(replaced);
		loader().submit(() -> {
			if (replaced.getAsBoolean()) {
				this.loading.remove(folder, gen);  // a newer request replaced this one before its turn
				return;
			}
			try {
				DimensionSet set = DimensionSet.load(ctx, pack, model, programs, custom, mainFormat, formats, pipelines,
					COPIES_NOT_COMPILED, options);
				complete(gen, set);
			} catch (GbufferPlans.BuildCancelled stopped) {
				this.loading.remove(folder, gen);  // a newer request or the engine turning off stopped the build: nothing installs
			} catch (Throwable t) {
				this.loading.remove(folder, gen);  // only this request's marker (D3: a later request's marker stays)
				if (this.generation.get() == gen) {
					LOG.error("[mcms-shaders] shader pack {} failed to compile: {} (the vanilla picture shows)", pack,
						t.toString());
					fail("the pack's programs could not be built: " + messageOf(t));
				}
			}
		});
	}

	/** The background loader, made on first use (one thread: loads run in the order they were requested). */
	private ExecutorService loader() {
		if (this.loader == null) {
			this.loader = Executors.newSingleThreadExecutor(runnable -> {
				Thread thread = new Thread(runnable, "mcms-shaders-loader");
				thread.setDaemon(true);
				return thread;
			});
		}
		return this.loader;
	}

	/**
	 * A background load finished: only the newest request's set is taken (plan.md E3's generation counter). The set
	 * of the folder the foreground waits for waits for the next frame; another dimension's set goes to the ready
	 * store, so a later dimension change switches at once (plan.md E3's activation rule). A load a newer request
	 * replaced (pack switch, engine off, resource reload) is released here.
	 */
	void complete(long gen, DimensionSet set) {
		String folder = folderOf(set.dimension());
		this.loading.remove(folder, gen);
		if (this.generation.get() != gen) {
			this.releaser.accept(set);
			return;
		}
		if (this.foregroundFolder != null && !folder.equals(this.foregroundFolder)) {
			offer(gen, folder, set);
			return;
		}
		Pending replaced = this.pendingSet.getAndSet(new Pending(gen, set));
		if (replaced != null) {
			this.releaser.accept(replaced.set());
		}
	}

	/**
	 * Stores a finished set under its folder, unless its request was replaced: the generation is checked and the set
	 * put under the store's lock, and an engine turning off clears the store under the same lock after moving the
	 * generation (the review: a check-then-put left a superseded set in the store).
	 */
	private void offer(long gen, String folder, DimensionSet set) {
		synchronized (this.readyByFolder) {
			if (this.generation.get() != gen) {
				this.releaser.accept(set);
				return;
			}
			Ready replaced = this.readyByFolder.put(folder, new Ready(gen, set));
			if (replaced != null) {
				this.releaser.accept(replaced.set());
			}
		}
	}

	/**
	 * Takes a folder's stored set out of the store (a set is handed out once; {@code remove} false only looks). A stored
	 * set of an older generation is released here, never handed out. Null when the folder holds none.
	 */
	private @Nullable DimensionSet take(String folder, boolean remove) {
		synchronized (this.readyByFolder) {
			Ready ready = remove ? this.readyByFolder.remove(folder) : this.readyByFolder.get(folder);
			if (ready == null) {
				return null;
			}
			if (ready.generation() != this.generation.get()) {
				this.readyByFolder.remove(folder);
				this.releaser.accept(ready.set());
				return null;
			}
			return ready.set();
		}
	}

	/** Stores the outgoing active set under its own folder (the switch back is at once). */
	private void keep(DimensionSet set) {
		offer(this.generation.get(), folderOf(set.dimension()), set);
	}

	/** Whether the store holds a folder's set. */
	private boolean holds(String folder) {
		synchronized (this.readyByFolder) {
			return this.readyByFolder.containsKey(folder);
		}
	}

	/** The set a finished background load left for the next frame (tests read it; null when none waits). */
	DimensionSet pendingSet() {
		Pending pending = this.pendingSet.get();
		return pending != null ? pending.set() : null;
	}

	/**
	 * The finished set of another dimension's folder (tests read it; null when the folder holds none). A stored set of
	 * a superseded request is released here, like {@link #take}, and never returned.
	 */
	DimensionSet readySet(String folder) {
		return take(folder, false);
	}

	/**
	 * Opens a request (a load, a pack switch, a reload, the engine turning off): its generation. Every release path
	 * goes through here, so any load still running for an older generation is dropped when it completes.
	 */
	long beginRequest() {
		return this.generation.incrementAndGet();
	}

	/**
	 * Takes a background-loaded set at the start of a frame: targets are created here, on the render thread. The
	 * set carries its request: one a newer request replaced between the load's completion and this frame is
	 * released here, never installed — checking the generation at completion alone is a check-then-set race.
	 */
	void takePendingSet(Surface surface) {
		Pending pending = this.pendingSet.getAndSet(null);
		if (pending == null) {
			return;
		}
		if (pending.generation() != this.generation.get()) {
			this.releaser.accept(pending.set());
			return;
		}
		if (!this.deviceReady.getAsBoolean()) {
			// The device this set was compiled on is gone: its pipelines are dead weight, the engine stops.
			this.releaser.accept(pending.set());
			fail("the Metal device is gone");
			LOG.error("[mcms-shaders] the render device is gone before the pack's passes could start (the vanilla picture shows)");
			return;
		}
		if (this.active != null) {
			// A pending set never displaces the active one: its frame state, targets and pipelines are live (D1). Only a
			// duplicate load of a folder can land here, so the duplicate is the one released.
			this.releaser.accept(pending.set());
			return;
		}
		install(pending.set(), surface);
	}

	/**
	 * Makes a set active on the render thread: targets, placeholder, noise, copy pipelines, the frame, uniforms, the
	 * load line, and then the other folders' sets start compiling in the background (plan.md E3). A failed install
	 * releases the set (it never became active).
	 */
	void install(DimensionSet set, Surface surface) {
		long ctx = surface.ctx();
		long t0 = System.nanoTime();  // E9: the install's steps are timed (the reload's 130-150 ms frame, RIL-564)
		// A set that lands after a background load starts its clock here, at its first drawn frame, as Iris's pipeline does (the
		// dimension change restarts it too, for a change between two dimensions of one folder where no install happens).
		FrameUniforms uniforms = this.frameUniforms;
		if (uniforms != null) {
			uniforms.restartClock();
		}
		this.inPhaseWaterFailed = false;  // a new set tries DH's water in the phase hook again
		refreshAtlases();
		for (Map.Entry<Integer, String> format : set.dimension().union().colorFormats().entrySet()) {
			Targets.warnOnce(format.getValue());
		}
		// ADR 0008: the set's render size, from the scale it draws at (known here from its plans), as resizeTargets makes it.
		GbufferPlans planned = set.plans();
		float scale = frameScale(planned != null && planned.servesWorld());
		int width = renderSize(surface.width(), scale), height = renderSize(surface.height(), scale);
		this.upscaleFallback = false;  // a new set tries MetalFX again
		this.targets = newTargets(set, this.model, width, height, ctx, this.distantActive);
		if (scale != 1f) {
			this.targets.wantUpscale(surface.format());
		}
		if (!this.targets.create()) {
			this.targets.release();
			Targets plain = scale != 1f ? plainTargetsAfterRefusal(set, surface.width(), surface.height(), ctx) : null;
			if (plain == null) {
				fail("the GPU refused a render target");
				LOG.error("[mcms-shaders] the device refused a render target of pack {} (the vanilla picture shows)", this.packName);
				releaseGpu();
				this.releaser.accept(set);
				return;
			}
			this.targets = plain;
			width = surface.width();
			height = surface.height();
			scale = 1f;
		}
		long tTargets = System.nanoTime();
		// E6-B: the custom textures first (the pack's texture.noise replaces the generated noise).
		this.customs = CustomTextureSet.create(ctx, this.model.textures(), GameResources::read, GameResources.VIEWS);
		if (this.customs == null) {
			fail("the GPU refused a custom texture");
			LOG.error("[mcms-shaders] the device refused a custom texture of pack {} (the vanilla picture shows)", this.packName);
			releaseGpu();
			this.releaser.accept(set);
			return;
		}
		for (String missing : this.customs.missing()) {
			LOG.warn("[mcms-shaders] pack {} names texture {}, which the engine cannot load (the sampler keeps the engine's source)",
				this.packName, missing);
		}
		this.storage = StorageBuffers.create(ctx, this.model.buffers(), width, height);
		if (this.storage == null) {
			fail("the GPU refused a storage buffer");
			LOG.error("[mcms-shaders] the device refused a storage buffer of pack {} (the vanilla picture shows)", this.packName);
			releaseGpu();
			this.releaser.accept(set);
			return;
		}
		for (String problem : this.storage.problems()) {
			LOG.warn("[mcms-shaders] pack {}: {}", this.packName, problem);
		}
		long customNoise = this.customs.noise();
		this.noiseOwned = customNoise == 0;
		this.noiseTexture = customNoise != 0 ? customNoise : makeNoise(ctx, set.noiseResolution());
		this.normalsDefault = makeDefaultNormals(ctx);
		this.specularDefault = makeDefaultSpecular(ctx);
		this.lightmapDefault = makeTexel(ctx, 255, 255, 255, 255);  // E6-B: a custom lightmap before the game binds its own
		// 2026-11-15: the sampler of an atlas or lightmap name in a game pipeline that binds none (MTLSamplerAddressModeClampToEdge,
		// MTLSamplerMinMagFilterNearest, MTLSamplerMipFilterNotMipmapped: all 0).
		this.untexturedSampler = MetalBridge.samplerNew(ctx, 0, 0, 0, 0, 0, 1, Float.MAX_VALUE);
		if (this.normalsDefault == 0 || this.specularDefault == 0 || this.lightmapDefault == 0 || this.untexturedSampler == 0) {
			fail("the GPU refused one of the engine's default textures");
			LOG.error("[mcms-shaders] the device refused one of the engine's default textures (normal, specular, lightmap) or its"
				+ " untextured sampler (the vanilla picture shows)");
			releaseGpu();
			this.releaser.accept(set);
			return;
		}
		this.copyPipeline = this.copyPipelines.apply(ctx, this.targets.format(0));
		this.finalCopyPipeline = this.copyPipelines.apply(ctx, surface.format());
		if (this.copyPipeline == 0 || this.finalCopyPipeline == 0) {
			fail("the engine's copy pipeline failed");
			LOG.error("[mcms-shaders] the engine's copy pipeline failed (the vanilla picture shows)");
			releaseGpu();
			this.releaser.accept(set);  // the set never became active: its pipelines would leak otherwise (the review)
			return;
		}
		long tDefaults = System.nanoTime();
		GbufferPlans built = set.plans();
		if (built == null) {
			fail("the pack has no programs for the world");
			LOG.error("[mcms-shaders] pack {} has no gbuffers plans (the vanilla picture shows)", this.packName);
			releaseGpu();
			this.releaser.accept(set);
			return;
		}
		int[] compiledCopies = {0};
		try {
			GbufferPlans.GameCopier copier = GbufferPlans.renderThreadCopier();
			built.completeCopies(copy -> {  // the game-variant copies compile here, on the render thread
				compiledCopies[0]++;
				return copier.compile(copy);
			});
		} catch (RuntimeException e) {
			fail("the pack cannot draw its world: " + messageOf(e));
			LOG.error("[mcms-shaders] pack {} cannot draw its world: {} (the vanilla picture shows)", this.packName,
				e.getMessage());
			releaseGpu();
			this.releaser.accept(set);
			return;
		}
		long tCopies = System.nanoTime();
		this.plans = built;
		for (GbufferPhase phase : GbufferPhase.values()) {
			int[][] groups = new int[built.groups(phase)][];
			long[][] handles = new long[groups.length][];
			for (int group = 0; group < groups.length; group++) {
				groups[group] = built.layout(phase, group);
				handles[group] = new long[groups[group].length];
			}
			this.phaseLayouts[phase.ordinal()] = groups;
			this.phaseHandles[phase.ordinal()] = handles;
		}
		// E4 review item 1: a takeover that serves no world installs nothing; the game's pictures and passes stand.
		this.worldDrawn = built.servesWorld();
		// The shadow targets come first: the redirector takes them (E5), and their layout is the plans' shadow layout.
		this.shadowTargets = shadowTargetsOf(set);
		this.shadowsCleared = false;
		if (this.shadowTargets != null && !this.shadowTargets.fits(built.shadowLayout())) {
			// E5 review item 5: a shadow program writes a shadowcolor buffer the set does not hold (index 8 or above).
			fail("the pack writes a shadow colour buffer it does not have");
			LOG.error("[mcms-shaders] pack {} writes a shadow colour buffer it does not hold (the vanilla picture shows)",
				this.packName);
			releaseGpu();
			this.releaser.accept(set);
			return;
		}
		if (this.worldDrawn) {
			this.delegate = new GbufferDelegate(built, new EngineBindings());
			installRedirector(width, height);
			GbufferDelegate.install(this.delegate);
		}
		if (this.shadowTargets != null && !this.shadowTargets.create(Targets.engineTextures(surface.ctx()))) {
			fail("the GPU refused a shadow target");
			LOG.error("[mcms-shaders] the device refused a shadow target of pack {} (the vanilla picture shows)", this.packName);
			releaseGpu();
			this.releaser.accept(set);
			return;
		}
		long tShadow = System.nanoTime();
		this.frame = new EngineFrame(set, this.targets, this.shadowTargets, this.noiseTexture, this.copyPipeline,
			this.finalCopyPipeline,
			(bytes, out) -> MetalBridge.transientUniform(this.frameEncoder, bytes, out),
			this.model.properties().flips(), this.model.properties().scales(),
			gbuffersOf(built, this::setDelegatePhase), this.customs, this.storage);
		if (this.worldDrawn) {
			// The horizon (plan.md E4): the sky pipeline's below-horizon fan, drawn by the clear hook of every frame.
			this.horizon = new Horizon(surface.ctx(), gamePipelines(), built.groups(GbufferPhase.SKY), this.frameUniforms);
			this.frame.horizon(this.horizon);
		}
		long tFrame = System.nanoTime();
		this.active = set;
		this.state = State.ACTIVE;
		String folder = folderOf(set.dimension());
		this.foregroundFolder = folder;
		this.targetWidth = width;
		this.targetHeight = height;
		this.targetScale = scale;
		// The pack's smoothing half-lives in ticks (OptiFine doc, "Fragment Shader Configuration"): the first running
		// program that declares each const wins, the documented defaults fill the rest.
		this.frameUniforms.setHalflives(constFloat(set, "wetnessHalflife", 600f), constFloat(set, "drynessHalflife", 200f),
			constFloat(set, "eyeBrightnessHalflife", 10f), constFloat(set, "centerDepthHalflife", 1f));
		this.frameUniforms.setCenterDepth(1f);  // no depth read until E4: the documented far value
		// The celestial path's tilt (OptiFine "Fragment Shader Configuration": const float sunPathRotation, degrees).
		float sunPathRotation = constFloat(set, "sunPathRotation", 0f);
		this.frameUniforms.setSunPathRotation(sunPathRotation);
		this.frameUniforms.setShadowSettings(ShadowCamera.Settings.of(set.dimension().union().shadowConstants(),
			sunPathRotation, this.model.properties().shadowCulling().orElse(ShaderProperties.ShadowCulling.TRUE)));
		// The light's lists attach only for a set with a shadow pass (E5 review item 11). A pack that switches its shadow
		// program off has no shadow pass (review item 1). The lists use the shadow camera's matrices as they were last updated:
		// FrameUniforms updates them in the frame-start hook, after setupTerrain.
		if (this.shadowTargets != null && !this.shadowTargets.shadowProgramOff() && this.worldDrawn && this.delegate != null) {
			this.shadowDispatch = this.shadowDispatches.apply(this.delegate);
			ShadowCamera shadowCamera = this.frameUniforms.shadow();
			ShadowPassSequence.Content content = ShadowPassSequence.Content.of(this.model.properties());
			this.shadowOps = new ShadowPassOps(shadowCamera, this.shadowTargets, built.shadowLayout(), this.delegate,
				this.shadowDispatch);
			this.shadowDriver = new ShadowFrameDriver(new ShadowFrame(this.shadowOps), content, ShadowLists::detach);
			ShadowLists.attach(shadowCamera, content);
			ShadowComposites.get().attach(enc -> {
				EngineFrame active = this.frame;
				Targets current = this.targets;
				if (active != null && current != null) {
					active.shadowComposite(enc, current, this.passBlocks);
				}
			});
		}
		long tSettings = System.nanoTime();
		logDimensionLine(set);
		for (CompiledProgram pass : set.passes()) {
			if (!pass.unknownUniforms().isEmpty()) {
				LOG.warn("[mcms-shaders] program {} reads unknown uniforms (they read zero): {}", pass.name(),
					String.join(", ", pass.unknownUniforms()));
			}
		}
		submitRemainingFolders(surface, folder);
		long tEnd = System.nanoTime();
		LOG.info("[mcms-shaders] pack {} dimension {}: installed on the render thread in {} ms (targets {}, textures and"
			+ " defaults {}, game-variant copies {} ({} requested), plans, delegate and shadow targets {}, frame and horizon {},"
			+ " settings and shadow pass {}, logs and background loads {})", this.packName, set.dimension().label(),
			ms(tEnd - t0), ms(tTargets - t0), ms(tDefaults - tTargets), ms(tCopies - tDefaults), compiledCopies[0],
			ms(tShadow - tCopies), ms(tFrame - tShadow), ms(tSettings - tFrame), ms(tEnd - tSettings));
	}

	/**
	 * The other dimensions' sets compile in the background now (plan.md E3: a later change switches at once). A folder
	 * is skipped while it is active, stored, or already loading, so a second install never compiles a folder twice.
	 */
	private void submitRemainingFolders(Surface surface, String activeFolder) {
		PackModel model = this.model;
		if (model == null) {
			return;
		}
		java.util.LinkedHashSet<String> folders = new java.util.LinkedHashSet<>();
		for (DimensionPrograms programs : model.dimensions()) {
			folders.add(folderOf(programs));
		}
		folders.add(folderOf(model.rootFolder()));
		for (String folder : folders) {
			if (folder.equals(activeFolder) || this.loading.containsKey(folder) || holds(folder)) {
				continue;
			}
			DimensionPrograms programs = folderPrograms(model, folder);
			if (programs != null) {
				submitSet(this.generation.get(), programs, surface, false);
			}
		}
	}

	private static DimensionPrograms folderPrograms(PackModel model, String folder) {
		for (DimensionPrograms programs : model.dimensions()) {
			if (folderOf(programs).equals(folder)) {
				return programs;
			}
		}
		return folderOf(model.rootFolder()).equals(folder) ? model.rootFolder() : null;
	}

	/** The folder key of a dimension's programs ("" is the root folder; no folder maps to a key of its own). */
	private static String folderOf(DimensionPrograms programs) {
		return programs.folder().orElse("");
	}

	/** Releases a set's pipelines (a dropped or rejected load's own memory). */
	static void releaseSet(DimensionSet set) {
		if (set.plans() != null) {
			set.plans().close();  // the set's gbuffers plans: pipeline states and depth states (idempotent)
		}
		for (CompiledProgram pass : set.passes()) {
			if (pass.pipeline() != 0) {
				MetalBridge.release(pass.pipeline());
			}
			DimensionSet.releaseKernels(pass.kernels());  // E6: the compute kernels' pipelines are the set's too
		}
	}

	/** The first running program's float const {@code name} of the set, or the default (the union's first-wins rule). */
	private static float constFloat(DimensionSet set, String name, float dflt) {
		for (var program : set.dimension().programs()) {
			if (!program.runs() || program.directives().isEmpty()) {
				continue;
			}
			var value = program.directives().get().constants().get(name);
			if (value != null && !value.values().isEmpty()) {
				return value.values().get(0).floatValue();
			}
		}
		return dflt;
	}

	/** One line per dimension load (plan.md E3): sources, the road, and the times (cold: E3 has no MSL cache). */
	private void logDimensionLine(DimensionSet set) {
		LOG.info("[mcms-shaders] pack {} dimension {}: {} passes (pack {}, fallback {}, absent {}, standard road {}), translate {} ms, metal {} ms",
			this.packName, set.dimension().label(), set.passes().size(), set.fromPack(), set.byFallback(),
			set.absent(), set.onRoad(), ms(set.translateNanos()), ms(set.metalNanos()));
		for (String note : set.notes()) {
			LOG.warn("[mcms-shaders] {}", note);
		}
	}

	private static String ms(long nanos) {
		return String.format(Locale.ROOT, "%.1f", nanos / 1_000_000.0);
	}

	/**
	 * Rebuilds the targets at the render size of a window size and scale (ADR 0008; scale 1 is the window size). Pipelines do
	 * not depend on the size; the textures (depth included) are rebuilt (plan.md E3: rebuilt on resize). The takeover's
	 * redirector holds the depth textures and the size, so it is rebuilt with them. With the scale above 1 the targets also
	 * hold the main colour's render-size stand-in (in {@code mainFormat}) and the own-depth passes' depth; when the device
	 * refuses the scaled targets, upscaling turns off for the session (one WARN) and the targets are made at the window size.
	 */
	private void resizeTargets(int windowWidth, int windowHeight, float scale, int mainFormat) {
		EngineFrame resizedFrame = this.frame;
		if (resizedFrame != null) {
			resizedFrame.resized();  // E6: the setup programs run again after a resize
		}
		DimensionSet set = this.active;
		if (set == null) {
			return;
		}
		int width = renderSize(windowWidth, scale), height = renderSize(windowHeight, scale);
		long ctx = MetalBridge.ctx(backend());
		CustomImages images;
		try {
			// E6: fixed-size images keep their textures and contents; only the relative ones are rebuilt. Taken before the old
			// targets are released, so that release frees only what the new images do not own.
			images = this.targets.customImages().resize(ctx, width, height);
		} catch (RuntimeException refused) {
			fail("the GPU refused a custom image after a resize");
			LOG.error("[mcms-shaders] the device refused a custom image on resize (the vanilla picture shows)", refused);
			releaseGpu();
			return;
		}
		this.targets.release();
		Targets rebuilt = newTargets(set, this.model, width, height, ctx, images, this.distantActive);
		if (scale != 1f) {
			rebuilt.wantUpscale(mainFormat);
		}
		if (!rebuilt.create()) {
			rebuilt.release();
			Targets plain = scale != 1f ? plainTargetsAfterRefusal(set, windowWidth, windowHeight, ctx) : null;
			if (plain == null) {
				fail("the GPU refused a render target after a resize");
				LOG.error("[mcms-shaders] the device refused a render target on resize (the vanilla picture shows)");
				releaseGpu();
				return;
			}
			rebuilt = plain;
			width = windowWidth;
			height = windowHeight;
			scale = 1f;
		}
		this.targets = rebuilt;
		if (this.storage != null) {
			// E6-B: a screen-sized storage buffer whose size changed is rebuilt (its contents restart); fixed buffers keep theirs.
			if (!this.storage.resize(MetalBridge.ctx(backend()), width, height)) {
				fail("the GPU refused a storage buffer after a resize");
				LOG.error("[mcms-shaders] the device refused a storage buffer on resize (the vanilla picture shows)");
				releaseGpu();
				return;
			}
			for (String problem : this.storage.problems()) {
				LOG.warn("[mcms-shaders] pack {}: {}", this.packName, problem);
			}
		}
		if (this.worldDrawn) {
			installRedirector(width, height);
		}
		this.targetWidth = width;
		this.targetHeight = height;
		this.targetScale = scale;
		// ADR 0008 review: new sizes may build where a scaler did not (a refusal is per size), and at scale 1 the scaler's own
		// memory is freed.
		this.upscaleFallback = false;
		if (scale == 1f && this.spatialUsed) {
			ShaderNative.spatialRelease();
			this.spatialUsed = false;
		}
	}

	/**
	 * ADR 0008: after the device refused the scaled targets, upscaling is off for the session (one WARN, not the vanilla
	 * picture) and the set's targets are made at the window size, with new custom images. Null when those are refused too.
	 */
	private @Nullable Targets plainTargetsAfterRefusal(DimensionSet set, int windowWidth, int windowHeight, long ctx) {
		this.upscaleRefused = true;
		LOG.warn("[mcms-shaders] the device refused a render-scale target; upscaling is off for this session");
		Targets plain = newTargets(set, this.model, windowWidth, windowHeight, ctx, this.distantActive);
		if (plain.create()) {
			return plain;
		}
		plain.release();
		return null;
	}

	/**
	 * The gbuffers half of a frame, as install builds it: the three phase layouts, the world flag, the phase setter and
	 * the feedback sets (the opaque programs' sampled-and-written targets, the sky targets the opaque programs sample, and
	 * the translucent targets a program drawn in the translucent phase samples). The feedback sets count the samplers the
	 * built code reads ({@link GbufferPlans#sampledTargets}, E9), not the declarations: a copy at a phase boundary only serves
	 * a program that samples the target. Static so the GPU tests build the frame exactly as an activation does.
	 */
	static EngineFrame.Gbuffers gbuffersOf(GbufferPlans built, Consumer<GbufferPhase> phases) {
		PhaseTargets sky = phaseTargetsOf(built, GbufferPhase.SKY);
		PhaseTargets opaque = phaseTargetsOf(built, GbufferPhase.OPAQUE);
		PhaseTargets translucent = phaseTargetsOf(built, GbufferPhase.TRANSLUCENT);
		boolean world = built.servesWorld();
		// The phase boundaries refill the chains the phase's programs can read at a level other than 0 (E9). The distant
		// phase keeps every sampled target [choice: dh_terrain alone draws there, and E9 measured no Distant Horizons scene].
		int[][] reads = new int[GbufferPhase.values().length][];
		for (GbufferPhase phase : GbufferPhase.values()) {
			reads[phase.ordinal()] = phase == GbufferPhase.DISTANT ? built.readTargets(phase) : built.mipReadTargets(phase);
		}
		// Distant Horizons' terrain draws right after the opaque hook (dh_terrain, keyed to the DISTANT phase), so the opaque
		// hook's carry and feedback copies serve its samplers too (E9 review), and its targets flip with the opaque ones before
		// deferred (RIL-563: a target only dh_terrain writes was never flipped, so the full-screen passes read its clear).
		int[] opaqueSampled = with(built.sampledTargets(GbufferPhase.OPAQUE), built.sampledTargets(GbufferPhase.DISTANT));
		return new EngineFrame.Gbuffers(sky, opaque, translucent, world, phases, null,
			world ? shared(built.targets(GbufferPhase.OPAQUE), opaqueSampled) : NO_TARGETS,
			world ? shared(built.targets(GbufferPhase.SKY), opaqueSampled) : NO_TARGETS,
			world ? translucentSampled(built) : NO_TARGETS, reads, built.shadowMipReadTargets(),
			world ? built.targets(GbufferPhase.DISTANT) : NO_TARGETS);
	}

	/**
	 * The translucent targets a program drawn in the translucent phase samples (E9: they keep the phase hook's load copy, the
	 * others flip): those the translucent phase's programs sample, and those Distant Horizons' programs sample, since its
	 * water draws in that phase before the game's translucents.
	 */
	private static int[] translucentSampled(GbufferPlans built) {
		return shared(built.targets(GbufferPhase.TRANSLUCENT),
			with(built.sampledTargets(GbufferPhase.TRANSLUCENT), built.sampledTargets(GbufferPhase.DISTANT)));
	}

	/** Two target lists one after the other (a target in both appears twice; {@link #shared} reads it once). */
	private static int[] with(int[] a, int[] b) {
		int[] out = java.util.Arrays.copyOf(a, a.length + b.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}

	/** One phase's targets for the frame: every attachment group's layout, and the union of them. */
	private static PhaseTargets phaseTargetsOf(GbufferPlans built, GbufferPhase phase) {
		int[][] groups = new int[built.groups(phase)][];
		for (int group = 0; group < groups.length; group++) {
			groups[group] = built.layout(phase, group);
		}
		return new PhaseTargets(groups, built.targets(phase));
	}

	/**
	 * The set's shadow layout (E5), made live when a program is named shadow* or a stage declares a shadow sampler, or a
	 * full-screen pass's reflection reads one; null when the set reads no shadow map (its samplers then read zeros).
	 */
	private static @Nullable ShadowTargets shadowTargetsOf(DimensionSet set) {
		ShadowTargets layout = ShadowTargets.of(set.dimension());
		boolean wanted = layout.used();
		for (CompiledProgram pass : set.passes()) {
			for (var texture : pass.reflection().textures()) {
				wanted |= SamplerSources.of(texture.name()) == SamplerSources.Source.SHADOW;
			}
		}
		return wanted ? layout : null;
	}

	/** The targets of a set at a size: the depth copies exist where a program of the set reads them (no Distant Horizons depth). */
	static Targets newTargets(DimensionSet set, PackModel model, int width, int height, long ctx) {
		return newTargets(set, model, width, height, ctx, false);
	}

	/**
	 * The targets of a set at a size, with the Distant Horizons depth targets when {@code distant} (plan.md E7: DH runs in the
	 * session, so the engine owns its depth).
	 */
	static Targets newTargets(DimensionSet set, PackModel model, int width, int height, long ctx, boolean distant) {
		return newTargets(set, model, width, height, ctx, CustomImages.create(ctx, model.properties().images(), width, height),
			distant);
	}

	/** The set's targets at a size, with the custom images already made at that size (a resize keeps the fixed ones). */
	private static Targets newTargets(DimensionSet set, PackModel model, int width, int height, long ctx,
			CustomImages images, boolean distant) {
		Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(), set.usedTargets(),
			// depthtex1 is the solid depth (after the solid hand, before the translucents): dh_water reads it as depthtex0 (the
			// solid depth Iris gives it), so the copy exists whenever DH is served.
			readsTexture(set.dimension(), "depthtex1") || distant, readsTexture(set.dimension(), "depthtex2"), width, height,
			Targets.engineTextures(ctx), null, images);
		targets.wantDistantDepth(distant);
		return targets;
	}

	/** Whether a program of the dimension names a sampler or texture {@code name} (its stage texts; a copy only when read). */
	private static boolean readsTexture(DimensionPrograms dimension, String name) {
		java.util.regex.Pattern use = java.util.regex.Pattern.compile("\\b" + name + "\\b");
		for (ResolvedProgram program : dimension.programs()) {
			for (StageFile file : program.stages()) {
				if (use.matcher(file.text()).find()) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * The copier of game pipelines whose colour format differs from colortex0's. None compiles yet: the backend's compile
	 * path takes a create-info the frontend builds from the game's shaders, and this engine has no render-thread entry for
	 * it. Such a pipeline therefore draws in no phase (left for E4: the game-variant copies).
	 */
	static final GbufferPlans.GameCopier COPIES_NOT_COMPILED = copy -> null;

	/**
	 * The game's own pipelines the takeover maps (listed at install: no first-use compile in a frame). The registry
	 * lists keep one pipeline per location — 26.3 registers three under {@code pipeline/item_cutout} and the last
	 * (glint-special, 44 bytes) displaces the plain 36-byte one the hand draws with (RenderPipelines javap
	 * 2026-11-14) — so every {@code RenderPipelines} static field joins them: a field whose location and vertex
	 * strides the registry already covers adds nothing, and one the registry lost (the plain item_cutout) gets its
	 * own plan. A field reflection cannot read keeps the registry list (the engine still serves the rest).
	 */
	static List<RenderPipeline> gamePipelines() {
		List<RenderPipeline> all = new ArrayList<>(RenderPipelines.requiredPipelines());
		all.addAll(RenderPipelines.optionalPipelines());
		try {
			for (java.lang.reflect.Field field : RenderPipelines.class.getFields()) {
				if (!RenderPipeline.class.isAssignableFrom(field.getType())) {
					continue;
				}
				RenderPipeline pipeline = (RenderPipeline) field.get(null);
				if (pipeline == null || coversLayout(all, pipeline)) {
					continue;
				}
				all.add(pipeline);
			}
		} catch (IllegalAccessException unreadable) {
			// the registry list alone: the same-named layouts it lost draw through the closest plan or drop
		}
		return all;
	}

	/** Whether the list already holds a pipeline of the same name whose vertex strides are the same. */
	private static boolean coversLayout(List<RenderPipeline> pipelines, RenderPipeline pipeline) {
		String name = pipeline.getLocation().toString();
		int count = pipeline.getVertexFormatBindings().size();
		for (RenderPipeline listed : pipelines) {
			if (!listed.getLocation().toString().equals(name)
				|| listed.getVertexFormatBindings().size() != count) {
				continue;
			}
			boolean same = true;
			for (int binding = 0; binding < count; binding++) {
				VertexFormat a = listed.getVertexFormatBinding(binding);
				VertexFormat b = pipeline.getVertexFormatBinding(binding);
				if ((a == null || b == null) ? a != b : a.getVertexSize() != b.getVertexSize()) {
					same = false;
					break;
				}
			}
			if (same) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The redirector over the current targets: the phase layouts (write textures) for the redirected passes, the live
	 * depth, the size. Replaced when the targets are rebuilt; {@link #mainView} is set per frame.
	 */
	private void installRedirector(int width, int height) {
		ShadowTargets shadow = this.shadowTargets;
		EngineRedirector.ShadowColours shadowColours = shadow == null || this.plans == null ? null
			: new ShadowPassColours(shadow, this.plans.shadowLayout());
		EngineRedirector.PhaseColours colours = new EngineRedirector.PhaseColours() {
			@Override
			public long @Nullable [] colours(GbufferPhase phase, int group) {
				return phaseColours(phase, group);
			}

			@Override
			public boolean serves(GbufferPhase phase) {
				// The DH opaque terrain needs dh_terrain (DISTANT); the DH transparent terrain needs dh_water (TRANSLUCENT).
				return plans != null && (phase == GbufferPhase.DISTANT ? plans.servesDistant()
					: phase == GbufferPhase.TRANSLUCENT && plans.servesDistantWater());
			}
		};
		this.redirector = new EngineRedirector(colours, this.targets.depth(0), this.targets.distantDepth(0), width, height,
			shadowColours, this.delegate);
		this.redirector.mainColour(this.mainView);
		this.redirector.overlayDepth(this.targets.overlayDepth());  // ADR 0008: 0 at scale 1 keeps the passes' own depth
		MetalHooks.setRedirector(this.redirector);
	}

	/**
	 * The write handles of one attachment group of a phase, in attachment order (the redirector's colours and a group switch's).
	 * The array is the group's own, refilled per call: the pass takes the attachments it opens or splits onto, no allocation.
	 */
	private long @Nullable [] phaseColours(GbufferPhase phase, int group) {
		Targets targets = this.targets;
		int[][] groups = this.phaseLayouts[phase.ordinal()];
		if (targets == null || groups == null || group >= groups.length || groups[group].length == 0) {
			return null;
		}
		int[] layout = groups[group];
		long[] handles = this.phaseHandles[phase.ordinal()][group];
		for (int i = 0; i < layout.length; i++) {
			handles[i] = targets.write(layout[i]);
		}
		return handles;
	}

	/** The phase the delegate draws its pipelines in (the frame's phase hook moves it: the hand, then translucent). */
	private void setDelegatePhase(GbufferPhase phase) {
		GbufferDelegate delegate = this.delegate;
		if (delegate != null) {
			delegate.phase(phase);
		}
	}

	/**
	 * The delegate's per-draw bindings (E4Plans' contract): the frame, custom and pass blocks, the targets' read and
	 * write textures, the depth textures and the noise texture, all of the active set's current frame.
	 */
	private final class EngineBindings implements GbufferDelegate.Bindings {
		@Override
		public long @Nullable [] phaseColours(GbufferPhase phase, int group) {
			return Engine.this.phaseColours(phase, group);
		}

		@Override
		public long @Nullable [] frameBlock(String program) {
			return Engine.this.passBlocks.frameBlock(program);
		}

		@Override
		public long @Nullable [] customBlock() {
			return Engine.this.passBlocks.customBlock();
		}

		/**
		 * The pack's own uniform or buffer block of that name. The engine holds no storage for pack-declared blocks yet (the
		 * storage buffers come with the compute and gbuffers' shader blocks of E5 and E6), so every name answers null and
		 * the block reads as zero; the slots 0 to {@link EngineSlots#PACK_BLOCKS} minus one stay the delegate's to bind.
		 */
		@Override
		public long @Nullable [] block(String name) {
			return null;
		}

		@Override
		public long texture(String colortexName, boolean write) {
			Targets targets = Engine.this.targets;
			if (targets == null) {
				return 0;
			}
			int custom = targets.customImages().indexOfImage(colortexName);
			if (custom < 0) {
				custom = targets.customImages().indexOfSampler(colortexName);
			}
			if (custom >= 0) {
				return targets.customImages().texture(custom);
			}
			// The image names a pack's colorimgN and shadowcolorimgN declare (ComputeKernel's encoding; E6). The delegate calls
			// this per sampler of every draw, so the lookups allocate nothing (RIL-567).
			int shadowImage = ComputeKernel.shadowColourImageOf(colortexName);
			if (shadowImage >= 0) {
				return Engine.this.shadowTargets == null ? 0L : Engine.this.shadowTargets.color(shadowImage);
			}
			int index = ComputeKernel.colourTargetOf(colortexName);  // colorimgN is colortexN's image (E6)
			if (index < 0) {
				index = ProgramRequests.targetIndex(colortexName);
			}
			if (index < 0 || index >= targets.count()) {
				return 0;
			}
			return write ? targets.write(index) : targets.read(index);
		}


		@Override
		public long shadow(String name) {
			ShadowTargets shadow = Engine.this.shadowTargets;
			return shadow == null ? 0L : shadow.texture(name);
		}

		@Override
		public long depth(String name) {
			Targets targets = Engine.this.targets;
			if (targets == null) {
				return 0;
			}
			return switch (name) {
				case "depthtex1" -> targets.depth(1);
				case "depthtex2" -> targets.depth(2);
				default -> {
					int distant = SamplerSources.distantDepthOf(name);
					yield distant >= 0 ? targets.distantDepth(distant) : targets.depth(0);
				}
			};
		}

		@Override
		public boolean distantDefined() {
			return Engine.this.distantActive;
		}

		@Override
		public long @Nullable [] distantBlock() {
			return Engine.this.passBlocks.distantBlock();
		}

		@Override
		public @Nullable DistantFrame distantFrame() {
			return Engine.this.servesDistant() ? Engine.this.distant : null;
		}

		@Override
		public long noise() {
			return Engine.this.noiseTexture;
		}

		/**
		 * The size of the atlas a draw's view shows (E6-atlassize): the table of the game's atlases, matched by identity.
		 */
		@Override
		public void atlasSize(@Nullable GpuTextureView view, int[] out) {
			Engine.this.atlases.sizeOf(view, out);
		}

		/** E6-B: the storage buffer of a bufferObject index, or 0. */
		@Override
		public long storage(int index) {
			StorageBuffers buffers = Engine.this.storage;
			return buffers == null ? 0L : buffers.handle(index);
		}

		@Override
		public int customId(String program, String sampler) {
			CustomTextureSet custom = Engine.this.customs;
			return custom == null ? -1 : custom.id(program, sampler);
		}

		@Override
		public long customHandle(int id) {
			CustomTextureSet custom = Engine.this.customs;
			return custom == null ? 0L : custom.handle(id);
		}


		@Override
		public long defaultTexture(String name) {
			return switch (name) {
				case "normals" -> Engine.this.normalsDefault;
				case "specular" -> Engine.this.specularDefault;
				// E6-B: a custom texture replacing the game's atlas reads zero until the game binds its atlas (no content).
				case "texture", "gtexture", "tex" -> Engine.this.specularDefault;
				case "lightmap" -> Engine.this.lightmapDefault;
				default -> 0;
			};
		}

		@Override
		public long untexturedTexture() {
			return Engine.this.lightmapDefault;  // white
		}

		@Override
		public long untexturedSampler() {
			return Engine.this.untexturedSampler;
		}

		@Override
		public void projection(float[] out) {
			FrameUniforms uniforms = Engine.this.frameUniforms;
			if (uniforms != null) {
				uniforms.gbufferProjection(out);
			} else {
				java.util.Arrays.fill(out, 0f);
				out[0] = out[5] = out[10] = out[15] = 1f;  // no frame yet: the identity (the delegate draws only while active)
			}
		}
	}

	void dimensionChanged(String dimension, Surface surface) {
		// Iris starts frameTimeCounter and frameCounter again in each new dimension (measured with a pack that has a folder
		// per dimension; FrameUniforms.restartClock, plan.md E7): the engine restarts them at every dimension change.
		FrameUniforms uniforms = this.frameUniforms;
		if (uniforms != null) {
			uniforms.restartClock();
		}
		// The other dimensions compile in the background; a change switches at once when the set is there, else the
		// vanilla picture shows while it loads (plan.md E3). Only a different folder's set is a real change.
		DimensionPrograms programs = programsOf(this.model, dimension);
		if (this.active != null && programs.folder().equals(this.active.dimension().folder())) {
			this.loadedDimension = dimension;
			return;
		}
		this.loadedDimension = dimension;
		// The outgoing set goes to the store, not to the release: a switch back is at once (plan.md E3), and the store
		// hands a set out only once (take removes it), so a set is never installed twice or released twice.
		DimensionSet outgoing = detachActive();
		DimensionSet ready = take(folderOf(programs), true);
		if (outgoing != null) {
			keep(outgoing);
		}
		// A foreground load of the dimension we left waits for no frame now; it stays for its own folder.
		Pending stale = this.pendingSet.getAndSet(null);
		if (stale != null) {
			offer(stale.generation(), folderOf(stale.set().dimension()), stale.set());
		}
		if (ready != null) {
			releaseGpu();
			install(ready, surface);
			return;
		}
		this.state = State.LOADING;
		releaseGpu();
		this.foregroundFolder = folderOf(programs);
		// A folder already loading lands in the pending set of this same request (D1: a second load would be a duplicate).
		if (!this.loading.containsKey(folderOf(programs))) {
			submitSet(this.generation.get(), programs, surface, true);
		}
	}

	/** The dimension's program set: its own row, or the wildcard root folder's. */
	static DimensionPrograms programsOf(PackModel model, String dimension) {
		for (DimensionPrograms programs : model.dimensions()) {
			if (programs.dimension().isPresent() && programs.dimension().get().id.equals(dimension)) {
				return programs;
			}
		}
		return model.rootFolder();
	}

	private static String dimensionOf(Minecraft mc) {
		var level = mc.level;
		return level != null && level.dimension() != null ? level.dimension().identifier().toString()
			: "minecraft:overworld";
	}

	/**
	 * The engine's 1x1 default normal map: the documented default of {@code normals} with no PBR pack, RGBA (127, 127, 255, 255)
	 * ({@link TextureImage#DEFAULT_NORMAL}; engine-pack-format.md section 10). A gbuffers program's {@code normals} reads it.
	 */
	public static long makeDefaultNormals(long ctx) {
		int[] t = TextureImage.DEFAULT_NORMAL;
		return makeTexel(ctx, t[0], t[1], t[2], t[3]);
	}

	/** The engine's 1x1 default specular map: the documented default of {@code specular} with no PBR pack, RGBA (0, 0, 0, 0). */
	public static long makeDefaultSpecular(long ctx) {
		int[] t = TextureImage.DEFAULT_SPECULAR;
		return makeTexel(ctx, t[0], t[1], t[2], t[3]);
	}

	/** A 1x1 RGBA8 texture holding one texel, sampled like the atlas (shader-read, private storage); 0 when refused. */
	static long makeTexel(long ctx, int r, int g, int b, int a) {
		long texture = ShaderNative.textureNew(ctx, 70 /* RGBA8Unorm */, ShaderNative.TYPE_2D, 1, 1, 1, 1,
			ShaderNative.USAGE_SHADER_READ, ShaderNative.STORAGE_PRIVATE);
		if (texture != 0 && !ShaderNative.textureUpload(ctx, texture, new byte[] {(byte) r, (byte) g, (byte) b, (byte) a},
				4L, 0, 0, 0, 0, 0, 0, 1, 1, 1)) {
			MetalBridge.release(texture);
			return 0;
		}
		return texture;
	}

	/**
	 * noisetex until E6 loads {@code texture.noise} (plan.md E3): a hash noise texture, RGB8 served as RGBA8 (Metal
	 * has no 24-bit formats), the size the pack's {@code noiseTextureResolution} names.
	 */
	static long makeNoise(long ctx, int size) {
		byte[] texels = new byte[size * size * 4];
		long seed = 0x2f6e2b1;
		for (int i = 0; i < texels.length; i += 4) {
			seed = seed * 6364136223846793005L + 1442695040888963407L;
			int value = (int) (seed >>> 40) & 0xff;
			texels[i] = (byte) value;
			texels[i + 1] = (byte) (value ^ 0xa5);
			texels[i + 2] = (byte) ((value * 31) & 0xff);
			texels[i + 3] = (byte) 0xff;
		}
		long texture = ShaderNative.textureNew(ctx, 70 /* RGBA8Unorm */, ShaderNative.TYPE_2D, size, size, 1, 1,
			ShaderNative.USAGE_SHADER_READ, ShaderNative.STORAGE_PRIVATE);
		if (texture != 0) {
			ShaderNative.textureUpload(ctx, texture, texels, size * 4L, 0, 0, 0, 0, 0, 0, size, size, 1);
		}
		return texture;
	}

	/**
	 * The engine's world-copy pipeline for a target format (the full-screen draw of a format-changing copy); 0 when
	 * the device refused it. The render-thread activation and the headless tests build it the same way.
	 */
	static long copyPipeline(long ctx, int targetFormat) {
		ShaderNative.Compiled copy = ShaderNative.libraryNew(ctx, COPY_MSL, ShaderNative.MSL_3_1,
			ShaderNative.MATH_FAST, true);
		if (!copy.ok()) {
			LOG.error("[mcms-shaders] the engine's copy program did not compile: {}", copy.error());
			return 0;
		}
		long library = copy.library();
		try {
			return MetalBridge.pipelineNew(ctx, library, "qs_copy_vs", library, "qs_copy_fs",
				new int[] {0, 0, 1, targetFormat, 15, 0, 0, 0, 0, 0, 0, 0, 0, 3});
		} finally {
			MetalBridge.release(library);
		}
	}

	/** A resource reload (F3+T): the pack and its sets load again from the files (plan.md E3's release rule). */
	public void onResourceReload() {
		if (this.state != State.OFF) {
			String pack = this.packName;
			turnOff();
			this.packName = pack;
		}
	}

	/**
	 * The reload hook (EngineResourceReloadMixin): the game's first load is not a reload — the engine has not read
	 * the pack for a first time yet, and restarting the load there made the pack load twice at start (the review).
	 * Every later completion is a real reload.
	 */
	public void onResourceLoadFinished() {
		refreshAtlases();
		if (!this.sawFirstLoad) {
			this.sawFirstLoad = true;
			return;
		}
		onResourceReload();
	}

	/**
	 * Reads the game's atlases into the table the per-draw atlasSize reads. The game makes its atlases on load, so this runs when
	 * a set installs and when a resource load finishes (the first load included); a frame does not visit them.
	 */
	private void refreshAtlases() {
		Minecraft mc = Minecraft.getInstance();
		AtlasManager manager = mc == null ? null : mc.getAtlasManager();
		if (manager == null) {
			this.atlases.refresh(null);
		} else {
			this.atlases.refresh(manager::forEach);
		}
	}

	/**
	 * A failed load leaves no takeover and no set installed (E4 review item 5): a failure on the loader (another
	 * dimension's set threw) keeps the redirector and the active set otherwise, and the passes would keep redirecting
	 * while every hook is inert. Idempotent; the frame start calls it while the state is FAILED.
	 */
	void releaseAfterFailure() {
		if (this.state == State.FAILED && (this.redirector != null || this.active != null)) {
			releaseGpu();
		}
	}

	/** Marks the engine failed (tests: the loader's failure path without a background load). */
	void failForTest() {
		this.state = State.FAILED;
	}

	/**
	 * The device closes: everything GPU-held is released (plan.md E3's release rule). A running background build stops at
	 * its next stop point (before its next translation or Metal call; its request is replaced by the turn-off), and this
	 * waits for the loader to end its last task: no Metal call may outlive the device.
	 */
	public void shutdown() {
		turnOff();
		ExecutorService service = this.loader;
		if (service != null) {
			service.shutdown();
			this.loader = null;
			awaitLoader(service);
		}
	}

	/** Waits, bounded, for the loader's last task. A build stops before its next Metal call, so the wait is short in practice. */
	private static void awaitLoader(ExecutorService service) {
		try {
			if (!service.awaitTermination(LOADER_STOP_SECONDS, TimeUnit.SECONDS)) {
				LOG.error("[mcms-shaders] the background load did not stop within {} s (the device may close under it)",
					LOADER_STOP_SECONDS);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** The loaded model (tests and later milestones). */
	public @Nullable PackModel model() {
		return this.model;
	}

	/** The active set (tests). */
	public @Nullable DimensionSet activeSet() {
		return this.active;
	}
}
