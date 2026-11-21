// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.mcms.shaders.runtime.Engine;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The shadow frame's hook (plan.md E5, "The pass"): runs the shadow pass before the level's {@code submitFeatures}, which
 * is the first thing the level does after its view-rotation push (javap, minecraft-merged-deobf-26.3.jar, 2026-11-14,
 * {@code LevelRenderer.render(GraphicsResourceAllocator, boolean, CameraRenderState, GpuBufferSlice, Vector4f, boolean,
 * boolean)V}):
 * <ul>
 * <li>bytecodes 45-55: the level's model-view push and {@code mul(viewRotationMatrix)}; the shadow pass does not use it;</li>
 * <li>bytecode 59-64: {@code profiler.popPush("submitFeatures")};</li>
 * <li>bytecode 69-79: {@code submitFeatures(levelRenderState, submitNodeStorage, boolean)}, the injection point (BEFORE).</li>
 * </ul>
 * The shadow pass therefore runs after {@code setupTerrain} (the light's lists are built by then) and before the world's
 * {@code PreparedFrame} and the hand's prepare (bytecode 770), and no game render pass is open at the point. The camera
 * argument is the level's own {@code CameraRenderState} (the entity submit's position).
 *
 * <p>Inert while no set is active: {@link Engine#shadowFrameHook} returns on its state check. An engine mixin
 * ({@code McmsMetalMixinPlugin}).
 */
@Mixin(LevelRenderer.class)
abstract class EngineShadowFrameMixin {

	@Inject(method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;submitFeatures(Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;Z)V",
			shift = At.Shift.BEFORE))
	private void mcms$shadowFrame(GraphicsResourceAllocator allocator, boolean a, CameraRenderState camera,
			GpuBufferSlice slice, Vector4f clearColor, boolean b, boolean c, CallbackInfo ci) {
		Engine.get().shadowFrameHook(camera);
	}
}
