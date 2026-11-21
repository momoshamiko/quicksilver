// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.mcms.shaders.runtime.shadow.ShadowCamera;
import org.junit.jupiter.api.Test;

/**
 * The light's list is built at Sodium's {@code setupTerrain} tail, which can run after the shadow frame's first hook: a
 * light with no build yet must say so (the shadow frame then skips its terrain steps for that frame, see ShadowPassOps).
 */
class ShadowTerrainBuiltTest {

	@Test
	void aLightWithoutAnyBuildIsNotBuiltEvenWhenAttached() {
		ShadowTerrain light = ShadowTerrain.get();
		light.detach();
		assertFalse(light.built(), "a detached light reads as built");
		light.attach(new ShadowCamera());
		assertFalse(light.built(), "an attached light with no setupTerrain tail yet reads as built");
		light.detach();
	}
}
