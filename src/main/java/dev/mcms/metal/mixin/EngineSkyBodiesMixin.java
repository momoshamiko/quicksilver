// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import dev.mcms.shaders.runtime.Engine;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips the game's sun and moon while the engine serves a set whose shaders.properties says {@code sun=false} or
 * {@code moon=false} (RIL-569; the docs: their textures in {@code gbuffers_skytextured}), as Iris 1.11.7 does
 * (results/2026-11-19-ril569-day and -night: no sun or moon pixel with the directives, the same count as Iris without
 * them). javap of minecraft-merged-deobf-26.3.jar (2026-11-19): {@code SkyRenderer.renderSunMoonAndStars} calls the private
 * {@code renderSun(RenderPass, float, PoseStack)} and {@code renderMoon(RenderPass, MoonPhase, float, PoseStack)}, each
 * between its own pose push and pop; the mixin stops each at HEAD, so the pose stack stays balanced and the other bodies
 * draw as before. Iris draws the stars under {@code stars=false} (the same star count with and without it), so this mixin
 * leaves them.
 *
 * <p>Inert while no set is active or the set leaves the directive unset or true ({@link Engine#hidesVanilla}). An engine
 * mixin ({@code McmsMetalMixinPlugin}).
 */
@Mixin(SkyRenderer.class)
abstract class EngineSkyBodiesMixin {

	@Inject(method = "renderSun", at = @At("HEAD"), cancellable = true)
	private void mcms$hideSun(CallbackInfo ci) {
		if (Engine.get().hidesVanilla("sun")) {
			ci.cancel();
		}
	}

	@Inject(method = "renderMoon", at = @At("HEAD"), cancellable = true)
	private void mcms$hideMoon(CallbackInfo ci) {
		if (Engine.get().hidesVanilla("moon")) {
			ci.cancel();
		}
	}
}
