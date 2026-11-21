// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import dev.mcms.shaders.runtime.terrain.TerrainEngine;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Registers the engine's Sodium terrain layout (E4-B review item 1). The layout's registration loads Sodium's terrain
 * vertex type and the renderpearl {@code VertexFormat} that Sodium's own {@code VertexFormatMixin} targets; doing that
 * inside the mixin plugin, while Mixin prepares the configs, fails the game at start. The plugin therefore only decides
 * whether this mixin applies (the ADR 0007 gate), and the registration runs here, on the render thread, at the head of
 * every game tick, so it is in place before any set can load.
 *
 * <p>{@code Minecraft.runTick(boolean)} is private and runs once per tick on the render thread (javap 2026-11-14, the
 * 26.3 jar). The registration is idempotent: the first call does the work, the rest return at once.
 */
@Mixin(Minecraft.class)
abstract class SodiumEngineStartMixin {
	@Inject(method = "runTick", at = @At("HEAD"))
	private void mcmsMetal$registerTerrainLayout(boolean tick, CallbackInfo ci) {
		TerrainEngine.registerLayout();
	}
}
