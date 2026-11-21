// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.mcms.shaders.runtime.Engine;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Improved (order-independent) transparency is off while the engine's takeover serves the world (plan.md E4,
 * "Phases": the level's one "Solid" pass takes the classic path the phase hook splits). {@code
 * GameRenderer.useImprovedTransparency()Z} (javap of minecraft-merged-deobf-26.3.jar, 2026-11-14): its bytecode 0 reads
 * {@code optionsRenderState.improvedTransparency} and {@code levelRenderState.renderWireframeTerrain}, and its only
 * return is {@code ireturn} at bytecode 31. The modifier answers false while {@link Engine#useClassicTransparency()}
 * holds; the game's own answer otherwise. Every caller in {@code LevelRenderer} reads the same answer for the frame.
 *
 * <p>Inert while no set is active: the state check leaves the game's answer alone. An engine mixin
 * ({@code McmsMetalMixinPlugin}). The modifier takes the game's answer as an argument (MixinExtras), so no
 * {@code CallbackInfoReturnable} is made per call.
 */
@Mixin(GameRenderer.class)
abstract class EngineLevelTransparencyMixin {

	@ModifyReturnValue(method = "useImprovedTransparency", at = @At("RETURN"))
	private boolean mcms$classicWhileActive(boolean improved) {
		return improved && !Engine.get().useClassicTransparency();
	}
}
