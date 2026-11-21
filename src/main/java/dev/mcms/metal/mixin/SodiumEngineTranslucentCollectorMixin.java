// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import dev.mcms.shaders.runtime.terrain.TerrainContext;
import dev.mcms.shaders.runtime.terrain.TerrainEngine;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

/**
 * Hook 6 of the engine's Sodium set (plan.md E4; engine-sodium-hooks.md table row 6): translucent quads that the
 * collector accepts bypass {@code push} — the collector keeps its own quads ({@code FullTQuad.fromVertices} at
 * bytecode 10 when splitting, {@code RegularTQuad.fromVertices} at 20 otherwise) and re-emits them later on another
 * worker pass, when the block context is gone. The HEAD hook fills the extension fields here, while the vertices are
 * the factory's engine subclass and the context is alive, and only while a set is active; the collector's later copies
 * go through {@code Vertex.copyVertexTo}, whose TAIL hook carries the extension (the centre, so the encoder computes
 * each copy's offset from its own position).
 *
 * <p>Javap 2026-11-14, both jars ADR 0007 allows: the method is {@code appendQuad([Lnet/caffeinemc/mods/sodium/
 * client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;Lnet/caffeinemc/mods/sodium/client/model/quad/
 * properties/ModelQuadFacing;I)Z} — the class is byte-identical between {@code g6ju16VJ} and {@code v7Lw0h1m}.
 */
@Mixin(TranslucentGeometryCollector.class)
abstract class SodiumEngineTranslucentCollectorMixin {
	@Inject(method = "appendQuad", at = @At("HEAD"))
	private void mcmsMetal$fillTranslucentQuad(ChunkVertexEncoder.Vertex[] quad, ModelQuadFacing facing, int faceNormal,
		CallbackInfoReturnable<Boolean> ci) {
		if (TerrainEngine.active()) {
			TerrainContext.fillQuad(quad);
		}
	}
}
