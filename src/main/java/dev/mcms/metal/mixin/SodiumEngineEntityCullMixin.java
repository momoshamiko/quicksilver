// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import dev.mcms.shaders.runtime.shadow.ShadowEntities;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium's entity culling answers the light's entity extraction with the light's frustum alone (E5 review item 7). Sodium's
 * {@code EntityRendererMixin} wraps {@code EntityRenderer.shouldRender} as {@code SodiumWorldRenderer.isEntityVisible(...) &&
 * original} (javap beta.1 and beta.5, identical); {@code isEntityVisible} tests the camera's render tree, so a caster outside
 * the camera's view would drop out of the shadow. {@link ShadowEntities} sets {@code cameraCullingWaived} around its
 * {@code shouldRender} call only; every other call (the camera's own extraction, the draws) keeps Sodium's test.
 *
 * <p>The method is {@code isEntityVisible(EntityRenderer, Entity, float)Z}, one declaration on both releases (javap 2026-11-14).
 */
@Mixin(SodiumWorldRenderer.class)
abstract class SodiumEngineEntityCullMixin {

	@Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true)
	private void mcms$lightFrustumDecides(EntityRenderer<?, ?> renderer, Entity entity, float tickDelta,
			CallbackInfoReturnable<Boolean> cir) {
		if (ShadowEntities.get().cameraCullingWaived()) {
			cir.setReturnValue(true);
		}
	}
}
