/*
 * Quicksilver: original code (ADR 0006), Apache-2.0. The item half of the per-entity id capture (plan.md E4,
 * "Per-entity values"): the bracket that spends the item of the state being submitted.
 */
package dev.mcms.metal.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.mcms.shaders.runtime.entities.EntityCapture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes the item render state's submits carry the state's item id. javap -p -c against
 * minecraft-merged-deobf-26.3.jar (2026-11-14): {@code ItemStackRenderState.submit} loops its layers and calls each
 * {@code LayerRenderState.submit} at bytecode 26; {@code LayerRenderState.submit} calls
 * {@code SubmitNodeCollector.submitItem} at bytecode 108 (the outer state, this$0, at bytecode 87), so pending ids
 * set around the outer {@code submit} span every {@code submitItem} a state makes — and no other path reaches
 * {@code submitItem} without a state (javap: its only callers are {@code LayerRenderState.submit} and
 * {@code SubmitNodeStorage.submitItem}, the forwarding interface). {@code submitItem} itself stores its records
 * through the phases' {@code submit} at bytecode 49 (translucent, interface), 94 (solid) and 102+ (outline), the
 * calls the attach hooks already cover. The hand, dropped items, item frames, mob items and shelf items all end
 * here. Inert while no set is active: one volatile read.
 */
@Mixin(ItemStackRenderState.class)
abstract class EngineSubmitItemStackRenderStateMixin {

	@Inject(method = "submit", at = @At("HEAD"))
	private void mcms$itemIds(PoseStack pose, SubmitNodeCollector collector, int lightCoords, int overlayCoords,
			int tintedColor, CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.itemSubmit(this);
		}
	}

	@Inject(method = "submit", at = @At("RETURN"))
	private void mcms$itemIdsSpent(PoseStack pose, SubmitNodeCollector collector, int lightCoords, int overlayCoords,
			int tintedColor, CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.clearPending();
		}
	}
}
