// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.mcms.shaders.runtime.terrain.TerrainEngine;
import dev.mcms.shaders.runtime.terrain.TerrainVertex;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hook 2 of the engine's Sodium set (plan.md E4; engine-sodium-hooks.md table row 2) and the quad-splitting copy: while a
 * set is active, the factory {@code uninitializedQuad()} hands out the engine's {@link TerrainVertex} subclass, which
 * carries the pack attributes next to Sodium's fields. Every quad array of {@code BlockRenderer}, {@code
 * DefaultFluidRenderer}, {@code ChunkVertexConsumer} and the translucent collector comes from this one factory, so no
 * producer needs its own hook. With no set active the factory returns Sodium's own quads and allocates nothing extra.
 *
 * <p>{@code copyVertexTo(Vertex, Vertex)} is static and copies the base fields only, so translucent quads that Sodium
 * splits would lose the extension when the collector re-emits them later through
 * {@code ChunkMeshBufferBuilder.writeExternal}; the TAIL hook copies the extension after the base copy.
 *
 * <p>Javap 2026-11-14, both jars ADR 0007 allows: {@code uninitializedQuad()[Lnet/caffeinemc/mods/sodium/client/
 * render/chunk/vertex/format/ChunkVertexEncoder$Vertex;} (a four-element array, {@code new Vertex()} at bytecode 14)
 * and {@code copyVertexTo(Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;
 * Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;)V} (static, base fields at
 * bytecodes 0-64) are byte-identical between {@code g6ju16VJ} and {@code v7Lw0h1m}.
 */
@Mixin(ChunkVertexEncoder.Vertex.class)
abstract class SodiumEngineVertexMixin {
	@ModifyReturnValue(method = "uninitializedQuad", at = @At("RETURN"))
	private static ChunkVertexEncoder.Vertex[] mcmsMetal$engineQuads(ChunkVertexEncoder.Vertex[] original) {
		if (!TerrainEngine.active()) {
			return original;
		}
		ChunkVertexEncoder.Vertex[] quads = new TerrainVertex[4];
		for (int i = 0; i < 4; i++) {
			quads[i] = new TerrainVertex();
		}
		return quads;
	}

	@Inject(method = "copyVertexTo", at = @At("TAIL"))
	private static void mcmsMetal$copyExtension(ChunkVertexEncoder.Vertex src, ChunkVertexEncoder.Vertex dst,
		CallbackInfo ci) {
		if (TerrainEngine.active()) {
			TerrainVertex.copyExtension(src, dst);
		}
	}
}
