// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.shadow;

import dev.mcms.shaders.pack.ShaderProperties;
import java.util.Map;

/**
 * The shadow pass's documented order (plan.md E5, "The pass"), run from the hook at {@code LevelRenderer.render}
 * bytecode 79, before the level's {@code submitFeatures} and before the world's {@code PreparedFrame} opens at bytecode
 * 100:
 *
 * <ol>
 * <li>{@link Steps#submitEntities}: the shadow entities and block entities into the engine's own
 * {@code SubmitNodeStorage}s (the game's storage is not touched).</li>
 * <li>{@link Steps#prepareEntities}: the engine's own {@code FeatureRenderDispatcher}s prepare them (the hand's dispatcher
 * is a third one, so the world's frame is untouched).</li>
 * <li>{@link Steps#clearCachedBatches}: the face-culled batches are cleared before the first shadow draw (the batch
 * is shared with the camera; a filled batch would otherwise be reused from the camera's frame).</li>
 * <li>{@link Steps#clearTargets}: the shadow targets are cleared (both depth maps to 1.0, each colour buffer to its clear
 * colour where the pack clears it), so the previous frame's casters are gone before this pass draws.</li>
 * <li>{@link Steps#openPass}: the game render pass the redirector sends into the shadow targets.</li>
 * <li>Terrain solid and cutout (when {@code shadowTerrain}), the entities' solid draws (when an entity or block entity is
 * drawn), the {@code shadowtex1} copy, translucent terrain (when {@code shadowTranslucent}) and the entities' translucent
 * draws.</li>
 * <li>{@link Steps#closePass}; then {@link Steps#closePrepared} (the shadow prepared frame, before the hand's prepare at
 * bytecode 770 uses its own dispatcher) and {@link Steps#clearCachedBatches} again (before the main camera's prepare).</li>
 * <li>{@link Steps#shadowcomp}: the {@code shadowcomp} passes, after the shadow pass (their compute parts are E6's).</li>
 * </ol>
 *
 * <p>Failure: a throwing step still closes the pass, the prepared frame and the cached batches, and the exception
 * propagates; {@code shadowcomp} does not run after a failed pass. Pure: the engine supplies the steps.
 */
public final class ShadowPassSequence {

	/** The steps the engine performs, in the documented order. */
	public interface Steps {
		/**
		 * Submits the shadow entity list and, when {@code blockEntities}, the block entities. The entity list is the
		 * entities, or the player and its vehicle alone ({@link Content#entityList}); {@code player} is the pack's switch.
		 */
		void submitEntities(boolean entityList, boolean blockEntities, boolean player);

		/** The engine's dispatcher prepares the submitted shadow draws. */
		void prepareEntities();

		/** Clears the shadow targets before the pass: both depth maps to 1.0, and each colour buffer the pack clears. */
		void clearTargets();

		/** Opens the redirected game pass into the shadow targets. */
		void openPass();

		/** Terrain solid and cutout through the chunk renderer, every face, the camera's sorting flag ({@code ShadowTerrain}). */
		void terrainSolidAndCutout();

		/** The shadow entities' and block entities' solid draws ({@code executeSolid}). */
		void entitiesSolid();

		/** The {@code shadowtex1} depth copy: after every opaque caster, before the translucent ones. */
		void copyShadowtex1();

		/** Translucent terrain (the shadow water program). */
		void terrainTranslucent();

		/** The shadow entities' and block entities' translucent draws ({@code executeTranslucent}). */
		void entitiesTranslucent();

		/** Closes the shadow pass. */
		void closePass();

		/** Closes the shadow prepared frame. */
		void closePrepared();

		/** Invalidates the face-culled batches the shadow draw filled ({@code RenderRegion.clearAllCachedBatches}). */
		void clearCachedBatches();

		/** Runs the {@code shadowcomp} passes. */
		void shadowcomp();
	}

	/**
	 * The shadow pass's content switches (shaders.properties; engine-pack-format.md section 16). Each defaults to true
	 * when the pack does not set it [inference: the reference's defaults; the Ordering page names no default].
	 * The switches are stored as the pack sets them; the documented coupling is in the methods.
	 */
	public record Content(boolean terrain, boolean translucent, boolean entities, boolean blockEntities, boolean player) {

		/** The switches of a pack's {@code shaders.properties} booleans. */
		public static Content of(Map<String, Boolean> booleans) {
			return new Content(on(booleans, "shadowTerrain"), on(booleans, "shadowTranslucent"),
				on(booleans, "shadowEntities"), on(booleans, "shadowBlockEntities"), on(booleans, "shadowPlayer"));
		}

		/** The switches of a parsed pack's properties. */
		public static Content of(ShaderProperties properties) {
			return of(properties.booleans());
		}

		/**
		 * Whether the entity list is extracted and submitted: the entities, and the player and its vehicle. The Ordering
		 * page forces {@code shadowPlayer} on while {@code shadowEntities} is on, so the player's switch is read as on
		 * then.
		 */
		public boolean entityList() {
			return this.entities || this.player;
		}

		/**
		 * Whether the entity draws run (their solid and translucent steps): the entity list, or block entities. Block
		 * entities have their own switch ({@code shadowBlockEntities}); the entities' switch does not remove them.
		 */
		public boolean entityDraws() {
			return entityList() || this.blockEntities;
		}

		private static boolean on(Map<String, Boolean> booleans, String key) {
			Boolean value = booleans.get(key);
			return value == null || value;
		}
	}

	private ShadowPassSequence() {
	}

	/** Runs the shadow pass with the steps and content given. */
	public static void run(Steps steps, Content content) {
		try {
			steps.submitEntities(content.entityList(), content.blockEntities(), content.player());
			steps.prepareEntities();
			// Before the first shadow draw too: the face-culled batch of each region is shared with the camera, and
			// prepare() returns a filled batch as it stands, so without this the pass could reuse last frame's batch.
			steps.clearCachedBatches();
			steps.clearTargets();
			steps.openPass();
			try {
				if (content.terrain()) {
					steps.terrainSolidAndCutout();
				}
				if (content.entityDraws()) {
					steps.entitiesSolid();
				}
				steps.copyShadowtex1();
				if (content.translucent()) {
					steps.terrainTranslucent();
				}
				if (content.entityDraws()) {
					steps.entitiesTranslucent();
				}
			} finally {
				steps.closePass();
			}
		} finally {
			steps.closePrepared();
			steps.clearCachedBatches();
		}
		steps.shadowcomp();
	}
}
