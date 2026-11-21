/*
 * Quicksilver: original code (ADR 0006), Apache-2.0. The item draws' preparation bracket (plan.md E4,
 * currentRenderedItemId).
 */
package dev.mcms.metal.mixin;

import dev.mcms.shaders.runtime.entities.EntityCapture;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks which item submit's vertices the next builder request writes ({@code ItemFeatureRenderer.prepareSubmit(Submit)},
 * private, javap -p -c against minecraft-merged-deobf-26.3.jar, 2026-11-14). {@code buildGroup} calls it once per submit
 * (the invoke at bytecode 30 inside its loop), and the submit's builder requests ({@code getVertexBuilder} in
 * {@code prepareMainSubmit} and {@code prepareOutlineSubmit}) all happen inside it, so each item's vertices take its own
 * record's ids. The method's only return sits at bytecode 20. Items reach this renderer through
 * {@code SubmitNodeCollection.submitItem}, so item draws carry {@code currentRenderedItemId}. Inert while no set is
 * active.
 */
@Mixin(ItemFeatureRenderer.class)
abstract class EngineSubmitItemFeatureRendererMixin {

	@Inject(method = "prepareSubmit(Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer$Submit;)V",
		at = @At("HEAD"))
	private void mcms$preparing(ItemFeatureRenderer.Submit submit, CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.prepareRecord(submit);
		}
	}

	@Inject(method = "prepareSubmit(Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer$Submit;)V",
		at = @At("RETURN"))
	private void mcms$prepared(ItemFeatureRenderer.Submit submit, CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.prepareDone();
		}
	}
}
