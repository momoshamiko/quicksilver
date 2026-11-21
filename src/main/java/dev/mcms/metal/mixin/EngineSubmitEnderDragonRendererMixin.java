/*
 * Quicksilver: original code (ADR 0006), Apache-2.0. The end crystal beam id at submit time (plan.md E4, Iris
 * "Hardcoded IDs": minecraft:end_crystal_beam).
 */
package dev.mcms.metal.mixin;

import dev.mcms.shaders.runtime.entities.EntityCapture;
import net.minecraft.client.renderer.entity.EnderDragonRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brackets the end crystal healing beams ({@code EnderDragonRenderer.submitCrystalBeams(float, float, float, float,
 * PoseStack, SubmitNodeCollector, int)V}, static, javap -p -c against minecraft-merged-deobf-26.3.jar, 2026-11-14):
 * the beam is one {@code SubmitNodeCollector.submitCustomGeometry} call at bytecode 127, which stores its record in
 * {@code translucentCustomGeometry} (or {@code outline} and {@code solid}) through the phase hooks. HEAD at bytecode 0
 * and RETURN at 137 bound the bracket. Inert while no set is active.
 */
@Mixin(EnderDragonRenderer.class)
abstract class EngineSubmitEnderDragonRendererMixin {

	@Inject(method = "submitCrystalBeams(FFFFLcom/mojang/blaze3d/vertex/PoseStack;"
		+ "Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V", at = @At("HEAD"))
	private static void mcms$crystalBeamIds(CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.crystalBeamSubmit();
		}
	}

	@Inject(method = "submitCrystalBeams(FFFFLcom/mojang/blaze3d/vertex/PoseStack;"
		+ "Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V", at = @At("RETURN"))
	private static void mcms$crystalBeamIdsSpent(CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.clearPending();
		}
	}
}
