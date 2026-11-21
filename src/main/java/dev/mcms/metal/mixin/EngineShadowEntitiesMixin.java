// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import dev.mcms.shaders.runtime.Engine;
import dev.mcms.shaders.runtime.shadow.ShadowEntities;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The shadow entity list's hook (plan.md E5, "Entities for the shadow"): the tail of
 * {@code LevelExtractor.extractVisibleEntities(Camera, Frustum, DeltaTracker, LevelRenderState)V}, a private void method
 * with one {@code return} (javap of minecraft-merged-deobf-26.3.jar, 2026-11-14, bytecode 331). The hook runs after the
 * camera's loop, so the view scale and the camera's per-entity resets are already done, and the second extraction reads
 * the same {@code DeltaTracker}. {@link ShadowEntities#extract} builds the engine's list; the game's list is untouched.
 *
 * <p>No invoker calls this method (the audit checks it), so the injection cannot recurse. Inert while no shadow pass is
 * attached: the hook returns before any work. A throw in the extraction would end the game's extraction of the frame; the
 * extraction is guarded, logs once and detaches the light's lists. An engine mixin ({@code McmsMetalMixinPlugin}).
 */
@Mixin(LevelExtractor.class)
abstract class EngineShadowEntitiesMixin {

	@Shadow
	private Minecraft minecraft;

	@Shadow
	private LevelRenderer levelRenderer;

	@Shadow
	private ClientLevel level;

	@Inject(method = "extractVisibleEntities", at = @At("RETURN"))
	private void mcms$shadowEntities(Camera camera, Frustum frustum, DeltaTracker deltaTracker,
			LevelRenderState state, CallbackInfo ci) {
		if (!ShadowEntities.get().attached()) {
			return;
		}
		try {
			ShadowEntities.get().extract(this.minecraft, this.levelRenderer.entityRenderDispatcher(), camera,
				deltaTracker, this.level);
		} catch (Throwable t) {
			Engine.shadowListFailed("the shadow entity list", t);
		}
	}
}
