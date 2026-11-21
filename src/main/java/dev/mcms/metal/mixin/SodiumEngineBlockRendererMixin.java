// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import dev.mcms.shaders.runtime.terrain.TerrainContext;
import dev.mcms.shaders.runtime.terrain.TerrainEngine;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hook 3 of the engine's Sodium set (plan.md E4; engine-sodium-hooks.md table row 3): the block context of the mesh
 * builder's worker threads. {@code renderModel} is the one place every block quad knows its block: HEAD stores the
 * state, the position and the in-section position (the fourth argument, {@code x & 15} on each axis, bytecode 298-327
 * of {@code ChunkBuilderMeshingTask.execute}), and the model emitter then runs the quads synchronously into the two
 * sinks; TAIL clears the context. Fabric API baked models are {@code BlockStateModelPart}s and flow through this same
 * call, so no separate path exists to hook. Both hooks run only while a set is active ({@link TerrainEngine#active}):
 * with no set the engine does no work here.
 *
 * <p>Javap 2026-11-14, both jars ADR 0007 allows: the method is {@code renderModel(Lnet/minecraft/client/renderer/
 * block/dispatch/BlockStateModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;
 * Lnet/minecraft/core/BlockPos;)V} — byte-identical between {@code g6ju16VJ} and {@code v7Lw0h1m} (the classes
 * differ only in constant-pool indices and {@code MutableQuadViewImpl} accessor names of methods this hook does not
 * touch). Injection at HEAD and TAIL needs no ordinal.
 */
@Mixin(BlockRenderer.class)
abstract class SodiumEngineBlockRendererMixin {
	@Inject(method = "renderModel", at = @At("HEAD"))
	private void mcmsMetal$blockContext(BlockStateModel model, BlockState state, BlockPos pos, BlockPos origin,
		CallbackInfo ci) {
		if (TerrainEngine.active()) {
			TerrainContext.begin(state, pos, origin, false);
		}
	}

	@Inject(method = "renderModel", at = @At("TAIL"))
	private void mcmsMetal$blockContextEnd(BlockStateModel model, BlockState state, BlockPos pos, BlockPos origin,
		CallbackInfo ci) {
		if (TerrainEngine.active()) {
			TerrainContext.end();
		}
	}
}
