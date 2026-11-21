// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

/**
 * The light's list is built from a frustum widened by {@link ShadowTerrain#LIST_MARGIN} (one section): the matrices are one
 * frame old at the build, so a section just past the light's edge must be listed. The exact frustum (no widening) does not
 * list it, so the widening is what makes the difference.
 *
 * <p>The light is orthographic with a half-width of 40 blocks on X, and an identity view: world X is light X. A section
 * centre 18 blocks past the +X edge is outside the exact frustum by more than the section's own padding (about 9 blocks).
 */
class LightFrustumWideningTest {

	private static final Matrix4f VIEW_PROJECTION = new Matrix4f().ortho(-40f, 40f, -40f, 40f, -100f, 100f);

	@Test
	void aSectionOneSectionPastTheEdgeIsListedOnlyWithTheWidening() {
		LightFrustum exact = new LightFrustum(VIEW_PROJECTION, 0f);
		LightFrustum widened = new LightFrustum(VIEW_PROJECTION, ShadowTerrain.LIST_MARGIN);

		assertFalse(exact.testSection(58f, 0f, 0f), "the exact frustum does not list a section 18 blocks past its edge");
		assertTrue(widened.testSection(58f, 0f, 0f), "the widened frustum lists it: a camera move of one section stays inside");
	}

	@Test
	void aBoxOneSectionPastTheEdgeIsTestedAgainstTheWidenedFrustum() {
		LightFrustum exact = new LightFrustum(VIEW_PROJECTION, 0f);
		LightFrustum widened = new LightFrustum(VIEW_PROJECTION, ShadowTerrain.LIST_MARGIN);

		assertFalse(exact.testAab(50f, -4f, -4f, 66f, 4f, 4f), "the exact box test rejects the box beyond the edge");
		assertTrue(widened.testAab(50f, -4f, -4f, 66f, 4f, 4f), "the widened box test keeps the box");
	}
}
