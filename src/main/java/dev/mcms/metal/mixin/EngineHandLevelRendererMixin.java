// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.mcms.shaders.runtime.hand.HandRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The hand's submit hook (plan.md E4, "Hand", submit bullet): before {@code FrameGraphBuilder.execute} in
 * {@code LevelRenderer.render} — the call at bytecode 770
 * ({@code execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V},
 * the method's only call to it, ordinal 0; javap of minecraft-merged-deobf-26.3.jar, 2026-11-14). Here the engine
 * submits the hands and items and prepares its own frame, so the draws are ready when the phase hook (the solid
 * hand) and {@code render3dHud} (the translucent hand) draw them.
 *
 * <p>The hook sits before the frame graph runs and inside the level's model-view push (bytecodes 45-55; the stack
 * holds {@code viewRotationMatrix} until 789), so {@code RenderType.prepare} copies the level's model-view, exactly
 * what {@code renderItemInHand} would push — the engine's pose starts at {@code inverse(viewRotationMatrix)} instead.
 * The world's dispatcher holds its own {@code PreparedFrame} open from bytecode 100 to 795, which is why the engine
 * has its own dispatcher (plan.md E4). The shadow frame (E5) has its own dispatchers too, so it does not share this one. Off the
 * engine: one state check, no work.
 */
@Mixin(LevelRenderer.class)
abstract class EngineHandLevelRendererMixin {
	@Shadow
	@Final
	private GameRenderer gameRenderer;

	@Inject(method = "render", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V",
		ordinal = 0))
	private void mcms$handSubmitAndPrepare(GraphicsResourceAllocator allocator, boolean advanced,
			CameraRenderState camera, GpuBufferSlice fogBuffer, Vector4f clearColor, boolean fog, boolean sky,
			CallbackInfo ci) {
		HandRenderer.get().submitAndPrepare(Minecraft.getInstance(), this.gameRenderer, camera);
	}
}
