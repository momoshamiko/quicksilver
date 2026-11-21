// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.blockentity;

import com.mojang.renderpearl.api.commands.RenderPass;
import dev.mcms.shaders.runtime.Engine;
import dev.mcms.shaders.runtime.gbuffers.DrawLog;
import dev.mcms.shaders.runtime.gbuffers.GbufferDelegate;
import dev.mcms.shaders.runtime.gbuffers.GbufferMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame;
import org.jspecify.annotations.Nullable;

/**
 * The block-entity split (plan.md E4, the pipeline-mapping bullet's block-entity rule): the game batches entity and
 * block-entity submits into one collector ({@code LevelRenderer.submitFeatures} passes the same collector to
 * {@code submitEntities} at bytecode 14 and to {@code submitBlockEntities} at bytecode 31, javap of
 * minecraft-merged-deobf-26.3.jar 2026-11-14), and {@code RenderTypeFeatureRenderer$Group} merges every submit that
 * shares a render type into one draw — so a chest's {@code entity_cutout} vertices land in a mob's draw and no
 * per-draw choice can tell them apart. The router therefore draws the block entities the way E4-C's
 * {@code HandRenderer} draws the hand:
 *
 * <ol>
 * <li>{@link #storage} feeds the game's own {@code submitBlockEntities} code the router's {@link SubmitNodeStorage}
 * (a {@code @WrapOperation} on the call in {@code LevelRenderer.submitFeatures} swaps the collector argument, so the
 * method body runs unchanged and no invoker re-enters it — an invoker of a method our mixins inject into recurses,
 * the 2026-11-14 preview 8 crash), and {@link #prepare} prepares the frame on a second
 * {@link FeatureRenderDispatcher} whose staged buffer is an engine one ({@code prepareFrame} uploads it, bytecode 295
 * of {@code prepareFrameWithContext}), so every block-entity submit — the model path, sign text, banner patterns,
 * beacon beams — lands in the engine's storage. The per-entity id capture keeps working unchanged: the submit hooks
 * and the staged-buffer range hooks are type-wide mixins, and the router's buffer carries its own ranges, so
 * {@code blockEntityId} still resolves per vertex at slot 20 (each staged upload publishes its ids to
 * {@code GbufferDelegate.publishVertexIds}, keyed by its vertex buffer: the router's and the world's alike).</li>
 * <li>{@link #drawSolid} at the phase hook, before the {@code depthtex2} copy (block entities are opaque-phase
 * geometry, so the copied depth includes them): the frame's {@code executeSolid} on the open game pass, inside a
 * {@link GbufferMapping.Domain#BLOCK_ENTITY} window, so {@code GbufferDelegate} maps the pipelines to the block
 * programs ({@code GbufferMapping}'s routing rules).</li>
 * <li>{@link #worldPhaseDraw} after the world frame's own {@code executeTranslucent}, {@code executeSeeThrough},
 * {@code executeAlwaysOnTop} and {@code executeOutline} (a mixin on those methods, guarded by the world frame's
 * identity, which {@link #drawSolid} records from the main-pass lambda's parameter): the same phases of the
 * block-entity frame, in the same window. {@code executeTranslucentAfterTerrain} and {@code waterMask} are not drawn:
 * no block-entity submit lands in them ({@code submitQuadParticleGroup} and shape outlines own after-terrain, water
 * masking is terrain's, engine-entity-draws.md section 1's phase table).</li>
 * </ol>
 *
 * <p>Lifecycle: {@link #afterLevel} at the after-level hook closes a frame the phase draws never ran; the buffer's
 * {@code endFrame} runs beside the game's every frame; {@link #close} (a {@code GbufferDelegate} release listener)
 * closes the dispatcher and the buffer on engine off, pack switch, resource reload and device close. Inert while the
 * takeover serves no world: the submit mixin keeps the game's call, one state read.
 */
public final class BlockEntityRouter {

	private static final BlockEntityRouter INSTANCE = new BlockEntityRouter();

	public static BlockEntityRouter get() {
		return INSTANCE;
	}

	/** The game's staged-buffer size ({@code RenderBuffers.<init>}: 4194304, javap 2026-11-14). */
	private static final int STAGED_BUFFER_SIZE = 4 * 1024 * 1024;

