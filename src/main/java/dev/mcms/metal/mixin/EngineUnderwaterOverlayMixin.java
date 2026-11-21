// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import dev.mcms.shaders.runtime.Engine;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips the water texture the game lays over the screen when the camera is in water, while the engine serves a set (RIL-569).
 * Iris 1.11.7 draws no such overlay over a pack, with {@code underwaterOverlay} unset or false alike
 * (results/2026-11-19-ril569-day: the view from inside the water keeps its grey with and without the directive, where the
 * game tints it), so the engine skips it whatever the directive says ({@link Engine#hidesScreenOverlays}). javap of
 * minecraft-merged-deobf-26.3.jar (2026-11-19): {@code ScreenEffectRenderer.submit} calls the private static
 * {@code submitWater(WaterOverlay, PoseStack, SubmitNodeCollector)} in first person when the player is no spectator and the
 * frame's state holds a water overlay. The mixin stops that method at HEAD; the fire and block overlays stay.
 *
 * <p>Inert while no set is active. An engine mixin ({@code McmsMetalMixinPlugin}).
 */
@Mixin(ScreenEffectRenderer.class)
abstract class EngineUnderwaterOverlayMixin {

	@Inject(method = "submitWater", at = @At("HEAD"), cancellable = true)
	private static void mcms$hideUnderwaterOverlay(CallbackInfo ci) {
		if (Engine.get().hidesScreenOverlays()) {
			ci.cancel();
		}
	}
}
