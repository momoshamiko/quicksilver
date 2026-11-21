/*
 * Quicksilver: original code (ADR 0006), Apache-2.0. The shader keys' polling (plan.md E8, "Keys").
 */
package dev.mcms.metal.mixin;

import dev.mcms.shaders.ui.ShaderKeys;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Polls the shader keys where the game polls its own bindings: {@code Minecraft.handleKeybinds()}. javap of
 * minecraft-merged-deobf-26.3.jar (2026-11-16): {@code tick()} calls it at bytecode 174 only when {@code gui.overlay()} and
 * {@code gui.screen()} are both null (bytecode 144-161), so the keys act in the world and never in a menu (the Controls screen
 * rebinding a key included). An engine mixin (McmsMetalMixinPlugin).
 */
@Mixin(Minecraft.class)
abstract class EngineKeybindsMixin {
	@Inject(method = "handleKeybinds", at = @At("HEAD"))
	private void mcms$engineShaderKeys(CallbackInfo ci) {
		ShaderKeys.handle((Minecraft) (Object) this);
	}
}
