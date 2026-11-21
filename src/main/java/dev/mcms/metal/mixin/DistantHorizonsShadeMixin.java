// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import dev.mcms.shaders.runtime.terrain.TerrainEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Distant Horizons' face shade while the engine draws DH through a pack without the old lighting (plan.md E7, DH decision 12):
 * 1 on every face, as the engine's terrain (SodiumEngineLevelSliceMixin). DH bakes a shade into each LOD vertex's colour when it
 * builds its buffers: {@code ClientLevelWrapper.getShade(EDhDirection)} (javap of DH 3.3.4, 2026-11-16) answers, for its default
 * {@code lodShading} AUTO, the client level's {@code cardinalLighting().byFace(...)}, the game's 0.5 below, 0.8 north and south and
 * 0.6 east and west. Under Iris the same pack reads DH's raw vertex colour brighter (an instrumented copy of Complementary that
 * writes {@code gl_Color}, results/2026-11-16-e7-dhprobe2: the far grass 74, 91, 68 here against 91, 115, 79 there, while the normals
 * agree). The answer follows {@link TerrainEngine#distantFlatShade()}; when that flips, TerrainEngine.frameCheck has DH drop its cached
 * render data, so buffers built before the engine's set was active are built again unshaded.
 */
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper")
abstract class DistantHorizonsShadeMixin {

	@Inject(method = "getShade(Lcom/seibel/distanthorizons/core/enums/EDhDirection;)F", at = @At("HEAD"), cancellable = true)
	private void mcmsMetal$packShade(CallbackInfoReturnable<Float> cir) {
		if (TerrainEngine.distantFlatShade()) {
			cir.setReturnValue(1.0f);
		}
	}
}
