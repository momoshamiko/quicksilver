// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import dev.mcms.shaders.runtime.terrain.TerrainEngine;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Hook 16 of the engine's Sodium set (plan.md E7; engine-sodium-hooks.md table row 16), the fluid half of hook 15: while a
 * set whose pack does not ask for the old lighting is active, a fluid face's shade is 1, so the side shade never enters a
 * fluid vertex's brightness. Iris draws pack fluids that way (measured with the fluids-smoke set and mcms-vcolor-ft: a
 * water and a lava cube's side faces carry alpha 1 on Iris, 0.8 and 0.6 before this hook).
 *
 * <p>Javap 2026-11-16, both jars ADR 0007 allows (0.9.3-beta.1 and 0.9.3-beta.5): Sodium's fluid renderer does not read
 * the slice's cardinal lighting (hook 15 does not reach it). {@code render} passes {@code updateQuad} a constant shade, its
 * one float parameter: 1 for the top and bottom faces, 0.8 for a face on the Z axis and 0.6 on the X axis (render bytecode
 * 1393, 1585 and 2250-2259), and {@code updateQuad} multiplies each vertex's light brightness by it (bytecode 108-125); the
 * light pipeline's own call passes {@code shade = false} (bytecode 53). The method is the same in both jars.
 */
@Mixin(DefaultFluidRenderer.class)
abstract class SodiumEngineFluidShadeMixin {
	@ModifyVariable(method = "updateQuad", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private float mcmsMetal$packFluidShade(float shade) {
		return TerrainEngine.flatShade() ? 1.0f : shade;
	}
}
