// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import dev.mcms.shaders.runtime.Engine;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The engine's clear hook (plan.md E4, "Frame order": the frame graph's "clear" pass receives the pack targets' clears).
 * The game's "clear" pass is {@code LevelRenderer.lambda$render$0(Vector4f)V}: it clears the main colour and depth
 * ({@code clearColorAndDepthTextures}, bytecodes 40-45) and returns at bytecode 50, its only {@code return}. The hook
 * runs at that return, so the game's clear of the main target stays and the engine's targets are cleared and the
 * prepare passes run before the sky pass. Javap of minecraft-merged-deobf-26.3.jar, 2026-11-14: the method has one
 * {@code return} (bytecode 50) and its name is unique in {@code LevelRenderer}.
 *
 * <p>Inert while no set is active: {@link Engine#clearHook()} returns on its state check.
 * An engine mixin ({@code McmsMetalMixinPlugin}); Vitrail cannot load beside Quicksilver: fabric.mod.json breaks it (E10).
 */
@Mixin(LevelRenderer.class)
abstract class EngineLevelClearMixin {

	@Inject(method = "lambda$render$0", at = @At("RETURN"))
	private void mcms$engineClear(Vector4f fogColor, CallbackInfo ci) {
		Engine.get().clearHook();
	}
}
