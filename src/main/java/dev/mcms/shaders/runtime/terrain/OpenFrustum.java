// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;
import org.joml.FrustumIntersection;
import org.joml.Vector3d;

/**
 * A Sodium frustum that holds every box: the camera's terrain under a pack's {@code frustum.culling=false} (RIL-560; the Iris
 * docs' Rendering page: "culls geometry which is outside the view frustum", on by default). Sodium's members, javap of both
 * jars ADR 0007 allows: {@code Frustum} has {@code testAab}, {@code intersectAab} (JOML's {@code FrustumIntersection} codes:
 * {@code SimpleFrustum} returns JOML's own answer), {@code testSection} and {@code testSectionExpanded}; {@code Viewport} is
 * built from a frustum and the camera's position.
 */
public final class OpenFrustum implements Frustum {

	private static final OpenFrustum INSTANCE = new OpenFrustum();

	private OpenFrustum() {
	}

	/** A viewport at the given viewport's camera position whose frustum holds every box. */
	public static Viewport viewportAt(Viewport viewport) {
		CameraTransform camera = viewport.getTransform();
		return new Viewport(INSTANCE, new Vector3d(camera.x, camera.y, camera.z));
	}

	@Override
	public boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		return true;
	}

	@Override
	public int intersectAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		return FrustumIntersection.INSIDE;
	}

	@Override
	public boolean testSection(float x, float y, float z) {
		return true;
	}

	@Override
	public boolean testSectionExpanded(float x, float y, float z, float margin) {
		return true;
	}
}
