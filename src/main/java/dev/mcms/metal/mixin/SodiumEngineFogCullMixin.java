// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import dev.mcms.shaders.runtime.Engine;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium's fog occlusion off while a set serves the world (RIL-571; {@link Engine#fogCullsTerrain}). Javap of both jars
 * ADR 0007 allows: {@code RenderSectionManager.getSearchDistance(FogParameters)} returns {@code getRenderDistance()} when
 * {@code SodiumOptions$PerformanceSettings.useFogOcclusion} is off, and otherwise {@code getEffectiveRenderDistance},
 * which with an opaque fog colour caps the render distance at the fog's cull distance ({@code FogParameters.cullDistance}:
 * the lesser of the game's environmental and render-distance fog ends) plus 0.5. Every search of the camera's sections
 * reads this method (the async cull task's distance, the section trees, the out-of-graph fallback), so returning the
 * render distance at its head, as Sodium does with the option off, decides them all.
 *
 * <p>Under water the game ends its fog at {@code WATER_FOG_END_DISTANCE} scaled by the player's water vision, and Sodium
 * then drops every section past it; Iris 1.11.7 draws a pack's far terrain there (results/2026-11-19-ril569-day, scene
 * overlays-water). In the overworld above water the environmental end ({@code FOG_END_DISTANCE}, 1024 blocks by default,
 * javap of the 26.3 jar) lies past every render distance, so the search distance is the same either way. The Nether's
 * dimension type ends its fog at 96 blocks, a boss bar's world fog caps the end at 96, and lava, powder snow, blindness
 * and darkness end it closer: there the search grows to the render distance too, not measured against Iris. While no
 * set serves the world, the player's own setting stands.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
abstract class SodiumEngineFogCullMixin {

	@Shadow
	private float getRenderDistance() {
		throw new AssertionError();
	}

	@Inject(method = "getSearchDistance", at = @At("HEAD"), cancellable = true)
	private void mcmsMetal$noFogCull(FogParameters fog, CallbackInfoReturnable<Float> cir) {
		if (!Engine.get().fogCullsTerrain()) {
			cir.setReturnValue(getRenderDistance());
		}
	}
}
