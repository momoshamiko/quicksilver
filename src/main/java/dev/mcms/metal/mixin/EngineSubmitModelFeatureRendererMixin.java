/*
 * Quicksilver: original code (ADR 0006), Apache-2.0. The per-entity id preparation bracket (plan.md E4).
 */
package dev.mcms.metal.mixin;

import dev.mcms.shaders.runtime.entities.EntityCapture;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks which submit record's vertices the next vertex builder request will write ({@code ModelFeatureRenderer.
 * prepareModel(Submit)}, javap -p -c against minecraft-merged-deobf-26.3.jar, 2026-11-14: the inherited
 * {@code getVertexBuilder(RenderType)} call sits at bytecode 19 and {@code Model.renderToBuffer} — the writes — at
 * bytecode 96, both inside this one call, so the record named at HEAD owns the builder handed out inside it; the
 * range itself closes at the next builder request or the upload, in {@code EngineSubmitStagedVertexBufferMixin}).
 * Inert while no set is active.
 */
@Mixin(ModelFeatureRenderer.class)
abstract class EngineSubmitModelFeatureRendererMixin {

	@Inject(method = "prepareModel", at = @At("HEAD"))
	private void mcms$preparing(ModelFeatureRenderer.Submit<?> submit, CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.prepareRecord(submit);
		}
	}

	@Inject(method = "prepareModel", at = @At("RETURN"))
	private void mcms$prepared(ModelFeatureRenderer.Submit<?> submit, CallbackInfo ci) {
		if (EntityCapture.active()) {
			EntityCapture.prepareDone();
		}
	}
}
