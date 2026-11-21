// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import dev.mcms.shaders.runtime.Engine;
import dev.mcms.shaders.runtime.terrain.TerrainEngine;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Camera;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The engine's terrain latch (plan.md E4; engine-sodium-hooks.md section 2): {@code setupTerrain} runs on the render
 * thread every frame before the terrain layers draw, so its HEAD is where the latched answer of
 * {@code ChunkMeshFormats.getCurrent} follows the engine's state and the active set. On a change the latch flips
 * first and the world reloads its meshes ({@code reload} builds a fresh render-section manager, fresh regions and
 * arenas and re-meshes every section with the format {@code getCurrent} answers then); until the rebuild finishes,
 * terrain is simply missing, the same picture as a render-distance change (ADR 0006 section 3 accepts a mesh rebuild
 * at engine on/off).
 *
 * <p>The frame path allocates nothing here: the renderer is passed as {@code this}, and the set is read from the
 * engine. The latch also reacts to a different set while the engine stays active (a pack or option change).
 *
 * <p>Javap 2026-11-14, both jars ADR 0007 allows: the method is {@code setupTerrain(Lnet/minecraft/client/Camera;
 * Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;Lnet/caffeinemc/mods/sodium/client/util/FogParameters;
 * ZZLorg/joml/Matrix4f;)V}; its own {@code reload()} call when the render distance changed (bytecodes 28-41 in
 * beta.1) is the precedent that reloading here is safe. The member exists identically in both jars (the setupTerrain
 * bytecode differs only past the parts this hook needs, and the injection is at HEAD).
 */
@Mixin(SodiumWorldRenderer.class)
abstract class SodiumEngineTerrainLatchMixin {
	@Inject(method = "setupTerrain", at = @At("HEAD"))
	private void mcmsMetal$latchTerrainFormat(Camera camera, Viewport viewport, FogParameters fog,
		boolean useOcclusionCulling, boolean renderDistanceChanged, Matrix4f positionMatrix, CallbackInfo ci) {
		Engine engine = Engine.get();
		TerrainEngine.frameCheck(engine.state() == Engine.State.ACTIVE, engine.activeSet(),
			(SodiumWorldRenderer) (Object) this);
	}
}
