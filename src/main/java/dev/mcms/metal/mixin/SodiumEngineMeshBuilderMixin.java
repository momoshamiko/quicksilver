// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import dev.mcms.shaders.runtime.terrain.TerrainContext;
import dev.mcms.shaders.runtime.terrain.TerrainEngine;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.builder.ChunkMeshBufferBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hook 5 of the engine's Sodium set (plan.md E4; engine-sodium-hooks.md table row 5): the single funnel of every
 * immediately-written quad. {@code push} enforces the four-vertex quad (bytecodes 0-15), then calls the encoder's
 * {@code write(buffer.address + vertexCount * stride, materialBits, quad, sectionIndex)} (bytecodes 21-57). The HEAD
 * hook fills the extension fields from the thread's block context before the engine's encoder reads them, and only
 * while a set is active. The {@code push(Vertex[], Material)} overload delegates here (bytecodes 0-9), so one injection
 * covers both.
 *
 * <p>Javap 2026-11-14, both jars ADR 0007 allows: the method is {@code push([Lnet/caffeinemc/mods/sodium/client/
 * render/chunk/vertex/format/ChunkVertexEncoder$Vertex;I)V} — the class is byte-identical between {@code g6ju16VJ}
 * and {@code v7Lw0h1m}.
 */
@Mixin(ChunkMeshBufferBuilder.class)
abstract class SodiumEngineMeshBuilderMixin {
	@Inject(method = "push([Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;I)V",
		at = @At("HEAD"))
	private void mcmsMetal$fillQuad(ChunkVertexEncoder.Vertex[] quad, int materialBits, CallbackInfo ci) {
		if (TerrainEngine.active()) {
			TerrainContext.fillQuad(quad);
		}
	}
}
