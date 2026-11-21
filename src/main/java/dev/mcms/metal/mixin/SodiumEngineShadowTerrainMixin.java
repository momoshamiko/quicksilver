// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import dev.mcms.shaders.runtime.Engine;
import dev.mcms.shaders.runtime.terrain.ShadowTerrain;
import dev.mcms.shaders.runtime.terrain.SodiumInitialBuildPort;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Camera;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Builds the light's terrain list at the tail of {@code SodiumWorldRenderer.setupTerrain} (E5-B2; plan.md E5; hooks
 * section 4, table row 9), and submits the first builds of the sections that list reaches (E6-B2; {@code LightInitialBuilds}).
 * Setup runs on the render thread after the camera's lists are final, so the main lists are untouched: the light's list is
 * built by {@code ShadowTerrain.build} while {@code SodiumEngineRenderRegionMixin} answers the engine's list.
 *
 * <p>Inert with no attached shadow camera: the handler reads nothing else and returns (the engine is off or the set has no
 * shadow pass). The render distance in blocks is the render-section manager's {@code renderDistance} times 16, the same
 * value Sodium's private {@code getRenderDistance} returns. A throw in the light's list ends the frame's setup: the build is
 * guarded, logs once, and detaches the light's lists, so the frame keeps the vanilla shadows. A throw in the first builds does
 * not reach here: {@code LightInitialBuilds} logs it and turns the first builds off, and the light's list keeps its builds.
 *
 * <p>The first-build port is kept while the render-section manager is the same object (a world reload makes a new manager and
 * a new port), so a frame makes no port.
 *
 * <p>Javap 2026-11-14, both jars ADR 0007 allows: {@code setupTerrain(Lnet/minecraft/client/Camera;Lnet/caffeinemc/mods/
 * sodium/client/render/viewport/Viewport;Lnet/caffeinemc/mods/sodium/client/util/FogParameters;ZZLorg/joml/Matrix4f;)V},
 * one {@code return} at bytecode 574 on beta.1 and on beta.5 (the TAIL injection goes before it). The handler takes every
 * argument in order (MixinAuditTest's rule for an {@code @Inject} with arguments).
 *
 * <p>The position matrix is the camera's clip matrix of this frame: Sodium's {@code LevelExtractorMixin.cullTerrain} passes
 * the game frustum's matrix ({@code FrustumAccessor.sodium$getMatrix}), which {@code Frustum.calculateFrustum} sets to the
 * projection times the model view (javap 2026-11-17, Sodium 0.9.3-beta.1 and the 26.3 game jar). The light's build reads it
 * for the cull of casters that cannot shade the view (E9, {@code CasterFrustum}).
 */
@Mixin(SodiumWorldRenderer.class)
abstract class SodiumEngineShadowTerrainMixin {

	/** The first-build port, made once per render-section manager (see the class comment). */
	private SodiumInitialBuildPort mcmsMetal$initialBuilds;

	@Inject(method = "setupTerrain", at = @At("TAIL"))
	private void mcmsMetal$buildLightList(Camera camera, Viewport viewport, FogParameters fog, boolean useOcclusionCulling,
		boolean renderDistanceChanged, Matrix4f positionMatrix, CallbackInfo ci) {
		if (!ShadowTerrain.get().attached()) {
			return;
		}
		try {
			RenderSectionManager manager = ((SodiumEngineWorldRendererAccessor) (Object) this).mcmsMetal$renderSectionManager();
			if (manager == null) {
				return;
			}
			SodiumEngineRenderSectionManagerAccessor sections = (SodiumEngineRenderSectionManagerAccessor) (Object) manager;
			if (this.mcmsMetal$initialBuilds == null || !this.mcmsMetal$initialBuilds.isFor(manager)) {
				this.mcmsMetal$initialBuilds = new SodiumInitialBuildPort(manager, sections);
			}
			CameraTransform at = viewport.getTransform();
			ShadowTerrain.get().build(sections.mcmsMetal$renderableSectionTree(), manager.regions, sections.mcmsMetal$renderSections(),
				sections.mcmsMetal$frame(), sections.mcmsMetal$renderDistance() * 16f, at.x, at.y, at.z, positionMatrix,
				this.mcmsMetal$initialBuilds);
		} catch (Throwable t) {
			Engine.shadowListFailed("the shadow terrain list", t);
		}
	}
}
