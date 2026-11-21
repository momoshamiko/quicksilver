// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mcms.shaders.pack.ConstValue;
import dev.mcms.shaders.pack.ShaderProperties.ShadowCulling;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The distance rule of the light's caster lists from {@code shadow.culling} (the rendering page: {@code false} "will prevent
 * any geometry within the shadow distance from being culled"; {@code reversed} is Iris's voxelDistance exemption, which the
 * engine does not build). {@link ShadowCamera#cullRadius} is what the light's terrain list and the entity selection read.
 */
class ShadowCullingTest {

	private static float cullRadiusOf(ShadowCulling culling, double renderMul) {
		ShadowCamera camera = new ShadowCamera();
		camera.configure(ShadowCamera.Settings.of(Map.of(
			"shadowDistance", new ConstValue("float", List.of(128.0)),
			"shadowDistanceRenderMul", new ConstValue("float", List.of(renderMul))),
			0f, culling));
		return camera.cullRadius();
	}

	@Test
	void trueCullsByTheShadowDistanceWhenTheRenderMultiplierIsSet() {
		assertEquals(128f, cullRadiusOf(ShadowCulling.TRUE, 1.0), 1e-4f);
	}

	@Test
	void falseLeavesTheRenderDistanceAsTheOnlyDistance() {
		assertEquals(-1f, cullRadiusOf(ShadowCulling.FALSE, 1.0), 0f, "no radius: the list uses the render distance");
		assertEquals(-1f, cullRadiusOf(ShadowCulling.FALSE, -1.0), 0f);
	}

	@Test
	void reversedCullsAtTheShadowDistanceBecauseItsVoxelExemptionIsNotBuilt() {
		// An absolute radius: a voxel-distance exemption that changed this radius would fail here.
		assertEquals(128f, cullRadiusOf(ShadowCulling.REVERSED, 1.0), 1e-4f);
	}

	@Test
	void theSixArgumentSettingsKeepTheDefaultCulling() {
		assertEquals(ShadowCulling.TRUE, new ShadowCamera.Settings(1024, 0f, 160f, 2f, -1f, 0f).culling());
	}
}
