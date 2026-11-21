// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.hand;

/**
 * The engine's second {@code FeatureRenderDispatcher}, as the {@link HandFrameCoordinator} uses it: every method is one
 * frame operation at its javap-checked site (plan.md E4, "Hand"; the offsets are in the mixins' Javadoc), and a fake
 * behind this interface drives the lifecycle tests without the game. The render-pass arguments stay opaque so the
 * coordinator stays game-free; the game implementation passes them through.
 */
public interface HandDispatcher extends AutoCloseable {
	/** This frame's render states, set before {@link #submitAndPrepare()} runs (the coordinator stays game-free;
	 * the tests' fakes ignore them). */
	void setFrameInputs(net.minecraft.client.renderer.state.level.CameraRenderState camera,
		net.minecraft.client.renderer.state.GameRenderState state,
		net.minecraft.client.renderer.state.level.PlayerRenderState player);

	/**
	 * Submits the hands and items with their pose and prepares their frame ({@code LevelRenderer.render}, before
	 * {@code FrameGraphBuilder.execute} at bytecode 770): {@code submitHandsWithItems} then
	 * {@code FeatureRenderDispatcher.prepareFrame(handAndScreenSubmitNodeStorage)}.
	 */
	void submitAndPrepare();

	/** The prepared frame has no draws (nothing to draw, nothing to keep open). */
	boolean preparedEmpty();

	/** The prepared frame's {@code executeSolid(RenderPass)} — the solid hand, at the phase hook. */
	void executeSolid(Object renderPass);

	/**
	 * The prepared frame's translucent phases ({@code executeTranslucent}, {@code executeTranslucentAfterTerrain},
	 * {@code executeSeeThrough}, {@code executeAlwaysOnTop} — {@code renderAllFeatures}' order, bytecodes 7-22), in
	 * the redirected engine pass at {@code render3dHud} bytecode 141, before the screen effects.
	 */
	void executeTranslucent(Object renderPass);

	/** Closes the prepared frame ({@code PreparedFrame.close}, which ends the staged buffer's draw). */
	void closeFrame();

	/**
	 * The staged buffer's {@code endFrame()}: called beside the game's {@code RenderBuffers.endFrame()}
	 * ({@code GameRenderer.render} bytecode 379) every frame while the buffer exists — only there do the pool's used
	 * GPU buffers become available again, so without it each prepare would create new buffers.
	 */
	void bufferEndFrame();

	/** Closes the dispatcher and its staged buffer (engine off, pack switch, resource reload, device close). */
	@Override
	void close();
}
