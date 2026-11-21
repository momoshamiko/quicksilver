// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.hand;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.mcms.metal.mixin.EngineHandFeatureDispatcherAccessor;
import dev.mcms.metal.mixin.EngineHandGameRendererAccessor;
import dev.mcms.shaders.runtime.Engine;
import dev.mcms.shaders.runtime.gbuffers.DrawLog;
import dev.mcms.shaders.runtime.gbuffers.GbufferDelegate;
import dev.mcms.shaders.runtime.gbuffers.PassKind;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * The engine's hand rendering (plan.md E4, "Hand"): a second {@code FeatureRenderDispatcher} whose
 * {@code stagedVertexBuffer} is an engine one, so the world's dispatcher and its open {@code PreparedFrame}
 * ({@code LevelRenderer.render} bytecodes 100-795) stay untouched. The game-facing entry points, in frame order:
 *
 * <ol>
 * <li>{@link #submitAndPrepare} at {@code LevelRenderer.render}, before {@code FrameGraphBuilder.execute}
 * (bytecode 770), under {@code renderItemInHand}'s guards and with its pose (the model-view stays the level's).</li>
 * <li>The solid hand, drawn by the takeover's phase hook through the registered
 * {@link GbufferDelegate.HandDraws}: inside its {@code HAND} kind window, on the reopened game pass, with this
 * renderer's projection served through the delegate.</li>
 * <li>{@link #drawTranslucentHand} in place of the game's {@code render3dHud} {@code renderItemInHand} call (bytecode
 * 141, skipped while a set is active), before the screen effects; it closes the frame.</li>
 * <li>{@link #afterLevel} at E3's hook, closing a frame the translucent draw never ran; {@link #onBufferEndFrame}
 * beside the game's {@code RenderBuffers.endFrame()} ({@code GameRenderer.render} bytecode 379) every frame; the
 * shadow frame (E5) has its own dispatchers, so {@link #submitAndPrepare} does not share them.</li>
 * </ol>
 *
 * <p>Inert while no set is active (one {@link Engine#state()} check per entry point, no allocation). Every bytecode
 * position is recorded in the mixins' Javadoc ({@code dev.mcms.metal.mixin.EngineHand*}, javap of
 * minecraft-merged-deobf-26.3.jar, 2026-11-14).
 */
public final class HandRenderer {
	private static final HandRenderer INSTANCE = new HandRenderer();

	public static HandRenderer get() {
		return INSTANCE;
	}

	/** The game's staged-buffer size ({@code RenderBuffers.<init>}: 4194304, javap 2026-11-14). */
	private static final int STAGED_BUFFER_SIZE = 4 * 1024 * 1024;

	private HandFrameCoordinator coordinator;
	private final Matrix4f handProjection = new Matrix4f();
	/** The same matrix column-major, published to the delegate without a per-frame allocation. */
	private final float[] handProjectionColumnMajor = new float[16];
	/** The delegate the hand draws are registered with: a delegate lives only as long as its set, so a new active
	 * delegate (pack switch, reload, engine off/on, a stored set on a dimension change) is registered anew. */
	private @Nullable GbufferDelegate registeredDelegate;
	private @Nullable GameHandDispatcher dispatcher;

	private HandRenderer() {
		this.coordinator = new HandFrameCoordinator(new ClosedDispatcher());
	}

	private record ClosedDispatcher() implements HandDispatcher {
		@Override public void setFrameInputs(CameraRenderState camera, GameRenderState state, PlayerRenderState player) { }
		@Override public void submitAndPrepare() { }
		@Override public boolean preparedEmpty() { return true; }
		@Override public void executeSolid(Object renderPass) { }
		@Override public void executeTranslucent(Object renderPass) { }
		@Override public void closeFrame() { }
		@Override public void bufferEndFrame() { }
		@Override public void close() { }
	}

	/** Whether the engine draws the hand: the engine is active and its takeover serves the world (E4 review item 1). */
	private boolean active() {
		return Engine.get().drawsHand();
	}

	/** Whether this frame's hand is prepared and open (the solid hand draws, and the split opens, only then). */
	public boolean solidHandPrepared() {
		return this.coordinator.frameOpen();
	}

	/** The engine's staged buffer exists (its {@code endFrame} must run beside the game's). */
	public boolean bufferExists() {
		return this.dispatcher != null;
	}

	/**
	 * Submits the hands and items and prepares their frame ({@code LevelRenderer.render}, before
	 * {@code FrameGraphBuilder.execute} at bytecode 770), under {@code renderItemInHand}'s guards and with its pose:
	 * the {@code PoseStack} starts at {@code inverse(viewRotationMatrix)}, then {@code bobHurt}, then
	 * {@code bobView} when the option is on; the model-view stays the level's (the stack holds {@code viewRotationMatrix}
	 * between bytecodes 55 and 789, which is what {@code renderItemInHand} would push, and {@code RenderType.prepare}
	 * copies the model-view at prepare time). Also computes this frame's hand projection and publishes it with the
	 * hand draws to the takeover's delegate.
	 *
	 * @return a frame was prepared (the solid hand should draw at the phase hook)
	 */
	public boolean submitAndPrepare(Minecraft mc, GameRenderer gameRenderer, CameraRenderState camera) {
		if (!this.active()) {
			return false;
		}
		GameRenderState state = gameRenderer.gameRenderState();
		PlayerRenderState player = state.levelRenderState.playerRenderState;
		if (DrawLog.on()) {
			logGuards(mc, state, camera, player);
		}
		if (!shouldRenderHand(mc, state, camera, player)) {
			return false;
		}
		GameHandDispatcher d = this.dispatcher(mc, gameRenderer);
		d.setFrameInputs(camera, state, player);
		this.handProjection(camera, state);
		publishToDelegate();
		boolean prepared = this.coordinator.prepare();
		if (DrawLog.on()) {
			StringBuilder sb = DrawLog.line();
			sb.append("HAND f=").append(DrawLog.frameNumber()).append(" prepared=").append(prepared ? 1 : 0);
			DrawLog.emit(sb);
		}
		return prepared;
	}

	/**
	 * E4 diagnostics (draw log only): the guards of {@code renderItemInHand} (bytecodes 0-70) as this frame sees them, so a
	 * hand that draws nothing names the guard that stopped it. Logged once per frame, before the guards decide.
	 */
	private void logGuards(Minecraft mc, GameRenderState state, CameraRenderState camera, PlayerRenderState player) {
		StringBuilder sb = DrawLog.line();
		sb.append("HAND f=").append(DrawLog.frameNumber())
			.append(" guards panoramic=").append(camera.isPanoramicMode ? 1 : 0)
			.append(" hasPlayer=").append(player.hasPlayer ? 1 : 0)
			.append(" firstPerson=").append(state.optionsRenderState.cameraType.isFirstPerson() ? 1 : 0)
			.append(" sleeping=").append(camera.entityRenderState != null && camera.entityRenderState.isSleeping ? 1 : 0)
			.append(" hudHidden=").append(state.guiRenderState.isHudHidden ? 1 : 0)
			.append(" gameMode=").append(mc.gameMode == null ? "null" : mc.gameMode.getPlayerMode().name())
			.append(" handsAndItems=").append(player.firstPersonHandsAndItems != null ? 1 : 0)
			.append(" frameOpen=").append(this.coordinator.frameOpen() ? 1 : 0);
		DrawLog.emit(sb);
	}

	/** Gives the active delegate this frame's hand projection (column-major) and the hand draws its phase hook
	 * calls ({@code GbufferDelegate.setHandDraws}/{@code setHandProjection}); a delegate lives only as long as its
	 * set, so a delegate other than the registered one is registered anew (the old one's registration is cleared).
	 * The frame path allocates nothing of its own (the column array is this renderer's one). Package-visible for the
	 * re-registration test. */
	void publishTo(GbufferDelegate delegate) {
		delegate.setHandProjection(this.handProjectionColumnMajor);
		if (this.registeredDelegate == delegate) {
			return;
		}
		if (this.registeredDelegate == null) {
			GbufferDelegate.addReleaseListener(this.release);
		} else {
			this.registeredDelegate.setHandDraws(null);
			this.registeredDelegate.setHandProjection(null);
		}
		delegate.setHandDraws(HAND_DRAWS);
		this.registeredDelegate = delegate;
	}

	private void publishToDelegate() {
		GbufferDelegate delegate = GbufferDelegate.active();
		if (delegate != null) {
			this.publishTo(delegate);
		}
	}

	/** The hand draws the phase hook calls, registered with whichever delegate is active. */
	private static final GbufferDelegate.HandDraws HAND_DRAWS = new GbufferDelegate.HandDraws() {
		@Override public void drawSolidHand(Object renderPass) {
			HandRenderer.get().coordinator.drawSolid(renderPass);
		}

		@Override public void closeHandFrame() {
			HandRenderer.get().coordinator.closeIfOpen();
		}
	};

	/** Runs on the engine's release paths (turnOff and shutdown: engine off, pack switch, resource reload, device
	 * close) — the device close has no frame after it, so this listener is what closes the dispatcher then. */
	private final Runnable release = this::close;

	/** {@code renderItemInHand}'s guards (bytecodes 0-70): not panoramic, a player, first person, not sleeping, HUD
	 * visible, not a spectator, hands to draw. */
	private static boolean shouldRenderHand(Minecraft mc, GameRenderState state, CameraRenderState camera,
			PlayerRenderState player) {
		return !camera.isPanoramicMode
			&& player.hasPlayer
			&& state.optionsRenderState.cameraType.isFirstPerson()
			&& (camera.entityRenderState == null || !camera.entityRenderState.isSleeping)
			&& !state.guiRenderState.isHudHidden
			&& mc.gameMode != null && mc.gameMode.getPlayerMode() != net.minecraft.world.level.GameType.SPECTATOR
			&& player.firstPersonHandsAndItems != null;
	}

	private void handProjection(CameraRenderState camera, GameRenderState state) {
		// E8: the loaded pack's MC_HAND_DEPTH (the player's Hand depth setting), so the projection and the macro agree.
		HandProjection.perspective(camera.hudFov, camera.depthFar, state.windowRenderState.width,
			state.windowRenderState.height, Engine.get().handDepth(), this.handProjection);
		this.handProjection.get(this.handProjectionColumnMajor);
	}

	/** This frame's hand projection (the pack's {@code gl_ProjectionMatrix} for the hand programs; valid after
	 * {@link #submitAndPrepare} prepared a frame). */
	public Matrix4f handProjection() {
		return this.handProjection;
	}

	/**
	 * The translucent hand, in place of the game's {@code renderItemInHand} call ({@code render3dHud} bytecode 141):
	 * a pass in the game's shape ({@code colorView}, the depth view the game computed — {@code hud3DTarget}'s when
	 * post effects are active, {@code render3dHud} bytecodes 96-118 — no clears; the depth the pass sees is the
	 * engine's once the takeover's redirector moves it), opened inside a {@code HAND} kind window so the redirector
	 * takes it over and the entity and item pipelines map to the hand programs; then the frame's translucent phases
	 * in {@code renderAllFeatures}' order (translucent, after-terrain, see-through, always-on-top; bytecodes 7-22),
	 * then the frame closes. Before the screen effects, as in the game's order.
	 */
	public void drawTranslucentHand(GpuTextureView colorView, GpuTextureView depthView) {
		if (!this.coordinator.frameOpen()) {
			return;
		}
		this.translucentColorView = colorView;
		this.translucentDepthView = depthView;
		GbufferDelegate delegate = GbufferDelegate.active();
		if (delegate != null) {
			delegate.passKind(PassKind.HAND, this.translucentDraw);
		} else {
			this.translucentDraw.run();
		}
	}

	/** This frame's pass views, kept in fields so the kind window reuses one stored Runnable (no per-frame
	 * allocation; valid only inside the draw). */
	private @Nullable GpuTextureView translucentColorView;
	private @Nullable GpuTextureView translucentDepthView;
	private final Runnable translucentDraw = () -> this.drawTranslucentHandInPass(this.translucentColorView,
		this.translucentDepthView);

	private void drawTranslucentHandInPass(GpuTextureView colorView, GpuTextureView depthView) {
		GpuDevice device = RenderSystem.getDevice();
		CommandEncoder encoder = device.createCommandEncoder();
		RenderPass pass = encoder.createRenderPass(() -> "Quicksilver: item in hand", colorView,
			Optional.empty(), depthView, OptionalDouble.empty());
		try {
			RenderSystem.bindDefaultUniforms(pass);
			this.coordinator.drawTranslucent(pass);
		} finally {
			pass.close();
		}
	}

	/** E3's after-level hook: a frame the translucent draw never ran (guards, early exits) is closed here. */
	public void afterLevel() {
		if (this.dispatcher != null) {
			this.coordinator.closeIfOpen();
		}
	}

	/** Beside the game's {@code RenderBuffers.endFrame()} ({@code GameRenderer.render} bytecode 379), every frame. */
	public void onBufferEndFrame() {
		if (this.dispatcher != null) {
			this.coordinator.bufferEndFrame();
		}
	}

	/** Closes the dispatcher and its buffer: engine off, pack switch, resource reload, device close (the release
	 * listener the engine runs, and the frame-end check when the engine left ACTIVE without one). The registration
	 * resets unconditionally: at the frame-end hook the engine has already left ACTIVE, and the next set installs a
	 * new delegate that must be registered anew. */
	public void close() {
		if (this.dispatcher != null) {
			this.coordinator.close();
			this.dispatcher = null;
		}
		if (this.registeredDelegate != null) {
			// Unconditional: active() answers null or another set's delegate by then; the registered delegate is
			// ours and always safe to clear.
			this.registeredDelegate.setHandDraws(null);
			this.registeredDelegate.setHandProjection(null);
			GbufferDelegate.removeReleaseListener(this.release);
			this.registeredDelegate = null;
		}
	}

	private GameHandDispatcher dispatcher(Minecraft mc, GameRenderer gameRenderer) {
		GameHandDispatcher d = this.dispatcher;
		if (d == null) {
			// The no-op coordinator of the idle state is closed (a no-op) and replaced by the real one.
			this.coordinator.close();
			d = new GameHandDispatcher(mc, gameRenderer);
			this.coordinator = new HandFrameCoordinator(d);
			this.dispatcher = d;
		}
		return d;
	}

	/**
	 * The second dispatcher: {@code new FeatureRenderDispatcher(game RenderBuffers, ModelManager, AtlasManager, Font,
	 * GameRenderState)} (constructor bytecode 51 reads {@code RenderBuffers.stagedVertexBuffer()} and stores it), with
	 * that field replaced by an engine {@code StagedVertexBuffer(label, 4194304)} — no second {@code RenderBuffers}
	 * (section buffer pack and pool) is allocated. The per-frame objects (the pose stack, the inversion scratch) are
	 * fields: the steady-state frame path allocates nothing.
	 */
	private static final class GameHandDispatcher implements HandDispatcher {
		private final FirstPersonHandsAndItemsRenderer hands;
		private final SubmitNodeStorage storage;
		private final GameRenderer gameRenderer;
		private final FeatureRenderDispatcher dispatcher;
		private final StagedVertexBuffer buffer;
		private final PoseStack poseStack = new PoseStack();
		private final Matrix4f inverseViewRotation = new Matrix4f();
		/** The hand's body for the bracket, bound once: a method reference per frame would allocate. */
		private final HandPoseBracket.Body handBody = this::drawHand;
		private @Nullable PreparedFrame frame;
		private @Nullable CameraRenderState camera;
		private @Nullable GameRenderState state;
		private @Nullable PlayerRenderState player;

		GameHandDispatcher(Minecraft mc, GameRenderer gameRenderer) {
			this.gameRenderer = gameRenderer;
			RenderBuffers renderBuffers = gameRenderer.renderBuffers();
			this.dispatcher = new FeatureRenderDispatcher(renderBuffers, mc.getModelManager(), mc.getAtlasManager(),
				mc.font, gameRenderer.gameRenderState());
			this.buffer = new StagedVertexBuffer(() -> "Quicksilver: hand", STAGED_BUFFER_SIZE);
			((EngineHandFeatureDispatcherAccessor) (Object) this.dispatcher).mcms$setStagedVertexBuffer(this.buffer);
			this.hands = gameRenderer.firstPersonHandsAndItemsRenderer;
			this.storage = ((EngineHandGameRendererAccessor) (Object) gameRenderer).mcms$handStorage();
		}

		/** This frame's render states, set before {@link #submitAndPrepare()} runs (the coordinator stays game-free). */
		@Override
		public void setFrameInputs(CameraRenderState camera, GameRenderState state, PlayerRenderState player) {
			this.camera = camera;
			this.state = state;
			this.player = player;
		}

		/** The pose and the submit ({@code renderItemInHand} bytecodes 71-175), then the prepare (bytecodes 178-186). */
		@Override
		public void submitAndPrepare() {
			if (this.camera == null || this.state == null || this.player == null) {
				return;
			}
			HandPoseBracket.run(this.poseStack, this.handBody);  // the pose pops even when the submit throws (E4 review item 9)
			this.frame = this.dispatcher.prepareFrame(this.storage);
		}

		/** The hand's pose and its submit, inside the bracket's pushed pose (the frame's inputs are set, see {@link #setFrameInputs}). */
		private void drawHand(PoseStack stack) {
			CameraRenderState camera = this.camera;
			GameRenderState state = this.state;
			PlayerRenderState player = this.player;
			if (camera == null || state == null || player == null) {
				return;
			}
			stack.mulPose(camera.viewRotationMatrix.invert(this.inverseViewRotation));
			EngineHandGameRendererAccessor self = (EngineHandGameRendererAccessor) (Object) this.gameRenderer;
			self.mcms$bobHurt(camera, stack);
			if (state.optionsRenderState.bobView) {
				self.mcms$bobView(camera, stack);
			}
			this.hands.submitHandsWithItems(camera.cameraEntityPartialTicks, stack, this.storage, player,
				player.firstPersonHandsAndItems);
		}

		@Override
		public boolean preparedEmpty() {
			PreparedFrame f = this.frame;
			return f == null || f.isEmpty();
		}

		@Override
		public void executeSolid(Object renderPass) {
			PreparedFrame f = this.frame;
			if (f != null) {
				f.executeSolid((RenderPass) renderPass);
			}
		}

		/** {@code renderAllFeatures}' order (bytecodes 7-22): translucent, after-terrain, see-through, always-on-top. */
		@Override
		public void executeTranslucent(Object renderPass) {
			PreparedFrame f = this.frame;
			if (f != null) {
				RenderPass pass = (RenderPass) renderPass;
				f.executeTranslucent(pass);
				f.executeTranslucentAfterTerrain(pass);
				f.executeSeeThrough(pass);
				f.executeAlwaysOnTop(pass);
			}
		}

		@Override
		public void closeFrame() {
			PreparedFrame f = this.frame;
			this.frame = null;
			if (f != null) {
				f.close();
			}
		}

		@Override
		public void bufferEndFrame() {
			this.buffer.endFrame();
		}

		@Override
		public void close() {
			this.closeFrame();
			this.dispatcher.close();
			this.buffer.close();
		}
	}
}
