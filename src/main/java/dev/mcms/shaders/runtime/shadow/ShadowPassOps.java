// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.shadow;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.ToIntFunction;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import dev.mcms.metal.MetalBridge;
import dev.mcms.metal.mixin.EngineLevelChunkSamplerAccessor;
import dev.mcms.metal.mixin.SodiumEngineRenderSectionManagerAccessor;
import dev.mcms.metal.mixin.SodiumEngineWorldRendererAccessor;
import dev.mcms.shaders.runtime.DrawDumps;
import dev.mcms.shaders.runtime.gbuffers.DrawLog;
import dev.mcms.shaders.runtime.gbuffers.GbufferDelegate;
import dev.mcms.shaders.runtime.gbuffers.PassKind;
import dev.mcms.shaders.runtime.terrain.ShadowTerrain;
import dev.mcms.shaders.runtime.terrain.TerrainEngine;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The production binding of the shadow frame's operations (E5; {@link ShadowFrame.Ops}), run by the engine's shadow hook at
 * {@code LevelRenderer.render} bytecode 79 (javap-checked in {@code EngineShadowFrameMixin}), one frame at a time.
 *
 * <p>The shadow pass changes no camera state, and the frame's order makes the globals mirror correct for both passes:
 * <ul>
 * <li>Terrain globals come from the shadow pass's own {@link UniformBufferManager} (built with the world's render distance,
 * as Sodium builds the camera's). Its update goes through {@link TerrainEngine#adapter()} with the light's own
 * {@link CameraTransform} (beta.5 takes it; beta.1 ignores it). The update's bytes reach the u_Globals mirror, which the
 * shadow draws read. The camera's manager is not touched: it has its own once-per-frame update, and that update runs at the
 * head of the camera's first terrain layer ({@code SodiumWorldRenderer.renderLayer} calls {@code update} before any draw;
 * javap 2026-11-14, beta.1 and beta.5). The camera's first terrain layer is after this pass in the frame, so the mirror holds
 * the camera's bytes again before any camera terrain draw. No other draw reads the mirror's matrices: only the terrain
 * prologue does (GbufferDelegate).</li>
 * <li>The delegate's kind window is ended on every path (also when the pass fails to open or to close).</li>
 * <li>The pass is opened with the game's default uniforms, as the game's and the hand's passes are ({@code
 * RenderSystem.bindDefaultUniforms}, javap 26.3).</li>
 * <li>Every debug group the game left open on the shadow pass is popped before the pass closes (the frontend refuses to
 * close a pass with groups open, and then the encoder keeps its pass).</li>
 * </ul>
 *
 * <ul>
 * <li>Entities: {@link ShadowEntityDispatch} submits the shadow entities and block entities, prepares them before the pass,
 * draws their solid and translucent parts inside it, and closes the prepared frame after it (before the hand's prepare at
 * bytecode 770).</li>
 * <li>Clear (E9): the pass clears its own attachments, {@code shadowtex0} to 1.0 and each colour attachment the pack clears,
 * in its load actions ({@link ShadowTargets#clearForPass}, taken by the redirect of the frame's first open), so no clear
 * pass runs ahead of it on the GPU and its vertex work can start early. A colour buffer the pack clears that the pass does
 * not write gets its own clear pass. {@code shadowtex1} needs no clear: the copy below overwrites it whole; a frame whose
 * pass ended before that copy clears it then, and a frame whose pass did not open clears every map.</li>
 * <li>Pass: {@link GbufferDelegate#beginKind} with {@link PassKind#SHADOW}, then a game render pass the redirector takes
 * into the shadow targets. The view is bound right after the open: {@link GbufferDelegate#shadowView}.</li>
 * <li>Terrain: {@link ShadowTerrain#bindDraw} with the chunk renderer of the section manager, the fog of the last camera
 * frame ({@code lastFogParameters}), the block sampler (see {@link #bindTerrain}) and the shadow manager's buffers.</li>
 * <li>{@code shadowtex1}: the pass is ended, {@code shadowtex0}'s depth is blitted into {@code shadowtex1} outside any render
 * pass, and the pass is opened again (loads, the redirect keeps the attachments).</li>
 * <li>{@link ShadowTargets#changed}: marked after the pass wrote the maps, so their mip chains refill before a read.</li>
 * <li>{@code shadowcomp}: the set's shadowcomp fragment passes run through {@link ShadowComposites} after the pass (their
 * compute parts are E6's); the mip chains are filled in {@link #closePass}, before them.</li>
 * </ul>
 *
 * <p>Single owner: the render thread. The matrices are the camera's own {@link ShadowCamera} matrices (referenced, updated
 * in place each frame). A frame allocates nothing here except the game's pass objects (two per frame at most: the open, and
 * the reopen after the {@code shadowtex1} copy).
 */
public final class ShadowPassOps implements ShadowFrame.Ops {

	private static final Logger LOG = LoggerFactory.getLogger("Quicksilver");

	/** The names the shadow maps are dumped under ({@code DrawDumps.picture}, when the draw log is dumping). */
	private static final String DUMP_DEPTH_0 = "shadowtex0";
	private static final String DUMP_DEPTH_1 = "shadowtex1";
	private static final String DUMP_COLOUR_PREFIX = "shadowcolor";

	/**
	 * FrontendRenderPass's private count of its open debug groups (javap 26.3: {@code pushedDebugGroups}, an int). Read with
	 * this handle, so a pass with no groups open costs no exception. Null when the field cannot be reached: then the pass's
	 * groups are popped until the frontend refuses a pop (see {@link #openDebugGroups}).
	 */
	private static final @Nullable VarHandle OPEN_DEBUG_GROUPS = openDebugGroupsHandle();
	/** The counter's receiver class, kept so the per-frame check allocates nothing (VarHandle.coordinateTypes copies a list). */
	private static final @Nullable Class<?> DEBUG_GROUP_RECEIVER = OPEN_DEBUG_GROUPS == null
		? null : OPEN_DEBUG_GROUPS.coordinateTypes().get(0);

	private final ShadowCamera camera;
	private final ShadowTargets targets;
	private final GbufferDelegate delegate;
	/** The shadow pass's colour attachments: shadowcolor indices in attachment order ({@code GbufferPlans.shadowLayout}). */
	private final int[] layout;
	private final ShadowEntityDispatch entities;
	/** Sodium's matrices of the light: references to the camera's live matrices, so no per-frame allocation. */
	private final ChunkRenderMatrices matrices;

	/** The shadow pass's own globals and section-time buffers (see the class comment); null until the first terrain bind. */
	private @Nullable UniformBufferManager shadowUniforms;
	/** The render section manager {@link #shadowUniforms} was built for: a new world renderer makes a new manager. */
	private @Nullable RenderSectionManager shadowUniformsOwner;
	/** Whether the shadow manager has begun this frame ({@code prepareFrame}) and needs its {@code endFrame}. */
	private boolean uniformsBegun;

	/** The frame's backend encoder and its native handle, set by {@link #begin} before each frame's run. */
	private @Nullable Object encoder;
	private long enc;
	/** The game's camera of this frame (the entity submit's position), set by {@link #begin}. */
	private @Nullable CameraRenderState frameCamera;
	/** The open game render pass of the shadow pass (null between the open and the close). */
	private @Nullable RenderPass pass;
	private boolean terrainWarned;
	/** Whether this frame's pass copied {@code shadowtex0} into {@code shadowtex1} (else the close clears it). */
	private boolean shadowtex1Copied;
	private boolean listWaitLogged;
	private boolean samplerWaitLogged;

	/**
	 * @param camera   the set's shadow camera (its matrices must be updated by the frame before the pass)
	 * @param targets  the set's shadow targets, created
	 * @param layout   the shadow pass's colour attachments, shadowcolor indices in attachment order
	 * @param delegate the active set's delegate (the shadow window and the shadow view go through it)
	 * @param entities the shadow entity dispatch
	 */
	public ShadowPassOps(ShadowCamera camera, ShadowTargets targets, int[] layout, GbufferDelegate delegate,
			ShadowEntityDispatch entities) {
		this.camera = camera;
		this.targets = targets;
		this.layout = layout.clone();
		this.delegate = delegate;
		this.entities = entities;
		this.matrices = new ChunkRenderMatrices(camera.projection(), camera.modelView());
	}

	/** Sets the frame's encoder and camera for the run that follows (the engine's shadow hook calls it). */
	public void begin(Object frameEncoder, CameraRenderState cameraState) {
		this.encoder = frameEncoder;
		this.enc = MetalBridge.enc(frameEncoder);
		this.frameCamera = cameraState;
	}

	/** Releases the shadow manager (the set's release). */
	public void close() {
		if (this.shadowUniforms != null) {
			this.shadowUniforms.delete();
			this.shadowUniforms = null;
			this.shadowUniformsOwner = null;
		}
	}

	@Override
	public void submitEntities(boolean entityDraws, boolean blockEntities, boolean player) {
		if (this.frameCamera == null) {
			return;  // begin() sets the camera before every run (the engine's hook)
		}
		if (entityDraws) {
			this.entities.submit(this.frameCamera);
		}
		if (blockEntities) {
			this.entities.submitBlockEntities(this.frameCamera);
		}
	}

	@Override
	public void prepareEntities() {
		this.entities.prepare();
	}

	@Override
	public void clearTargets() {
		this.shadowtex1Copied = false;
		this.targets.clearForPass(this.enc, this.layout);
	}

	@Override
	public void openPass() {
		this.delegate.beginKind(PassKind.SHADOW);
		try {
			this.pass = openRenderPass();
			this.delegate.shadowView(this.camera.modelView(), this.camera.projection());
		} catch (Throwable e) {
			// The camera's draws must not inherit the shadow window, and a pass this step opened must not stay open.
			try {
				closeRenderPass();
			} finally {
				this.delegate.endKind();
				// The pass did not finish opening: whether or not its redirect took the clear, shadowtex1 gets no copy this
				// frame, so every map is cleared here (no frame keeps last frame's casters).
				this.targets.takePassClear();
				this.targets.clear(this.enc);
			}
			throw e;
		}
	}

	@Override
	public void bindShadowView() {
		// Bound in openPass, right after the window opens: every shadow draw reads the shadow view from then on.
	}

	@Override
	public void terrainSolidAndCutout() {
		if (terrainReady() && bindTerrain()) {
			ShadowTerrain.get().drawSolid(currentPass());
			ShadowTerrain.get().drawCutout(currentPass());
		}
	}

	@Override
	public void entitiesSolid() {
		this.entities.executeSolid(currentPass());
	}

	@Override
	public void copyShadowtex1() {
		// The blit is outside any render pass: end the open one, copy, and open the pass again (it loads, the redirect
		// keeps the attachments).
		closeRenderPass();
		MetalBridge.endEncoders(this.enc);
		MetalBridge.blitTextureToTexture(this.enc, this.targets.depth(0), this.targets.depth(1), 0,
			this.targets.resolution(), this.targets.resolution());
		this.shadowtex1Copied = true;
		this.pass = openRenderPass();
	}

	@Override
	public void terrainTranslucent() {
		if (terrainReady() && bindTerrain()) {
			ShadowTerrain.get().drawTranslucent(currentPass());
		}
	}

	@Override
	public void entitiesTranslucent() {
		this.entities.executeTranslucent(currentPass());
	}

	/**
	 * Closes the shadow pass on every path: the open pass (after its debug groups are popped), the window, the encoders and
	 * the shadow manager's frame. The maps are marked changed only when the pass closed cleanly. The maps are dumped only
	 * while the draw log dumps (a dump reads the GPU back and writes four PNG files).
	 */
	@Override
	public void closePass() {
		try {
			closeRenderPass();
		} finally {
			this.delegate.endKind();
			MetalBridge.endEncoders(this.enc);
			if (this.uniformsBegun && this.shadowUniforms != null) {
				this.shadowUniforms.endFrame();
			}
			this.uniformsBegun = false;
		}
		if (!this.shadowtex1Copied) {
			this.targets.clearDepth(this.enc, 1);  // the pass ended before its copy: shadowtex1 holds no stale casters
		}
		this.targets.changed();  // the maps were written: their mip chains are stale until refilled
		this.targets.fillMips(this.enc);  // E5 review item 10: refilled before shadowcomp and the camera frame read them
		if (DrawLog.dumping()) {
			dumpMaps();
		}
	}

	private void dumpMaps() {
		DrawDumps.picture(null, this.enc, this.targets.depth(0), DUMP_DEPTH_0, true);
		DrawDumps.picture(null, this.enc, this.targets.depth(1), DUMP_DEPTH_1, true);
		for (int c = 0; c < this.targets.colorCount(); c++) {
			DrawDumps.picture(null, this.enc, this.targets.color(c), DUMP_COLOUR_PREFIX + c, false);
		}
	}

	@Override
	public void closePrepared() {
		this.entities.closeFrame();  // the shadow prepared frame, before the hand's prepare at bytecode 770
	}

	@Override
	public void clearCachedBatches() {
		ShadowTerrain.get().clearCachedBatches();
	}

	/** Runs the set's shadowcomp passes (their fragment parts; the compute parts are E6's) on the frame's encoder. */
	@Override
	public void shadowcomp() {
		ShadowComposites.get().run(this.enc);
	}

	/** The open pass; a step that draws without one is a sequencing error and fails here. */
	private RenderPass currentPass() {
		RenderPass open = this.pass;
		if (open == null) {
			throw new IllegalStateException("a shadow draw with no shadow render pass open");
		}
		return open;
	}

	/** Closes the open pass, if any, after its debug groups are popped (see {@link #closeWithOpenGroups}). */
	private void closeRenderPass() {
		RenderPass open = this.pass;
		this.pass = null;
		if (open != null) {
			closeWithOpenGroups(open, ShadowPassOps::openDebugGroups);
		}
	}

	/** The count {@link #openDebugGroups} returns when the frontend's counter cannot be read. */
	static final int UNKNOWN_GROUPS = -1;

	/** The most pops {@link #popUntilRefused} tries: a frontend that never refuses a pop is a bug, not a stack to unwind. */
	private static final int MAX_UNCOUNTED_GROUPS = 256;

	/**
	 * Pops the debug groups the game left open on the pass, then closes it. The frontend refuses to close a pass with groups
	 * open and marks it closed first (javap 26.3, {@code FrontendRenderPass.close}), so the groups must be popped before the
	 * close. The pass is closed on every path, also when a pop throws: the close is in a finally block.
	 */
	static void closeWithOpenGroups(RenderPass open, ToIntFunction<RenderPass> openGroups) {
		try {
			int groups = openGroups.applyAsInt(open);
			if (groups == UNKNOWN_GROUPS) {
				popUntilRefused(open);
			} else {
				for (; groups > 0; groups--) {
					open.popDebugGroup();
				}
			}
		} finally {
			open.close();
		}
	}

	/** Pops until the frontend refuses: a pop with no group open throws {@link IllegalStateException} (javap 26.3). */
	private static void popUntilRefused(RenderPass open) {
		for (int pops = 0; pops < MAX_UNCOUNTED_GROUPS; pops++) {
			try {
				open.popDebugGroup();
			} catch (IllegalStateException refused) {
				return;  // no group is left open
			}
		}
	}

	/**
	 * The open debug groups of a frontend render pass: its private counter, read through {@link #OPEN_DEBUG_GROUPS}. Returns
	 * {@link #UNKNOWN_GROUPS} when the counter is missing or the pass is not a frontend pass (the counter's receiver type).
	 */
	static int openDebugGroups(RenderPass pass) {
		VarHandle counter = OPEN_DEBUG_GROUPS;
		if (counter == null || !DEBUG_GROUP_RECEIVER.isInstance(pass)) {
			return UNKNOWN_GROUPS;
		}
		return (int) counter.get(pass);
	}

	private static @Nullable VarHandle openDebugGroupsHandle() {
		try {
			Class<?> frontend = Class.forName("com.mojang.renderpearl.frontend.FrontendRenderPass");
			return MethodHandles.privateLookupIn(frontend, MethodHandles.lookup())
				.findVarHandle(frontend, "pushedDebugGroups", int.class);
		} catch (ReflectiveOperationException | RuntimeException e) {
			LOG.warn("[mcms-shaders] the shadow pass cannot read the frontend's debug-group counter: {}", e.toString());
			return null;
		}
	}

	private RenderPass openRenderPass() {
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		CommandEncoder commands = RenderSystem.getDevice().createCommandEncoder();
		RenderPass opened = commands.createRenderPass(() -> "Quicksilver: shadow", main.getColorTextureView(), Optional.empty(),
			main.getDepthTextureView(), OptionalDouble.empty());
		RenderSystem.bindDefaultUniforms(opened);  // the game's and the hand's passes bind these at their open too
		return opened;
	}

	/**
	 * Whether the light's terrain can be drawn this frame. The shadow hook can run before the first {@code setupTerrain} tail
	 * with the light attached, so a frame whose list is not built yet skips its terrain steps (logged once at INFO); the
	 * shadow frame itself continues, and later frames draw once the list exists.
	 */
	private boolean terrainReady() {
		if (ShadowTerrain.get().built()) {
			return true;
		}
		if (!this.listWaitLogged) {
			this.listWaitLogged = true;
			LOG.info("[mcms-shaders] the shadow terrain waits for its first light list (setupTerrain has not run with the light attached)");
		}
		return false;
	}

	/**
	 * Binds the shadow terrain inputs for this pass (see the class comment). Returns false, with one WARN, while the world
	 * renderer has no fog yet (the first frame of a level, before a camera frame), or no level exists; and false, with one
	 * INFO, while the game has not made its chunk-layer sampler yet (it does so in its first main pass, after this hook).
	 */
	private boolean bindTerrain() {
		SodiumWorldRenderer world = SodiumWorldRenderer.instanceNullable();
		ClientLevel level = Minecraft.getInstance().level;
		if (world == null || level == null) {
			return false;
		}
		SodiumEngineWorldRendererAccessor access = (SodiumEngineWorldRendererAccessor) world;
		GpuSampler atlasSampler = ((EngineLevelChunkSamplerAccessor) Minecraft.getInstance().levelRenderer)
			.mcmsMetal$chunkLayerSampler();
		if (atlasSampler == null) {
			if (!this.samplerWaitLogged) {
				this.samplerWaitLogged = true;
				LOG.info("[mcms-shaders] the shadow terrain waits for the game's chunk-layer sampler (made in its first main pass)");
			}
			return false;
		}
		FogParameters fog = access.mcmsMetal$lastFogParameters();
		if (fog == null) {
			if (!this.terrainWarned) {
				this.terrainWarned = true;
				LOG.warn("[mcms-shaders] the shadow terrain waits for the first camera frame's fog");
			}
			return false;
		}
		CameraTransform light = ShadowTerrain.get().lightTransform();
		if (light == null) {
			return false;  // the list is built (terrainReady), so its transform is set; this guards the type only
		}
		RenderSectionManager sections = access.mcmsMetal$renderSectionManager();
		UniformBufferManager uniforms = shadowUniforms(level, sections);
		if (!this.uniformsBegun) {
			uniforms.prepareFrame();
			this.uniformsBegun = true;
		}
		// The light's own camera transform: beta.5 writes the light's camera offset, which the light's list is offset by.
		TerrainEngine.adapter().updateGlobals(uniforms, this.matrices, fog, light);
		// The block atlas is sampled as the camera's terrain samples it: the game's chunk-layer sampler (linear, mipmapped).
		ShadowTerrain.get().bindDraw(sections.getChunkRenderer(), this.matrices, fog, atlasSampler,
			uniforms.getUniformBuffer(), uniforms.getSectionTimeInfo(), access.mcmsMetal$useTranslucencySorting());
		return true;
	}

	/**
	 * The shadow pass's own uniform manager for this world renderer's section manager, built as Sodium builds the camera's
	 * ({@code new UniformBufferManager(level, renderDistance)}, javap 26.3 beta.1). A new section manager (a world reload)
	 * makes a new manager, and the old one is deleted.
	 */
	private UniformBufferManager shadowUniforms(ClientLevel level, RenderSectionManager sections) {
		UniformBufferManager current = this.shadowUniforms;
		if (current != null && this.shadowUniformsOwner == sections) {
			return current;
		}
		close();
		int renderDistance = ((SodiumEngineRenderSectionManagerAccessor) sections).mcmsMetal$renderDistance();
		UniformBufferManager made = new UniformBufferManager(level, renderDistance);
		this.shadowUniforms = made;
		this.shadowUniformsOwner = sections;
		return made;
	}
}
