// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder.Vertex;

/**
 * The engine's vertex of Sodium's terrain quads (plan.md E4, ADR 0006): Sodium's {@link Vertex} plus the attributes a
 * shader pack's {@code gbuffers_terrain} program reads (engine-pack-format.md section 11). Every quad array Sodium
 * builds comes from {@code Vertex.uninitializedQuad()}, and the engine's mixin makes it hand out this subclass, so
 * {@code BlockRenderer}, {@code DefaultFluidRenderer} and {@code ChunkVertexConsumer} all carry the extension without
 * a second attribute stream (engine-sodium-hooks.md section 1: the buffer holds one interleaved stream).
 *
 * <p>The base fields stay Sodium's and are written by Sodium's code as before. The extension fields are filled by
 * {@link TerrainContext#fillQuad} at the two quad sinks ({@code ChunkMeshBufferBuilder.push} and
 * {@code TranslucentGeometryCollector.appendQuad}) from the worker-thread block context. The extension stores the
 * block centre, not the offset from the vertex to it: a split quad moves its vertices, and the encoder computes the
 * offset from the centre and the vertex it writes ({@link TerrainEncoder}). {@link #copyExtension} carries the
 * extension through {@code Vertex.copyVertexTo}, so a translucent quad that Sodium splits keeps its attributes when
 * the collector re-emits it later through {@code writeExternal} (engine-sodium-hooks.md errata).
 */
public final class TerrainVertex extends Vertex {
	/** The id from the pack's {@code block.properties}, or -1 when the pack lists the block nowhere. */
	public int blockId = -1;
	/** Whether the quad belongs to a fluid ({@code mc_Entity.y} 1.0; -1.0 for every other block). */
	public boolean fluid;
	/** The block's light emission, 0 to 15 ({@code at_midBlock.w}, engine-pack-format.md section 11). */
	public int emission;
	/** The block centre in section-local coordinates, the space of the vertex position. */
	public float centreX, centreY, centreZ;
	/** The sprite's centre in the block atlas ({@code mc_midTexCoord}). */
	public float midU, midV;

	/**
	 * Copies the extension of {@code src} onto {@code dst} (the TAIL hook of {@code Vertex.copyVertexTo}). Nothing
	 * happens unless both are engine vertices.
	 */
	public static void copyExtension(Vertex src, Vertex dst) {
		if (src instanceof TerrainVertex s && dst instanceof TerrainVertex t) {
			t.blockId = s.blockId;
			t.fluid = s.fluid;
			t.emission = s.emission;
			t.centreX = s.centreX;
			t.centreY = s.centreY;
			t.centreZ = s.centreZ;
			t.midU = s.midU;
			t.midV = s.midV;
		}
	}
}
