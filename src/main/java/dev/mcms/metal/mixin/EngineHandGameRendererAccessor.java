// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The hand's private {@code GameRenderer} members (plan.md E4, "Hand"; javap of minecraft-merged-deobf-26.3.jar,
 * 2026-11-14):
 *
 * <ul>
 * <li>{@code private final SubmitNodeStorage handAndScreenSubmitNodeStorage} (field, put at constructor bytecode 95):
 * the storage the engine submits the hands into at {@code LevelRenderer.render} bytecode 770 and prepares from
 * ({@code FeatureRenderDispatcher.prepareFrame} at {@code renderItemInHand} bytecode 183).</li>
 * <li>{@code private void bobHurt(CameraRenderState, PoseStack)} (descriptor
 * {@code (Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V},
 * called at {@code renderItemInHand} bytecode 126) and {@code private void bobView(CameraRenderState, PoseStack)}
 * (called at bytecode 146, under the {@code OptionsRenderState.bobView} check at 136): the hand pose's hurt and view
 * bobbing, applied to the engine's pose stack in the game's order.</li>
 * <li>{@code private void renderItemInHand(CameraRenderState, PlayerRenderState, GpuTextureView)} (the
 * {@code render3dHud} call at bytecode 141): the redirect fallback while no set is active.</li>
 * </ul>
 */
@Mixin(GameRenderer.class)
public interface EngineHandGameRendererAccessor {
	@Accessor("handAndScreenSubmitNodeStorage")
	SubmitNodeStorage mcms$handStorage();

	@Invoker("bobHurt")
	void mcms$bobHurt(CameraRenderState camera, PoseStack poseStack);

	@Invoker("bobView")
	void mcms$bobView(CameraRenderState camera, PoseStack poseStack);

	@Invoker("renderItemInHand")
	void mcms$renderItemInHand(CameraRenderState camera, PlayerRenderState player, GpuTextureView depthView);
}
