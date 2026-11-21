// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import dev.mcms.shaders.runtime.terrain.TerrainEngine;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.world.level.CardinalLighting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hook 15 of the engine's Sodium set (plan.md E7; engine-sodium-hooks.md table row 15): while a set whose pack does not
 * ask for the old lighting is active, the chunk-build slice answers a cardinal lighting of 1 on every face, so the
 * directional shade (the game's 0.5 below, 0.8 north and south, 0.6 east and west) never enters a vertex's brightness.
 * Iris draws pack terrain that way (measured with the mcms-vcolor packs, plan.md E7).
 *
 * <p>Javap 2026-11-15, both jars ADR 0007 allows (0.9.3-beta.1 and 0.9.3-beta.5): {@code LevelSlice.cardinalLighting()
 * Lnet/minecraft/world/level/CardinalLighting;} returns its {@code ClientLevel}'s answer (bytecode 0-7, the same in both
 * jars). Sodium's light pipelines read the shade only through it: {@code SmoothLightPipeline.getAmbientBrightness} and
 * {@code FlatLightPipeline.getShade} call {@code lightCache.getLevel().cardinalLighting().byFace(...)}, and the light
 * cache's level is the build's slice. The game's own block rendering outside the chunk builds (falling blocks, pistons
 * in motion) reads the client level and keeps its shade (not measured against Iris).
 */
@Mixin(LevelSlice.class)
abstract class SodiumEngineLevelSliceMixin {
	@Inject(method = "cardinalLighting", at = @At("HEAD"), cancellable = true)
	private void mcmsMetal$packShade(CallbackInfoReturnable<CardinalLighting> cir) {
		if (TerrainEngine.flatShade()) {
			cir.setReturnValue(TerrainEngine.FLAT_SHADE);
		}
	}
}