	/** Which of the world frame's phase executions finished (the PreparedFrame mixin names it). */
	public enum WorldPhase {
		TRANSLUCENT, SEE_THROUGH, ALWAYS_ON_TOP, OUTLINE
	}

	private @Nullable GameBlockEntities game;
	/** This frame's prepared block-entity frame (null while none was prepared). */
	private @Nullable PreparedFrame frame;
	/**
	 * The two domain-window draws, bound once (E4 review item 12: a lambda per frame and phase would allocate). Their
	 * arguments are the fields below, set for the duration of the window and cleared after it.
	 */
	private final Runnable solidBody = this::runSolid;
	private final Runnable phaseBody = this::runWorldPhase;
	private @Nullable PreparedFrame drawFrame;
	private @Nullable RenderPass drawPass;
	private @Nullable WorldPhase drawPhase;
	/** The world's prepared frame of this frame (its identity gates the world-phase draws); set by {@link #drawSolid}. */
	private @Nullable Object worldFrame;

	private BlockEntityRouter() {
	}

	/** Whether the engine routes block entities: active and its takeover serves the world (else the game's call runs). */
	private boolean active() {
		return Engine.get().drawsBlockEntities();
	}

	/** The engine's staged buffer exists (its {@code endFrame} must run beside the game's). */
	public boolean bufferExists() {
		return this.game != null;
	}

	/** Runs on the engine's release paths (engine off, pack switch, resource reload, device close). */
	private final Runnable release = this::close;

	/**
	 * The router's submit storage, for the game's own {@code submitBlockEntities} code to submit into (the submit
	 * mixin's {@code @WrapOperation} passes it in place of the game's collector; creating the storage makes the
	 * dispatcher and its staged buffer on first use).
	 */
	public SubmitNodeStorage storage() {
		return game().storage;
	}

	/**
	 * Prepares the block-entity frame, right after the wrapped submit returned ({@code prepareFrame} uploads the
	 * staged buffer, bytecode 295 of {@code prepareFrameWithContext}). A frame still open from a frame that never
	 * drew is closed first (the leak guard).
	 */
	public void prepare() {
		GameBlockEntities g = this.game;
		if (g == null) {
			return;
		}
		closeFrame();
		g.frame = g.dispatcher.prepareFrame(g.storage);
		this.frame = g.frame;
		if (DrawLog.on()) {
			StringBuilder sb = DrawLog.line();
			sb.append("BLOCKENTITIES f=").append(DrawLog.frameNumber()).append(" prepared=")
				.append(g.frame != null && !g.frame.isEmpty() ? 1 : 0);
			DrawLog.emit(sb);
		}
	}

	/**
	 * The block entities' solid phase, at the phase hook (after the world's {@code executeSolid}, before the
	 * {@code depthtex2} copy), on the open game pass; also records the world frame's identity for the world-phase
	 * draws. A no-op when no frame was prepared this frame.
	 */
	public void drawSolid(Object renderPass, Object preparedWorldFrame) {
		this.worldFrame = preparedWorldFrame;
		PreparedFrame f = this.frame;
		if (f == null || f.isEmpty()) {
			return;
		}
		GbufferDelegate delegate = GbufferDelegate.active();
		if (delegate == null) {
			return;
		}
		this.drawFrame = f;
		this.drawPass = (RenderPass) renderPass;
		try {
			delegate.passDomain(GbufferMapping.Domain.BLOCK_ENTITY, this.solidBody);
		} finally {
			this.drawFrame = null;
			this.drawPass = null;
		}
	}

	/** The solid body of {@link #drawSolid}: the frame's solid phase on the pass it was given. */
	private void runSolid() {
		PreparedFrame f = this.drawFrame;
		RenderPass pass = this.drawPass;
		if (f != null && pass != null) {
			f.executeSolid(pass);
		}
	}

