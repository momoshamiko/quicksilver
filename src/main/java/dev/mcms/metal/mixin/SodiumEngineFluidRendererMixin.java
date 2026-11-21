// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import dev.mcms.shaders.runtime.terrain.TerrainContext;
import dev.mcms.shaders.runtime.terrain.TerrainEngine;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.caffeinemc.mods.sodium.fabric.render.FluidRendererImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hook 4 of the engine's Sodium set (plan.md E4; engine-sodium-hooks.md table row 4): the fluid context, the same
 * channel as the block one. The hooks doc names the abstract {@code FluidRenderer.render}, which cannot hold an
 * injection; on Fabric the one concrete override the chunk builder calls is {@link FluidRendererImpl#render} (the
 * vanilla-shaped {@code DefaultFluidRenderer} runs inside it, and so do custom Fabric fluid handlers), so this is the
 * single entry every fluid quad passes.
 *
 * <p>The context is the fluid's own legacy block state ({@code FluidState.createLegacyBlock()}), so a fluid's quads take
 * the id and emission of the fluid, not of the block that holds it (water in kelp is {@code minecraft:water}). The
 * fluid's vertices get no model offset (Sodium applies none to them), which {@link TerrainContext#begin} enforces.
 * [inference] The documents do not state this rule; it follows the review's reading of what packs expect.
 *
 * <p>Javap 2026-11-14, both jars ADR 0007 allows: the method is {@code render(Lnet/caffeinemc/mods/sodium/client/
 * world/LevelSlice;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;
 * Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/caffeinemc/mods/sodium/client/render/chunk/
 * translucent_sorting/TranslucentGeometryCollector;Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/
 * ChunkBuildBuffers;)V} — the whole class is byte-identical between {@code g6ju16VJ} and {@code v7Lw0h1m}.
 */
@Mixin(FluidRendererImpl.class)
abstract class SodiumEngineFluidRendererMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void mcmsMetal$fluidContext(LevelSlice slice, BlockState state, FluidState fluidState, BlockPos pos,
		BlockPos origin, TranslucentGeometryCollector collector, ChunkBuildBuffers buffers, CallbackInfo ci) {
		if (TerrainEngine.active()) {
			TerrainContext.begin(fluidState.createLegacyBlock(), pos, origin, true);
		}
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void mcmsMetal$fluidContextEnd(LevelSlice slice, BlockState state, FluidState fluidState, BlockPos pos,
		BlockPos origin, TranslucentGeometryCollector collector, ChunkBuildBuffers buffers, CallbackInfo ci) {
		TerrainContext.end();
	}
}
