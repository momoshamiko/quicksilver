/*
 * Quicksilver: original code (ADR 0006), Apache-2.0. The player cape ids at submit time (plan.md E4, Iris "Hardcoded
 * IDs" of item.properties: minecraft:player_cape and minecraft:elytra_with_cape).
 */
package dev.mcms.metal.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.mcms.shaders.runtime.entities.EntityCapture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Names the player's cape while it is drawn ({@code CapeLayer.submit(PoseStack, SubmitNodeCollector, int,
 * AvatarRenderState, float, float)V}, javap -p -c against minecraft-merged-deobf-26.3.jar, 2026-11-14): the state is
 * argument 4; the cape's {@code submitModel} sits at bytecode 107 inside this overload, and the method returns at 16,
 * 32, 48 and 116 (the RETURN injection covers each). The chest slot decides the id: an elytra makes it
 * {@code minecraft:elytra_with_cape}, and anything else {@code minecraft:player_cape}. The EntityRenderState overload
 * is not hooked: it forwards to this one for players. Inert while no set is active.
 */
@Mixin(CapeLayer.class)
abstract class EngineSubmitCapeLayerMixin {

	@Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
		+ "ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V", at = @At("HEAD"))
	private void mcms$capeItem(PoseStack pose, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot,
			float xRot, CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.capeBegin(state);
		}
	}

	@Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
		+ "ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V", at = @At("RETURN"))
	private void mcms$capeItemEnded(CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.itemEnd();
		}
	}
}
