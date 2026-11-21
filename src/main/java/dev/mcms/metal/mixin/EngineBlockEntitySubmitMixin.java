/*
 * Quicksilver: original code (ADR 0006), Apache-2.0. The block-entity split's submit hook (plan.md E4).
 */
package dev.mcms.metal.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.mcms.shaders.runtime.Engine;
import dev.mcms.shaders.runtime.blockentity.BlockEntityRouter;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Routes the block entities into the engine's storage (plan.md E4, the pipeline-mapping bullet's block-entity rule):
 * the call of {@code LevelRenderer.submitBlockEntities(PoseStack, LevelRenderState, SubmitNodeCollector)V} inside
 * {@code submitFeatures} — bytecode 31, the method's only call of it (javap of minecraft-merged-deobf-26.3.jar,
 * 2026-11-14) — runs unchanged, but with the router's storage in place of the game's collector, so the block
 * entities' submits never batch with the entities' ({@code RenderTypeFeatureRenderer$Group} merges every submit that
 * shares a render type into one draw); the router prepares its frame right after. The states list needs no care:
 * {@code submitFeatures} clears it after the call (bytecodes 34-38), whatever collector filled it.
 *
 * <p>Why a call-site wrap and not a cancelled body plus an invoker: a mixin {@code @Invoker} calls the transformed
 * method, so a body inject would fire again on every re-entry and recurse (the StackOverflowError of the 2026-11-14
 * preview 8 run; MixinAuditTest now fails that pair). Inert while no set is active or the takeover serves no world:
 * one state read, the original call runs with the game's collector.
 */
@Mixin(LevelRenderer.class)
abstract class EngineBlockEntitySubmitMixin {

	@WrapOperation(method = "submitFeatures", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/LevelRenderer;submitBlockEntities(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V"))
	private void mcms$submitBlockEntitiesIntoTheRouter(LevelRenderer self, PoseStack pose, LevelRenderState state,
			SubmitNodeCollector collector, Operation<Void> original) {
		if (!Engine.get().drawsBlockEntities()) {
			original.call(self, pose, state, collector);
			return;
		}
		BlockEntityRouter router = BlockEntityRouter.get();
		original.call(self, pose, state, router.storage());
		router.prepare();
	}
}
