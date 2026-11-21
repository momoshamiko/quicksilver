/*
 * Quicksilver: original code (ADR 0006), Apache-2.0. The per-entity id capture at submit time (plan.md E4).
 */
package dev.mcms.metal.mixin;

import dev.mcms.shaders.runtime.entities.EntityCapture;
import net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Attaches the pending ids to each submit record a phase stores ({@code SimpleFeatureRenderPhase.submit(SubmitNode)},
 * javap -p -c against minecraft-merged-deobf-26.3.jar, 2026-11-14: the record goes into the {@code submitsByFeature}
 * array of its feature type). The preparation bracket later looks the ids up by record identity. Inert while no set
 * is active.
 */
@Mixin(SimpleFeatureRenderPhase.class)
abstract class EngineSubmitSimpleFeatureRenderPhaseMixin {

	@Inject(method = "submit(Lnet/minecraft/client/renderer/feature/submit/SubmitNode;)V", at = @At("TAIL"))
	private void mcms$attachIds(SubmitNode node, CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.attach(node);
		}
	}
}
