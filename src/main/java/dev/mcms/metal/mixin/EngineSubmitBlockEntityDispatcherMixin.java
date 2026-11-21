/*
 * Quicksilver: original code (ADR 0006), Apache-2.0. The per-entity id capture at submit time (plan.md E4).
 */
package dev.mcms.metal.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.mcms.shaders.runtime.entities.EntityCapture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Names the block entity being submitted ({@code blockEntityId} from {@code block.properties}, plan.md E4): some
 * block-entity renderers pass something else than the render state to {@code submitModel} (the chest, shulker-box
 * and banner renderers pass {@code Float.valueOf(...)} — javap of their {@code submit} methods against
 * minecraft-merged-deobf-26.3.jar), so the model submit hook cannot always read the identity from its argument.
 * {@code BlockEntityRenderDispatcher.submit}, javap -p -c (2026-11-14): the render state is argument 0 and every
 * renderer's submits happen inside the {@code BlockEntityRenderer.submit} call at bytecode 20, so the context set
 * here spans them. Inert while no set is active: one volatile read.
 */
@Mixin(BlockEntityRenderDispatcher.class)
abstract class EngineSubmitBlockEntityDispatcherMixin {

	@Inject(method = "submit", at = @At("HEAD"))
	private void mcms$blockEntity(net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState state,
			PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.blockEntitySubmit(state);
		}
	}

	@Inject(method = "submit", at = @At("RETURN"))
	private void mcms$blockEntityDone(net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState state,
			PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.blockEntitySubmitDone();
		}
	}
}
