// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import dev.mcms.shaders.runtime.Engine;
import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips the game's vanilla cloud draws while the engine serves a set whose shaders.properties says {@code clouds=off}
 * (plan E6, item 2). Before this mixin the game drew its clouds every frame through the pack's {@code gbuffers_clouds}, which
 * discarded every fragment, so the draws cost time and drew nothing.
 *
 * <p>javap of minecraft-merged-deobf-26.3.jar (2026-11-15): {@code CloudRenderer.render(CloudStatus, RenderPass)} and
 * {@code CloudRenderer.renderOit(CloudStatus, OitStage, GpuTextureView, Parameters)} are the two draw entries; each returns
 * {@code void}. The mixin stops both at HEAD. The mesh build in {@code prepare} is not skipped, so the cost of a frame's
 * cloud mesh stays (a later frame of a set without {@code clouds=off} draws it).
 *
 * <p>Inert while no set is active or the active set does not say {@code clouds=off}: {@link Engine#skipsVanillaClouds()} is
 * the decision. An engine mixin ({@code McmsMetalMixinPlugin}); Vitrail cannot load beside Quicksilver: fabric.mod.json
 * breaks it (E10).
 */
@Mixin(CloudRenderer.class)
abstract class EngineCloudRendererMixin {

	@Inject(method = "render(Lnet/minecraft/client/CloudStatus;Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
		at = @At("HEAD"), cancellable = true)
	private void mcms$skipWhileCloudsOff(CallbackInfo ci) {
		if (Engine.get().skipsVanillaClouds()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderOit", at = @At("HEAD"), cancellable = true)
	private void mcms$skipOitWhileCloudsOff(CallbackInfo ci) {
		if (Engine.get().skipsVanillaClouds()) {
			ci.cancel();
		}
	}
}
