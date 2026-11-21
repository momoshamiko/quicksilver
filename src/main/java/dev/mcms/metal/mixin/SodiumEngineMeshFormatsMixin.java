// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import dev.mcms.shaders.runtime.terrain.TerrainEngine;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hook 1 of the engine's Sodium set (plan.md E4; engine-sodium-hooks.md table row 1): while a set is active,
 * {@code getCurrent} answers the engine's extended {@code ChunkVertexType} instead of {@code COMPACT}, which widens
 * the whole pipeline in one place — the stride of every {@code ChunkMeshBufferBuilder}, the worker-side encoder and
 * the vertex binding of the terrain pipelines all come from the type's format.
 *
 * <p>Javap 2026-11-14, both jars ADR 0007 allows (0.9.3-beta.1 {@code g6ju16VJ}, 0.9.3-beta.5 {@code v7Lw0h1m}):
 * the method is {@code getCurrent()Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexType;}
 * and reads the {@code COMPACT} static field (beta.1 bytecode 0-3, identical in beta.5; the class is byte-identical
 * between the jars). Its four call sites, identical in both jars: {@code RenderSectionManager.<init>} bytecode 170
 * ({@code new DefaultChunkRenderer}) and 190 ({@code new ChunkBuilder}), {@code gpu.arena.ArenaAggregator}'s
 * constructor bytecode 41, and the lazily created {@code RenderRegion$DeviceResources.<init>} bytecode 4. The answer
 * is latched in {@link TerrainEngine}: it flips only together with {@code SodiumWorldRenderer.reload()}, which the
 * engine's setupTerrain hook calls, so every reader that matters sees one format per mesh generation.
 */
@Mixin(ChunkMeshFormats.class)
abstract class SodiumEngineMeshFormatsMixin {
	@Inject(method = "getCurrent", at = @At("RETURN"), cancellable = true)
	private static void mcmsMetal$extendedTerrainFormat(CallbackInfoReturnable<ChunkVertexType> cir) {
		if (TerrainEngine.active()) {
			cir.setReturnValue(TerrainEngine.terrainType());
		}
	}
}
