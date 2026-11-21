// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.shadow;

import dev.mcms.shaders.runtime.terrain.ShadowTerrain;

/**
 * The attach and detach of the light's two lists (E5): the terrain list ({@link ShadowTerrain}) and the entity list
 * ({@link ShadowEntities}). A set attaches both only when it has a shadow pass (the engine's {@code shadowOps} is made), and
 * detaches both at the set's release or when a shadow step fails. A set without shadows therefore pays neither the light's
 * terrain build nor its second entity extraction.
 */
public final class ShadowLists {

	private ShadowLists() {
	}

	/** Attaches the set's shadow camera and content switches to both lists. */
	public static void attach(ShadowCamera camera, ShadowPassSequence.Content content) {
		ShadowTerrain.get().attach(camera);
		ShadowEntities.get().attach(camera, content);
	}

	/** Detaches both lists: the set's release, or a failed shadow step (the frame then keeps the vanilla picture). */
	public static void detach() {
		ShadowTerrain.get().detach();
		ShadowEntities.get().detach();
	}

	/** Whether both lists are attached (a set with shadows is active). */
	public static boolean attached() {
		return ShadowTerrain.get().attached() && ShadowEntities.get().attached();
	}
}
