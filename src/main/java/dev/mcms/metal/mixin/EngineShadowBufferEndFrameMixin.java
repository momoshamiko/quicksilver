// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import dev.mcms.shaders.runtime.shadow.ShadowEntityDispatch;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The shadow entity dispatch's staged buffers end their frame beside the game's {@code RenderBuffers.endFrame()V} call in
 * {@code GameRenderer.render()V} (bytecode 379, the method's only such call, ordinal 0; javap of minecraft-merged-deobf-26.3.jar,
 * 2026-11-14), the pattern the hand's staged buffer uses ({@code EngineHandGameRendererMixin}). Only there do the pools' used GPU
 * buffers become available again. Inert with no set active: {@link ShadowEntityDispatch#endFrameOfActive} is one null check.
 */
@Mixin(GameRenderer.class)
abstract class EngineShadowBufferEndFrameMixin {

	@Inject(method = "render()V", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/RenderBuffers;endFrame()V", ordinal = 0, shift = At.Shift.AFTER))
	private void mcms$shadowBufferEndFrame(CallbackInfo ci) {
		ShadowEntityDispatch.endFrameOfActive();
	}
}
