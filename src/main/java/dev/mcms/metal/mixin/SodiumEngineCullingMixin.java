// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import dev.mcms.shaders.runtime.Engine;
import dev.mcms.shaders.runtime.terrain.OpenFrustum;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The pack's {@code frustum.culling} for the camera's terrain (RIL-560; the Iris docs' Rendering page: true by default).
 * Sodium's {@code SodiumWorldRenderer.setupTerrain(Camera, Viewport, FogParameters, boolean, boolean, Matrix4f)}, javap of
 * both jars ADR 0007 allows (the same descriptor {@code SodiumEngineShadowTerrainMixin} hooks): the viewport is what every
 * cull of the camera's sections tests (the async cull task, the section trees, the out-of-graph fallback).
 *
 * <p>With {@code frustum.culling=false} the viewport is replaced at the method's head by one at the same camera position whose
 * frustum holds every box ({@link OpenFrustum}), as Iris 1.11.7 draws it (results/2026-11-19-ril560-cull-frustum). The
 * light's terrain list is built apart ({@code ShadowTerrain}) and keeps {@code shadow.culling}. Iris does not change its
 * occlusion culling for {@code occlusion.culling=false} (results/2026-11-19-ril560-xray), so neither does the engine.
 * While the set leaves frustum culling on, or no set serves the world, the handler returns its argument: one read of the
 * engine's state.
 */
@Mixin(SodiumWorldRenderer.class)
abstract class SodiumEngineCullingMixin {

	@ModifyVariable(method = "setupTerrain", at = @At("HEAD"), argsOnly = true)
	private Viewport mcmsMetal$frustumCulling(Viewport viewport) {
		return Engine.get().frustumCulling() ? viewport : OpenFrustum.viewportAt(viewport);
	}
}
