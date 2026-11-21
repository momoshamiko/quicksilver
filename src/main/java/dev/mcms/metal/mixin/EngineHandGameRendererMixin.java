// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.mcms.shaders.runtime.Engine;
import dev.mcms.shaders.runtime.blockentity.BlockEntityRouter;
import dev.mcms.shaders.runtime.entities.EntityCapture;
import dev.mcms.shaders.runtime.hand.HandRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The hand's two hooks in {@code GameRenderer} (plan.md E4, "Hand"; javap of minecraft-merged-deobf-26.3.jar,
 * 2026-11-14):
 *
 * <ul>
 * <li>The engine staged buffer's {@code endFrame()}, injected after the game's
 * {@code RenderBuffers.endFrame()V} call in {@code render()V} at bytecode 379 (the method's only such call, ordinal
 * 0). The engine calls it beside the game's every frame while its buffer exists: only there do the pool's used GPU
 * buffers become available again, so without it each prepare would create new buffers. Off the engine: one check,
 * no work.</li>
 * <li>The hand redirect: while a set is active, the {@code renderItemInHand(CameraRenderState, PlayerRenderState,
 * GpuTextureView)V} call of {@code render3dHud} at bytecode 141 (the method's only call to it, ordinal 0) is skipped
 * — the game's one dispatcher holds the world's {@code PreparedFrame} open, and its model-view push would apply the
 * view rotation twice inside the level — and the engine draws the prepared hand frame's translucent phases in a
 * redirected engine pass in its place, before the screen effects (the submits at bytecodes 154-184). While no set is
 * active the original call runs.</li>
 * </ul>
 */
@Mixin(GameRenderer.class)
abstract class EngineHandGameRendererMixin {
	@Shadow
	@Final
	private com.mojang.blaze3d.pipeline.RenderTarget mainRenderTarget;

	@Inject(method = "render()V", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/RenderBuffers;endFrame()V", ordinal = 0, shift = At.Shift.AFTER))
	private void mcms$handBufferEndFrame(CallbackInfo ci) {
		if (HandRenderer.get().bufferExists()) {
			// Engine off, a pack switch, a resource reload, or a takeover that serves no world (the vanilla hand then)
			// stops the hand: the dispatcher and its buffer close (plan.md E4), and the next drawing frame makes a new
			// one.
			if (!Engine.get().drawsHand()) {
				HandRenderer.get().close();
			} else {
				HandRenderer.get().onBufferEndFrame();
			}
		}
		// The block-entity split's staged buffer beside it (the E4 routing rules): the same end frame — its pool's
		// buffers recycle only there — and the same stop when the takeover no longer serves a world.
		if (BlockEntityRouter.get().bufferExists()) {
			if (!Engine.get().drawsBlockEntities()) {
				BlockEntityRouter.get().close();
			} else {
				BlockEntityRouter.get().onBufferEndFrame();
			}
		}
		// Reset global per-frame entity/item ID capture once at the real end of frame, never inside an arbitrary
		// modded StagedVertexBuffer.endFrame() call mid-frame.
		EntityCapture.frameEnded();
	}

	/** A hand frame the translucent draw never ran (guards, early exits) closes here, beside E3's after-level hook
	 * (after the {@code renderLevel()V} call of {@code render()V}, bytecode 261), so it never stays open into the
	 * next frame (plan.md E4, "Hand"). */
	@Inject(method = "render()V", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V", shift = At.Shift.AFTER))
	private void mcms$handAfterLevel(CallbackInfo ci) {
		HandRenderer.get().afterLevel();
		// The block-entity frame closes beside it: a frame the phase draws never ran must not stay open (the split).
		BlockEntityRouter.get().afterLevel();
	}

	@WrapOperation(method = "render3dHud", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V",
		ordinal = 0))
	private void mcms$engineHand(GameRenderer self, CameraRenderState camera, PlayerRenderState player,
			GpuTextureView depthView, Operation<Void> original) {
		if (!Engine.get().drawsHand()) {
			original.call(self, camera, player, depthView);
			return;
		}
		// The frame was prepared at LevelRenderer.render bytecode 770; this draw closes it, and the after-level
		// hook catches the guards' early exits. The depth view is the game's own choice for this frame
		// (render3dHud bytecodes 96-118: hud3DTarget's when post effects are active, the main target's otherwise).
		HandRenderer.get().drawTranslucentHand(this.mainRenderTarget.getColorTextureView(), depthView);
	}
}
