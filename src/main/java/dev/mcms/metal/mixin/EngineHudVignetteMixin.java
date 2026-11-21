// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import dev.mcms.shaders.runtime.Engine;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips the game's vignette while the engine serves a set (RIL-569). javap of minecraft-merged-deobf-26.3.jar (2026-11-19):
 * {@code Hud.extractCameraOverlays} calls the private {@code extractVignette(GuiGraphicsExtractor, Entity)} when the
 * Vignette option is on, and the option is on by default ({@code Options}: {@code createBoolean("options.vignette", ...,
 * true)}), so the game darkened the corners over every pack's picture. Iris 1.11.7 draws no vignette over a pack, with
 * {@code vignette} unset or false alike (results/2026-11-19-ril569-night: the corners as bright as the middle at midnight),
 * so the engine skips it whatever the directive says ({@link Engine#hidesScreenOverlays}). The mixin stops the method at
 * HEAD; the vignette's brightness keeps updating ({@code Hud.tick} calls {@code updateVignetteBrightness}). The game draws
 * its red world-border warning with the same blit (the method computes the border distance first), so that warning goes
 * with the vignette; Iris's behaviour at the border is not measured.
 *
 * <p>Inert while no set is active. An engine mixin ({@code McmsMetalMixinPlugin}).
 */
@Mixin(Hud.class)
abstract class EngineHudVignetteMixin {

	@Inject(method = "extractVignette", at = @At("HEAD"), cancellable = true)
	private void mcms$hideVignette(CallbackInfo ci) {
		if (Engine.get().hidesScreenOverlays()) {
			ci.cancel();
		}
	}
}
