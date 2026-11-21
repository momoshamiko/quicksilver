/*
 * Quicksilver: original code (ADR 0006), Apache-2.0. The per-entity id capture at submit time (plan.md E4,
 * "Per-entity values").
 */
package dev.mcms.metal.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.mcms.shaders.runtime.entities.EntityCapture;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.UvMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Resolves the ids of a submit (plan.md E4). Model submits: entities and block entities (with armor, through the
 * model path) carry their identity in the render state, and the phase hooks attach the ids to the submit records the
 * submit call stores. {@code SubmitNodeCollection.submitModel}, javap -p -c against minecraft-merged-deobf-26.3.jar
 * (2026-11-14): the state is argument 1; the main record's phase {@code submit} calls sit at bytecode 55
 * ({@code waterMask}), 83 ({@code solid}) and 95 ({@code translucentModels}, the interface call), the outline
 * record's at 145, so the pending ids must span the whole call (cleared at RETURN, which covers the early paths none
 * of which store a record: every storing path reaches 148).
 *
 * <p>Hardcoded ids (Iris entity.properties, "Hardcoded IDs"): the submits that are not model submits store their
 * records directly, and bracket them with their own id. {@code submitNameTag(PoseStack, Vec3, int, Component,
 * boolean, int, CameraRenderState)V}: HEAD at bytecode 0; its record stores are the {@code submitNameTagPart} calls at
 * 180 and 234 and the {@code seeThrough} store at 207; RETURN at 4 and 241. {@code submitShadow(PoseStack, float, List)V}: HEAD at 0, the store at
 * 31 ({@code shadows}), RETURN at 34. {@code submitFlame(PoseStack, EntityRenderState, Quaternionf)V}: HEAD at 0, the
 * store at 20 ({@code solid}), RETURN at 23. None of the three calls {@code submitModel}, so the bracket's pending
 * ids reach the store unchanged.
 *
 * <p>Other submit methods ({@code submitItem} and the rest) carry no entity identity and read the default ids. Inert
 * while no set is active: one volatile read.
 */
@Mixin(SubmitNodeCollection.class)
abstract class EngineSubmitNodeCollectionMixin {

	@Inject(method = "submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;"
		+ "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;III"
		+ "Lnet/minecraft/client/renderer/texture/UvMapping;I)V", at = @At("HEAD"))
	private void mcms$modelIds(Model<?> model, Object state, PoseStack pose, RenderType renderType, int lightCoords,
			int overlayCoords, int tintedColor, UvMapping uvMapping, int outlineColor, CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.modelSubmit(state);
		}
	}

	@Inject(method = "submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;"
		+ "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;III"
		+ "Lnet/minecraft/client/renderer/texture/UvMapping;I)V", at = @At("RETURN"))
	private void mcms$modelIdsSpent(Model<?> model, Object state, PoseStack pose, RenderType renderType,
			int lightCoords, int overlayCoords, int tintedColor, UvMapping uvMapping, int outlineColor,
			CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.clearPending();
		}
	}

	@Inject(method = "submitNameTag(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/phys/Vec3;ILnet/minecraft/"
		+ "network/chat/Component;ZILnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("HEAD"))
	private void mcms$nameTagIds(CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.nameTagSubmit();
		}
	}

	@Inject(method = "submitNameTag(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/phys/Vec3;ILnet/minecraft/"
		+ "network/chat/Component;ZILnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("RETURN"))
	private void mcms$nameTagIdsSpent(CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.clearPending();
		}
	}

	@Inject(method = "submitShadow(Lcom/mojang/blaze3d/vertex/PoseStack;FLjava/util/List;)V", at = @At("HEAD"))
	private void mcms$shadowIds(CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.shadowSubmit();
		}
	}

	@Inject(method = "submitShadow(Lcom/mojang/blaze3d/vertex/PoseStack;FLjava/util/List;)V", at = @At("RETURN"))
	private void mcms$shadowIdsSpent(CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.clearPending();
		}
	}

	@Inject(method = "submitFlame(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/entity/state/"
		+ "EntityRenderState;Lorg/joml/Quaternionf;)V", at = @At("HEAD"))
	private void mcms$flameIds(CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.flameSubmit();
		}
	}

	@Inject(method = "submitFlame(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/entity/state/"
		+ "EntityRenderState;Lorg/joml/Quaternionf;)V", at = @At("RETURN"))
	private void mcms$flameIdsSpent(CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.clearPending();
		}
	}
}
