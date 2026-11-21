/*
 * Quicksilver: original code (ADR 0006), Apache-2.0. The block-entity split's world-phase draws (plan.md E4).
 */
package dev.mcms.metal.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import dev.mcms.shaders.runtime.blockentity.BlockEntityRouter;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the block-entity frame's translucent phases after the world frame's own, in the block-entity domain window
 * (plan.md E4, the pipeline-mapping bullet's block-entity rule). Each of {@code FeatureRenderDispatcher$PreparedFrame}
 * 's phase executions — {@code executeTranslucent(RenderPass)} (the world's call is {@code LevelRenderer.
 * executeClassicTransparency} bytecode 66, before the translucent terrain at 105), {@code executeSeeThrough}
 * ({@code LevelRenderer.executeSeeThrough} bytecode 43), {@code executeAlwaysOnTop} ({@code LevelRenderer.
 * executeAlwaysOnTop} bytecode 72) and {@code executeOutline} ({@code LevelRenderer.executeOutline} bytecode 49);
 * javap of minecraft-merged-deobf-26.3.jar, 2026-11-14 — ends with the router drawing the same phase of its frame on
 * the same open pass, when the executing frame is the world's (the identity the phase hook recorded; the hand's and
 * the screen effects' frames are not). {@code executeTranslucentAfterTerrain} and {@code waterMask} have no hook: no
 * block-entity submit lands in them. Inert while no set is active: the router's guard returns.
 */
@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
abstract class EnginePreparedFrameMixin {

	@Inject(method = "executeTranslucent", at = @At("RETURN"))
	private void mcms$blockEntitiesTranslucent(RenderPass renderPass, CallbackInfo ci) {
		BlockEntityRouter.get().worldPhaseDraw(this, renderPass, BlockEntityRouter.WorldPhase.TRANSLUCENT);
	}

	@Inject(method = "executeSeeThrough", at = @At("RETURN"))
	private void mcms$blockEntitiesSeeThrough(RenderPass renderPass, CallbackInfo ci) {
		BlockEntityRouter.get().worldPhaseDraw(this, renderPass, BlockEntityRouter.WorldPhase.SEE_THROUGH);
	}

	@Inject(method = "executeAlwaysOnTop", at = @At("RETURN"))
	private void mcms$blockEntitiesAlwaysOnTop(RenderPass renderPass, CallbackInfo ci) {
		BlockEntityRouter.get().worldPhaseDraw(this, renderPass, BlockEntityRouter.WorldPhase.ALWAYS_ON_TOP);
	}

	@Inject(method = "executeOutline", at = @At("RETURN"))
	private void mcms$blockEntitiesOutline(RenderPass renderPass, CallbackInfo ci) {
		BlockEntityRouter.get().worldPhaseDraw(this, renderPass, BlockEntityRouter.WorldPhase.OUTLINE);
	}
}
