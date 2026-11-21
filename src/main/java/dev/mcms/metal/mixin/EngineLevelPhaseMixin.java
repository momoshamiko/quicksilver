// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.mcms.shaders.runtime.Engine;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The engine's phase hooks (plan.md E4, "Phases"), both in {@code LevelRenderer.lambda$addMainPass$0}, the level's
 * "Solid" main pass (javap of minecraft-merged-deobf-26.3.jar, 2026-11-14; the lambda calls each method once).
 *
 * <ul>
 * <li>Before the pass opens: {@code invokeinterface CommandEncoder.createRenderPass} at bytecode 156 (the lambda's only
 * call of it, with the main colour view and the depth view). No game render pass is open there, so the sky-to-opaque
 * copies are plain blits ({@link Engine#opaqueHook}).</li>
 * <li>After {@code executeSolid}: {@code invokevirtual executeSolid(ChunkSectionsToRender, PreparedFrame, RenderPass)V}
 * at bytecode 174, the only call of that method in the lambda; {@code executeClassicTransparency} follows at bytecode
 * 187, which the phase hook precedes. The hook runs after bytecode 174 with the open game pass, which is the local
 * {@code renderPass} the lambda stores at bytecode 163 (its local variable table range 163-233, slot 8), taken with
 * {@code @Local}.</li>
 * </ul>
 *
 * <p>Inert while no set is active or while no world is drawn by the takeover: {@link Engine#opaqueHook} and
 * {@link Engine#phaseHook} return on their state checks. An engine mixin ({@code McmsMetalMixinPlugin}); Vitrail cannot load
 * beside Quicksilver: fabric.mod.json breaks it (E10).
 */
@Mixin(LevelRenderer.class)
abstract class EngineLevelPhaseMixin {

	@Inject(method = "lambda$addMainPass$0", at = @At(value = "INVOKE",
		target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/renderpearl/api/commands/RenderPass;",
		shift = At.Shift.BEFORE))
	private void mcms$engineOpaqueBegin(CallbackInfo ci) {
		Engine.get().opaqueHook();
	}

	@Inject(method = "lambda$addMainPass$0", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/LevelRenderer;executeSolid(Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
		shift = At.Shift.AFTER))
	private void mcms$enginePhase(com.mojang.renderpearl.api.buffers.GpuBufferSlice fogBuffer, boolean advanced,
			net.minecraft.client.renderer.chunk.ChunkSectionsToRender sections,
			net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame preparedFrame,
			boolean renderOutlines, boolean oit, CallbackInfo ci, @Local(type = RenderPass.class) RenderPass renderPass) {
		// The world's prepared frame parameter names the frame the block-entity split draws beside (its identity gates
		// the world-phase draws of EnginePreparedFrameMixin).
		Engine.get().phaseHook(renderPass, preparedFrame);
	}
}
