/*
 * Quicksilver: original code (ADR 0006), Apache-2.0. The per-entity id capture at submit time (plan.md E4).
 */
package dev.mcms.metal.mixin;

import dev.mcms.shaders.runtime.entities.EntityCapture;
import net.minecraft.client.renderer.feature.phase.TranslucentFeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.TranslucentSubmit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The translucent phase's own record store ({@code TranslucentFeatureRenderPhase.submit(TranslucentSubmit)}, javap -p
 * -c against minecraft-merged-deobf-26.3.jar, 2026-11-14: the record is added to the {@code submits} list at bytecode
 * 5; the {@code submit(SubmitNode)} bridge at bytecode 5 forwards here), so translucent model submits — the blended
 * ones {@code submitModel} stores through the {@code translucentModels} phase — carry their ids too. Inert while no
 * set is active.
 */
@Mixin(TranslucentFeatureRenderPhase.class)
abstract class EngineSubmitTranslucentFeatureRenderPhaseMixin {

	@Inject(method = "submit(Lnet/minecraft/client/renderer/feature/submit/TranslucentSubmit;)V", at = @At("TAIL"))
	private void mcms$attachIds(TranslucentSubmit node, CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.attach(node);
		}
	}
}
