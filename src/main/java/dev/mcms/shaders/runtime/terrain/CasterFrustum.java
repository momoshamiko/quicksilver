// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;
import org.joml.FrustumIntersection;

import dev.mcms.shaders.runtime.shadow.CasterCull;

/**
 * The light's frustum with the cull of casters that cannot shade the camera's view ({@link CasterCull}; E9,
 * {@code shadow.culling}): a section or box is listed when the light's frustum lists it and it may shade the view.
 *
 * <p>{@link #intersectAab} answers "inside" only through the light's frustum, never past the caster test: a box the light
 * holds whole is answered "intersecting", so Sodium's traversal still tests each child against both (its tree skips the
 * tests below an "inside" node; Sodium 0.9.3-beta.1 {@code TraversableTree}, javap 2026-11-17: -2 inside, -1 intersecting,
 * any other value outside).
 *
 * <p>Arguments are camera-relative block coordinates, as {@code Viewport} passes them, the same space as the light's frustum.
 */
public final class CasterFrustum implements Frustum {

	private final Frustum light;
	private final CasterCull casters;

	/**
	 * @param light   the light's frustum (its box, widened as the build asks)
	 * @param casters this frame's caster cull, set before the traversal
	 */
	public CasterFrustum(Frustum light, CasterCull casters) {
		this.light = light;
		this.casters = casters;
	}

	@Override
	public boolean testSection(float x, float y, float z) {
		return light.testSection(x, y, z)
			&& casters.mayShade(x, y, z, Viewport.CHUNK_SECTION_RADIUS + Viewport.CHUNK_SECTION_MARGIN);
	}

	@Override
	public boolean testSectionExpanded(float x, float y, float z, float expansion) {
		return light.testSectionExpanded(x, y, z, expansion)
			&& casters.mayShade(x, y, z, Viewport.CHUNK_SECTION_RADIUS + Viewport.CHUNK_SECTION_MARGIN + expansion);
	}

	@Override
	public boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		return light.testAab(minX, minY, minZ, maxX, maxY, maxZ) && casters.mayShade(minX, minY, minZ, maxX, maxY, maxZ);
	}

	@Override
	public int intersectAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		int answer = light.intersectAab(minX, minY, minZ, maxX, maxY, maxZ);
		if (answer != FrustumIntersection.INSIDE && answer != FrustumIntersection.INTERSECT) {
			return answer;
		}
		if (!casters.mayShade(minX, minY, minZ, maxX, maxY, maxZ)) {
			return FrustumIntersection.OUTSIDE;
		}
		return FrustumIntersection.INTERSECT;
	}
}
