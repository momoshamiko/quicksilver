/*
 * Quicksilver: original code (ADR 0006), Apache-2.0. The armour item id at submit time (plan.md E4, engine-pack-format
 * section 10: currentRenderedItemId for armour).
 */
package dev.mcms.metal.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.mcms.shaders.runtime.entities.EntityCapture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Names the armour piece being drawn ({@code HumanoidArmorLayer.renderArmorPiece(PoseStack, SubmitNodeCollector,
 * ItemStack, EquipmentSlot, int, HumanoidRenderState)V}, private, javap -p -c against minecraft-merged-deobf-26.3.jar,
 * 2026-11-14): the stack is argument 2; the piece's model submits run through {@code EquipmentLayerRenderer.renderLayers}
 * inside this call. HEAD at bytecode 0; the returns sit at 27 and 117 (both covered by the RETURN injection). The
 * armour's submits then carry the stack's item id, and an armour trim's submits carry the trim id (see
 * {@code EngineSubmitEquipmentLayerRendererMixin}). Inert while no set is active.
 */
@Mixin(HumanoidArmorLayer.class)
abstract class EngineSubmitHumanoidArmorLayerMixin {

	@Inject(method = "renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;"
		+ "Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/world/item/ItemStack;"
		+ "Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V",
		at = @At("HEAD"))
	private void mcms$armorItem(PoseStack pose, SubmitNodeCollector collector, ItemStack stack, EquipmentSlot slot,
			int lightCoords, HumanoidRenderState state, CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.armorBegin(stack);
		}
	}

	@Inject(method = "renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;"
		+ "Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/world/item/ItemStack;"
		+ "Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V",
		at = @At("RETURN"))
	private void mcms$armorItemEnded(CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.itemEnd();
		}
	}
}
