/*
 * Quicksilver: original code (ADR 0006), Apache-2.0. The shader engine's frame hooks (plan.md E3, "Frame hooks").
 */
package dev.mcms.metal.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.mcms.shaders.runtime.Engine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The engine's two hooks in {@code GameRenderer.render()V} (javap against minecraft-merged-deobf-26.3.jar,
 * 2026-11-13):
 *
 * <ul>
 * <li>Frame start, injected before the method's first {@code RenderSystem.getDevice()} call at bytecode 61 — the join
 * right after the conditional {@code resize(II)V} call at bytecode 58, where the main render target holds its new
 * size. The hook runs every frame (a plain {@code resize} injection would fire on resize frames only, because the
 * game skips the call when the size did not change). Here the engine captures and updates the frame uniforms, reads
 * {@code mainRenderTarget.width/height} for its target sizes and takes a finished background load. Off the engine is
 * inert: one settings check, no allocation.</li>
 * <li>After the level, injected after the {@code renderLevel()V} call at bytecode 261 (world, hand and 3D HUD drawn),
 * before {@code tryTakeScreenshotIfNeeded()V} (265), {@code LevelRenderer.blitEntityOutline()V} (275) and
 * {@code applyPostEffects()V} (279), so the world icon shows the pack's picture and the game's post effects and the
 * GUI draw on top of the final pass's output. Here the engine runs the pack's full-screen passes on the backend's
 * frame encoder (EngineFrame). The call sits inside the {@code shouldRenderLevel} branch (bc 223 {@code ifeq 291}),
 * so no pass runs on menu frames.</li>
 * </ul>
 */
@Mixin(GameRenderer.class)
abstract class EngineGameRendererMixin {
	@Shadow
	@Final
	private net.minecraft.client.renderer.state.GameRenderState gameRenderState;

	@Shadow
	@Final
	private Minecraft minecraft;

	@Shadow
	@Final
	private RenderTarget mainRenderTarget;

	@Inject(method = "render()V", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/systems/RenderSystem;getDevice()Lcom/mojang/renderpearl/api/device/GpuDevice;",
		ordinal = 0))
	private void mcms$engineFrameStart(CallbackInfo ci) {
		float partialTick = 0f;
		// The frame's partial tick as the game itself reads it two calls later (GameRenderState.levelRenderState.
		// worldPartialTicks, public fields; javap 2026-11-13). Guarded: no level renders on menu frames.
		if (this.gameRenderState != null && this.gameRenderState.levelRenderState != null) {
			partialTick = this.gameRenderState.levelRenderState.worldPartialTicks;
		}
		Engine.get().frameStart(this.minecraft, this.mainRenderTarget, partialTick);
	}

	@Inject(method = "render()V", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V", shift = At.Shift.AFTER))
	private void mcms$engineAfterLevel(CallbackInfo ci) {
		Engine.get().afterLevel(this.mainRenderTarget);
	}
}
