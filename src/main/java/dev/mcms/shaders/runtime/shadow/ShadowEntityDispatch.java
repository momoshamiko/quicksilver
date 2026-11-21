// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.shadow;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.mcms.metal.mixin.EngineHandFeatureDispatcherAccessor;
import dev.mcms.shaders.runtime.gbuffers.GbufferDelegate;
import dev.mcms.shaders.runtime.gbuffers.GbufferMapping;
import dev.mcms.shaders.runtime.terrain.ShadowTerrain;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The shadow frame's entity dispatch (E5, plan.md "Shadow dispatch"). Two pipelines, as the camera's entities and block
 * entities are split (E4's {@code BlockEntityRouter}): the shadow entities, and the block entities of the light's sections
 * ({@link ShadowTerrain#forEachBlockEntity}). Each pipeline has its own {@link SubmitNodeStorage}, its own
 * {@link FeatureRenderDispatcher} with an engine {@link StagedVertexBuffer} (the world's dispatcher and its open frame stay
 * untouched), and its own prepared frame, prepared before the shadow pass opens and closed after it.
 *
 * <p>Routing (E5 review item 8): the entity pipeline draws inside {@code GbufferMapping.Domain.ENTITY}, the block-entity
 * pipeline inside {@code Domain.BLOCK_ENTITY}, so a block entity's draws map to {@code shadow_block} and the entities' to
 * {@code shadow_entities} ({@link #route}).
 *
 * <p>Entities: the game's own per-entity call ({@code EntityRenderDispatcher.submit(state, camera, x, y, z, pose,
 * collector)}, javap 26.3): {@code x = state.x - camX}, and likewise y and z ({@code LevelRenderer.submitEntities},
 * bytecodes 73-99). The states are extracted camera-relative, so the offset is the state's position minus the camera's.
 *
 * <p>Block entities: the game's own sequence ({@code LevelRenderer.submitBlockEntities}, javap 26.3): the pose is pushed,
 * translated by the block position minus the camera position, the render state is submitted and the pose popped. The
 * render state is extracted with {@code BlockEntityRenderDispatcher.tryExtractRenderState} at the game's partial tick,
 * with the flag the game's main extraction passes (false, {@code LevelExtractor} bytecodes 297-298): a renderer whose
 * {@code shouldRenderOffScreen()} differs gets null (bytecodes 37-50).
 *
 * <p>Lifecycle: {@link #endFrameOfActive} at the game's {@code RenderBuffers.endFrame} (GameRenderer.render bytecode 379,
 * {@code EngineShadowBufferEndFrameMixin}) ends each staged buffer's frame, so its GPU buffers return to the pool. Without
 * it each frame would take new buffers. {@link #close} releases both dispatchers and buffers (the set's release).
 *
 * <p>Single owner: the render thread. The storages, the pose stack and the walk's consumer are reused every frame (the
 * prepare drains the storage, as the hand's does).
 */
public final class ShadowEntityDispatch {

	/** The staged vertex buffer's size: the game's own (RenderBuffers.&lt;init&gt;, javap 2026-11-14). */
	private static final int STAGED_BUFFER_SIZE = 4 * 1024 * 1024;

	/** The live dispatch of the active set (one set at a time, on the render thread); see {@link #endFrameOfActive}. */
	private static @Nullable ShadowEntityDispatch active;

	/** The two phases of a shadow draw. */
	enum Phase {
		SOLID, TRANSLUCENT
	}

	/** One pipeline's draws of a phase on the open pass. */
	interface Drawable {
		void draw(RenderPass pass, Phase phase);
	}

	/** Runs {@code draws} inside a domain window ({@link GbufferDelegate#passDomain}). */
	interface DomainWindow {
		void within(GbufferMapping.Domain domain, Runnable draws);
	}

	/** The game's per-entity submit ({@code EntityRenderDispatcher.submit}, javap 26.3). */
	interface EntitySubmit {
		void submit(EntityRenderState state, CameraRenderState camera, double x, double y, double z, PoseStack pose,
			SubmitNodeCollector collector);
	}

	/**
	 * One submit pipeline: the storage its submits go to, the dispatcher that prepares them, the staged buffer the dispatcher
	 * uploads through, and the open prepared frame (null while none).
	 */
	static final class Pipeline implements Drawable {
		final SubmitNodeStorage storage = new SubmitNodeStorage();
		final FeatureRenderDispatcher dispatcher;
		final StagedVertexBuffer buffer;
		private @Nullable PreparedFrame frame;

		Pipeline(FeatureRenderDispatcher dispatcher, StagedVertexBuffer buffer) {
			this.dispatcher = dispatcher;
			this.buffer = buffer;
		}

		/** Prepares the submitted draws (a frame still open from a frame that never drew is closed first). */
		void prepare() {
			closeFrame();
			this.frame = this.dispatcher.prepareFrame(this.storage);
		}

		@Override
		public void draw(RenderPass pass, Phase phase) {
			PreparedFrame prepared = this.frame;
			if (prepared == null) {
				return;
			}
			switch (phase) {
				case SOLID -> prepared.executeSolid(pass);
				case TRANSLUCENT -> prepared.executeTranslucent(pass);
			}
		}

		void closeFrame() {
			PreparedFrame prepared = this.frame;
			this.frame = null;
			if (prepared != null) {
				prepared.close();
			}
		}

		void endFrame() {
			this.buffer.endFrame();
		}

		void close() {
			closeFrame();
			this.dispatcher.close();
			this.buffer.close();
		}
	}

	private final @Nullable Minecraft mc;
	private final Pipeline entityPipeline;
	private final Pipeline blockPipeline;
	private final PoseStack pose = new PoseStack();
	/** The walk's consumer, bound once: a method reference per frame would allocate. */
	private final Consumer<BlockEntity> extractBlockEntity = this::submitBlockEntity;
	/** The game's per-entity submit, bound once: it reads the game's dispatcher at each call. */
	private final EntitySubmit submitEntity = (state, camera, x, y, z, poseStack, collector) ->
		mc().getEntityRenderDispatcher().submit(state, camera, x, y, z, poseStack, collector);
	/** The window the routed draws run in (the delegate's domain window), and the routed draws; bound once in the constructor. */
	private final DomainWindow window;
	private final Runnable entityDraws;
	private final Runnable blockEntityDraws;
	private final GbufferDelegate delegate;
	private @Nullable RenderPass routePass;
	private Phase routePhase = Phase.SOLID;
	private @Nullable CameraRenderState blockCamera;
	private @Nullable BlockEntityRenderDispatcher blockDispatcher;
	private float blockPartialTick;

	/** Creates the dispatch with the game's render buffers and atlases (the render thread, with a game renderer). */
	public ShadowEntityDispatch(Minecraft mc, GbufferDelegate delegate) {
		this(mc, delegate, pipeline(mc, "Quicksilver: shadow entities"), pipeline(mc, "Quicksilver: shadow block entities"));
	}

	/** The dispatch over the given pipelines; {@code mc} supplies the game's dispatchers at submit time (null in tests that never submit). */
	ShadowEntityDispatch(@Nullable Minecraft mc, GbufferDelegate delegate, Pipeline entities, Pipeline blocks) {
		this.mc = mc;
		this.delegate = delegate;
		this.entityPipeline = entities;
		this.blockPipeline = blocks;
		this.window = (domain, draws) -> this.delegate.passDomain(domain, draws);
		this.entityDraws = () -> this.entityPipeline.draw(routePass(), this.routePhase);
		this.blockEntityDraws = () -> this.blockPipeline.draw(routePass(), this.routePhase);
		active = this;
	}

	/**
	 * A dispatch whose pipelines hold no game dispatcher, for a set installed without the game (the engine's install tests).
	 * Its submits need the game and fail, so it is never given entities to draw.
	 */
	public static ShadowEntityDispatch withoutGame(GbufferDelegate delegate) {
		return new ShadowEntityDispatch(null, delegate, headless("Quicksilver: shadow entities"),
			headless("Quicksilver: shadow block entities"));
	}

	private static Pipeline headless(String label) {
		FeatureRenderDispatcher dispatcher = new FeatureRenderDispatcher(new RenderBuffers(1), null, null, null, null);
		return new Pipeline(dispatcher, new StagedVertexBuffer(() -> label, STAGED_BUFFER_SIZE));
	}

	private static Pipeline pipeline(Minecraft mc, String label) {
		GameRenderer gameRenderer = mc.gameRenderer;
		FeatureRenderDispatcher dispatcher = new FeatureRenderDispatcher(gameRenderer.renderBuffers(), mc.getModelManager(),
			mc.getAtlasManager(), mc.font, gameRenderer.gameRenderState());
		StagedVertexBuffer buffer = new StagedVertexBuffer(() -> label, STAGED_BUFFER_SIZE);
		((EngineHandFeatureDispatcherAccessor) (Object) dispatcher).mcms$setStagedVertexBuffer(buffer);
		return new Pipeline(dispatcher, buffer);
	}

	/**
	 * The game's per-entity submit for every shadow entity of this frame's list (the second extraction): the states' offsets
	 * from the camera, through {@link #submitStates}.
	 */
	public void submit(CameraRenderState camera) {
		submitStates(ShadowEntities.get().entityStates(), camera, this.submitEntity, this.pose, this.entityPipeline.storage);
	}

	/**
	 * The game's entity submit over {@code states}: each state's position minus the camera's, as the game's
	 * {@code LevelRenderer.submitEntities} passes it (javap 26.3, bytecodes 73-99), not the camera's position.
	 */
	static void submitStates(List<EntityRenderState> states, CameraRenderState camera, EntitySubmit submit, PoseStack pose,
			SubmitNodeCollector collector) {
		Vec3 position = camera.pos;
		double camX = position.x;
		double camY = position.y;
		double camZ = position.z;
		for (EntityRenderState state : states) {
			submit.submit(state, camera, state.x - camX, state.y - camY, state.z - camZ, pose, collector);
		}
	}

	/** Submits the block entities of the light's culled sections into the block pipeline (the game's submit, per block entity). */
	public void submitBlockEntities(CameraRenderState camera) {
		this.blockCamera = camera;
		this.blockDispatcher = mc().getBlockEntityRenderDispatcher();
		this.blockPartialTick = mc().getDeltaTracker().getGameTimeDeltaPartialTick(true);
		ShadowTerrain.get().forEachBlockEntity(this.extractBlockEntity);
		this.blockCamera = null;
		this.blockDispatcher = null;
	}

	private void submitBlockEntity(BlockEntity entity) {
		CameraRenderState camera = this.blockCamera;
		BlockEntityRenderDispatcher dispatcher = this.blockDispatcher;
		if (camera == null || dispatcher == null) {
			return;
		}
		// The flag is the game's main extraction's (LevelExtractor bytecodes 297-298): see the class comment.
		BlockEntityRenderState state = dispatcher.tryExtractRenderState(entity, this.blockPartialTick, null, false);
		if (state == null) {
			return;
		}
		BlockPos at = state.blockPos;
		Vec3 position = camera.pos;
		this.pose.pushPose();
		this.pose.translate(at.getX() - position.x, at.getY() - position.y, at.getZ() - position.z);
		dispatcher.submit(state, this.pose, this.blockPipeline.storage, camera);
		this.pose.popPose();
	}

	/** Prepares both pipelines (before the shadow pass opens). */
	public void prepare() {
		this.entityPipeline.prepare();
		this.blockPipeline.prepare();
	}

	/** The solid phase of the entity and block-entity pipelines, inside the shadow pass. */
	public void executeSolid(RenderPass pass) {
		run(pass, Phase.SOLID);
	}

	/** The translucent phase of the entity and block-entity pipelines, inside the shadow pass. */
	public void executeTranslucent(RenderPass pass) {
		run(pass, Phase.TRANSLUCENT);
	}

	private void run(RenderPass pass, Phase phase) {
		this.routePass = pass;
		this.routePhase = phase;
		try {
			route(this.entityDraws, this.blockEntityDraws, this.window);
		} finally {
			this.routePass = null;
		}
	}

	private RenderPass routePass() {
		RenderPass pass = this.routePass;
		if (pass == null) {
			throw new IllegalStateException("a shadow entity draw with no shadow render pass open");
		}
		return pass;
	}

	/**
	 * One phase of the shadow draws (E5 review item 8): the entities' draws inside the ENTITY window, then the block entities'
	 * inside the BLOCK_ENTITY window, so {@code GbufferMapping}'s routing rules pick the block programs for block entities.
	 */
	static void route(Runnable entities, Runnable blockEntities, DomainWindow window) {
		window.within(GbufferMapping.Domain.ENTITY, entities);
		window.within(GbufferMapping.Domain.BLOCK_ENTITY, blockEntities);
	}

	/** Closes both prepared frames (after the pass, before the hand's prepare at bytecode 770). Idempotent. */
	public void closeFrame() {
		this.entityPipeline.closeFrame();
		this.blockPipeline.closeFrame();
	}

	/** Ends both staged buffers' frames: their GPU buffers return to their pools (beside the game's RenderBuffers.endFrame). */
	public void endFrame() {
		this.entityPipeline.endFrame();
		this.blockPipeline.endFrame();
	}

	/** The game's end of frame for the active dispatch, if a set has one (the mixin calls it beside RenderBuffers.endFrame). */
	public static void endFrameOfActive() {
		ShadowEntityDispatch dispatch = active;
		if (dispatch != null) {
			dispatch.endFrame();
		}
	}

	/** Releases both dispatchers and their buffers (the set's release). */
	public void close() {
		closeFrame();
		this.entityPipeline.close();
		this.blockPipeline.close();
		if (active == this) {
			active = null;
		}
	}

	private Minecraft mc() {
		Minecraft minecraft = this.mc;
		if (minecraft == null) {
			throw new IllegalStateException("the shadow dispatch was built without the game and cannot submit");
		}
		return minecraft;
	}
}
