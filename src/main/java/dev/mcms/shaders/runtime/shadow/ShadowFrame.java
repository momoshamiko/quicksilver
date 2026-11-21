// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.shadow;

/**
 * The shadow frame's real {@link ShadowPassSequence.Steps}: each documented step of the shadow pass (plan.md E5, "The
 * pass") calls one {@link Ops} operation, in {@link ShadowPassSequence}'s order. The game binding and the frame-order
 * test both implement {@link Ops}; the sequence and the steps are the same code in both, so the test checks the order
 * the game runs.
 *
 * <p>Allocation: the steps are one instance per frame shadow, built once; {@link #run} allocates nothing (the
 * {@link ShadowPassSequence.Content} is built once per set from the pack's booleans).
 *
 * <p>The shadow view: {@link Ops#bindShadowView} runs right after the pass opens and before any draw, so every shadow
 * draw sees the engine's {@code shadowModelView} and never the captured model-view (plan.md E5; the level's model-view
 * push at bytecodes 45-55 is still on the stack at the hook).
 */
public final class ShadowFrame {

	/**
	 * The backend operations of the shadow pass, one per step. Each method is one frame operation at its javap-checked
	 * site or one engine call; the order is the caller's contract (see {@link ShadowPassSequence#run}).
	 */
	public interface Ops {
		/** Submits the shadow entities, block entities and the player into the engine's storage. */
		void submitEntities(boolean entities, boolean blockEntities, boolean player);

		/** The engine's dispatcher prepares the submitted shadow draws. */
		void prepareEntities();

		/** Clears the shadow targets: both depth maps to 1.0, and each colour buffer the pack clears to its clear colour. */
		void clearTargets();

		/** Opens the game render pass the redirector sends into the shadow targets (E5-B1's begin/end pair). */
		void openPass();

		/** Binds the engine's {@code shadowModelView} for this pass, before any draw. */
		void bindShadowView();

		/** Terrain solid and cutout through the chunk renderer, every face, the camera's sorting flag ({@code ShadowTerrain}). */
		void terrainSolidAndCutout();

		/** The shadow entities' solid draws ({@code executeSolid}). */
		void entitiesSolid();

		/** The {@code shadowtex1} depth copy, after every opaque caster and before the translucent ones. */
		void copyShadowtex1();

		/** Translucent terrain (the shadow water program). */
		void terrainTranslucent();

		/** The shadow entities' translucent draws ({@code executeTranslucent}). */
		void entitiesTranslucent();

		/** Closes the shadow pass (ends the redirect, then closes the render pass). */
		void closePass();

		/** Closes the shadow prepared frame. The shadow pass's dispatchers are its own; the hand's prepare at bytecode 770 uses another. */
		void closePrepared();

		/** Invalidates the face-culled batches: before the first shadow draw, and again before the camera's prepare. */
		void clearCachedBatches();

		/** Runs the {@code shadowcomp} passes (their compute parts are E6's). */
		void shadowcomp();
	}

	private final ShadowPassSequence.Steps steps;

	/** Binds the operations; the steps are built once here. */
	public ShadowFrame(Ops ops) {
		this.steps = new ShadowPassSequence.Steps() {
			@Override public void submitEntities(boolean entities, boolean blockEntities, boolean player) {
				ops.submitEntities(entities, blockEntities, player);
			}
			@Override public void prepareEntities() {
				ops.prepareEntities();
			}
			@Override public void clearTargets() {
				ops.clearTargets();
			}
			@Override public void openPass() {
				ops.openPass();
				ops.bindShadowView();
			}
			@Override public void terrainSolidAndCutout() {
				ops.terrainSolidAndCutout();
			}
			@Override public void entitiesSolid() {
				ops.entitiesSolid();
			}
			@Override public void copyShadowtex1() {
				ops.copyShadowtex1();
			}
			@Override public void terrainTranslucent() {
				ops.terrainTranslucent();
			}
			@Override public void entitiesTranslucent() {
				ops.entitiesTranslucent();
			}
			@Override public void closePass() {
				ops.closePass();
			}
			@Override public void closePrepared() {
				ops.closePrepared();
			}
			@Override public void clearCachedBatches() {
				ops.clearCachedBatches();
			}
			@Override public void shadowcomp() {
				ops.shadowcomp();
			}
		};
	}

	/**
	 * Runs the shadow pass for one frame: {@link ShadowPassSequence#run} over this frame's steps, with the content
	 * switches of the set. A failed step closes what it opened and propagates; {@code shadowcomp} then does not run.
	 */
	public void run(ShadowPassSequence.Content content) {
		ShadowPassSequence.run(this.steps, content);
	}
}