	/**
	 * One of the world frame's phase executions finished ({@code PreparedFrame.executeTranslucent} and its siblings at
	 * their RETURN): when {@code worldFrame} is the world's prepared frame, the block-entity frame's same phase draws
	 * after it, in the block-entity domain window. Every other frame (the hand's, the screen effects') is not the
	 * world's and draws nothing here.
	 */
	public void worldPhaseDraw(Object preparedFrame, Object renderPass, WorldPhase phase) {
		if (this.worldFrame == null || preparedFrame != this.worldFrame) {
			return;
		}
		PreparedFrame f = this.frame;
		if (f == null || f.isEmpty()) {
			return;
		}
		GbufferDelegate delegate = GbufferDelegate.active();
		if (delegate == null) {
			return;
		}
		this.drawFrame = f;
		this.drawPass = (RenderPass) renderPass;
		this.drawPhase = phase;
		try {
			delegate.passDomain(GbufferMapping.Domain.BLOCK_ENTITY, this.phaseBody);
		} finally {
			this.drawFrame = null;
			this.drawPass = null;
			this.drawPhase = null;
		}
	}

	/** The phase body of {@link #worldPhaseDraw}: the block-entity frame's execution of the phase it was given. */
	private void runWorldPhase() {
		PreparedFrame f = this.drawFrame;
		RenderPass pass = this.drawPass;
		WorldPhase phase = this.drawPhase;
		if (f == null || pass == null || phase == null) {
			return;
		}
		switch (phase) {
			case TRANSLUCENT -> f.executeTranslucent(pass);
			case SEE_THROUGH -> f.executeSeeThrough(pass);
			case ALWAYS_ON_TOP -> f.executeAlwaysOnTop(pass);
			case OUTLINE -> f.executeOutline(pass);
		}
	}

	/** The after-level hook: a frame the phase draws never ran is closed here, so no frame leaks into the next. */
	public void afterLevel() {
		if (this.game != null) {
			closeFrame();
		}
		this.worldFrame = null;
	}

	/** Beside the game's {@code RenderBuffers.endFrame()}, every frame while the buffer exists. */
	public void onBufferEndFrame() {
		GameBlockEntities g = this.game;
		if (g != null) {
			g.buffer.endFrame();
		}
	}

	/** Closes the frame, the dispatcher and its buffer (engine off, pack switch, resource reload, device close). */
	public void close() {
		closeFrame();
		GameBlockEntities g = this.game;
		if (g != null) {
			g.dispatcher.close();
			g.buffer.close();
			this.game = null;
			GbufferDelegate.removeReleaseListener(this.release);
			this.worldFrame = null;
		}
	}

	private void closeFrame() {
		GameBlockEntities g = this.game;
		if (g != null && g.frame != null) {
			g.frame.close();
			g.frame = null;
		}
		this.frame = null;
	}

	private GameBlockEntities game() {
		GameBlockEntities g = this.game;
		if (g == null) {
			GbufferDelegate.addReleaseListener(this.release);  // the release paths close the dispatcher and buffer
			Minecraft mc = Minecraft.getInstance();
			GameRenderer gameRenderer = mc.gameRenderer;
			RenderBuffers renderBuffers = gameRenderer.renderBuffers();
			FeatureRenderDispatcher dispatcher = new FeatureRenderDispatcher(renderBuffers, mc.getModelManager(),
				mc.getAtlasManager(), mc.font, gameRenderer.gameRenderState());
			StagedVertexBuffer buffer = new StagedVertexBuffer(() -> "Quicksilver: block entities", STAGED_BUFFER_SIZE);
			((dev.mcms.metal.mixin.EngineHandFeatureDispatcherAccessor) (Object) dispatcher)
				.mcms$setStagedVertexBuffer(buffer);
			g = new GameBlockEntities(dispatcher, buffer, new SubmitNodeStorage());
			this.game = g;
		}
		return g;
	}

	/** The second dispatcher, its engine staged buffer and the router's submit storage. */
	private static final class GameBlockEntities {
		final FeatureRenderDispatcher dispatcher;
		final StagedVertexBuffer buffer;
		final SubmitNodeStorage storage;
		@Nullable PreparedFrame frame;

		GameBlockEntities(FeatureRenderDispatcher dispatcher, StagedVertexBuffer buffer, SubmitNodeStorage storage) {
			this.dispatcher = dispatcher;
			this.buffer = buffer;
			this.storage = storage;
		}
	}
}
