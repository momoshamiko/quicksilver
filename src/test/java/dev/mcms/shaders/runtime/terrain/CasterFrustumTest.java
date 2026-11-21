// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import dev.mcms.shaders.runtime.shadow.CasterCull;

/**
 * The light's frustum with the caster cull (E9): a box the light holds whole but the cull leaves out answers "outside",
 * and a box the light holds whole and the cull keeps answers "intersecting", never "inside": Sodium's tree skips every
 * test below an "inside" node, so an "inside" would keep the casters of the whole subtree.
 */
class CasterFrustumTest {

	/** A light box holding every box below. */
	private static final Matrix4f LIGHT = new Matrix4f().ortho(-200f, 200f, -200f, 200f, -200f, 200f);
	/** An orthographic camera looking down -Z: left plane x >= -10, right plane x <= 10. */
	private static final Matrix4f CAMERA = new Matrix4f().setOrtho(-10f, 10f, -10f, 10f, 0.1f, 100f);

	@Test
	void aBoxTheLightHoldsIsTestedFurtherOrCulled() {
		CasterCull casters = new CasterCull();
		casters.set(CAMERA, 0f, 1f, 0f, -1f); // overhead light
		LightFrustum light = new LightFrustum(LIGHT, 0f);
		CasterFrustum frustum = new CasterFrustum(light, casters);

		assertEquals(FrustumIntersection.INSIDE, light.intersectAab(-48f, -8f, -40f, -32f, 8f, -24f), "the light holds it");
		assertEquals(FrustumIntersection.OUTSIDE, frustum.intersectAab(-48f, -8f, -40f, -32f, 8f, -24f),
			"left of the view, lit from above");
		assertEquals(FrustumIntersection.INTERSECT, frustum.intersectAab(-8f, -8f, -40f, 8f, 8f, -24f),
			"in the view: its children are still tested");
		assertFalse(frustum.testSection(-40f, 0f, -32f), "the section left of the view");
		assertTrue(frustum.testSection(0f, 0f, -32f), "the section in the view");
	}
}
