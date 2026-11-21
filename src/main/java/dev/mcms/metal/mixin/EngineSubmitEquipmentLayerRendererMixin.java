/*
 * Quicksilver: original code (ADR 0006), Apache-2.0. The armour trim id at submit time (plan.md E4, Iris "Hardcoded
 * IDs" of item.properties: trim_<material>).
 */
package dev.mcms.metal.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.mcms.shaders.runtime.entities.EntityCapture;
import net.minecraft.client.model.Model;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Names an armour trim while its layers are drawn ({@code EquipmentLayerRenderer.renderLayers(LayerType,
 * ResourceKey, Model, S, ItemStack, PoseStack, SubmitNodeCollector, int, Identifier, int, int)V}, javap -p -c against
 * minecraft-merged-deobf-26.3.jar, 2026-11-14). The overload's {@code OrderedSubmitNodeCollector.submitModel} calls are
 * ordinal 0 (the base layers, bytecode 236), ordinal 1 (the trim pattern, bytecode 335) and ordinal 2 (the trimmed
 * armour glint, bytecode 373); the trim pair brackets ordinals 1 and 2 (BEFORE pushes the stack's trim id, AFTER pops
 * it), so the base layers keep the armour id. The stack is argument 4. The overload without the Identifier argument
 * delegates to this one and holds no submit. Inert while no set is active.
 */
@Mixin(EquipmentLayerRenderer.class)
abstract class EngineSubmitEquipmentLayerRendererMixin {

	private static final String RENDER_LAYERS = "renderLayers(Lnet/minecraft/client/resources/model/"
		+ "EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;"
		+ "Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;"
		+ "Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V";
	private static final String SUBMIT_MODEL = "Lnet/minecraft/client/renderer/OrderedSubmitNodeCollector;"
		+ "submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;"
		+ "Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/UvMapping;I)V";

	@SuppressWarnings("rawtypes")
	@Inject(method = RENDER_LAYERS, at = @At(value = "INVOKE", target = SUBMIT_MODEL, ordinal = 1))
	private void mcms$trimBeforePattern(EquipmentClientInfo.LayerType type, ResourceKey layers, Model model,
			Object state, ItemStack stack, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int lightCoords,
			Identifier playerTextureOverride, int outlineColor, int order, CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.trimBegin(stack);
		}
	}

	@Inject(method = RENDER_LAYERS, at = @At(value = "INVOKE", target = SUBMIT_MODEL, ordinal = 1,
		shift = At.Shift.AFTER))
	private void mcms$trimAfterPattern(CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.itemEnd();
		}
	}

	@SuppressWarnings("rawtypes")
	@Inject(method = RENDER_LAYERS, at = @At(value = "INVOKE", target = SUBMIT_MODEL, ordinal = 2))
	private void mcms$trimBeforeGlint(EquipmentClientInfo.LayerType type, ResourceKey layers, Model model,
			Object state, ItemStack stack, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int lightCoords,
			Identifier playerTextureOverride, int outlineColor, int order, CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.trimBegin(stack);
		}
	}

	@Inject(method = RENDER_LAYERS, at = @At(value = "INVOKE", target = SUBMIT_MODEL, ordinal = 2,
		shift = At.Shift.AFTER))
	private void mcms$trimAfterGlint(CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.itemEnd();
		}
	}
}
