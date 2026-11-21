// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder.Vertex;
import net.caffeinemc.mods.sodium.client.render.texture.SpriteFinderCache;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The worker-thread block context of the terrain extension (plan.md E4; engine-sodium-hooks.md section 1, row 3): the
 * mesh builder encodes quads on worker threads, and the two render entry points that know the block set the context
 * through the engine's mixins: {@code BlockRenderer.renderModel} (every block quad, Fabric API baked models included,
 * which are the same call) and the fluid renderer's {@code render} (the Fabric implementation is the one concrete
 * override of the abstract {@code FluidRenderer.render}). The sinks ({@code ChunkMeshBufferBuilder.push} and
 * {@code TranslucentGeometryCollector.appendQuad}) fire inside those render calls, on the same thread.
 *
 * <p>The context is one reused object per thread ({@link ThreadLocal#withInitial}): {@link #begin} overwrites its
 * fields and {@link #end} clears its flag, so a block allocates nothing. The mixins call {@code begin} and {@code end}
 * only while a set is active ({@link TerrainEngine#active()}), and the sinks do the same, so the engine is inert while
 * no set is active.
 *
 * <p>The position arguments are Sodium's: the fourth argument of {@code renderModel} and the fifth of the fluid render
 * are the block's position <em>inside its section</em> (0 to 15 on each axis; bytecode 298-327 of
 * {@code ChunkBuilderMeshingTask.execute}, both jars), and the vertices are section-local. The block centre is therefore
 * {@code inSection + 0.5}, plus the model offset for blocks that have one. Fluids take no model offset: Sodium applies
 * none to fluid vertices.
 */
public final class TerrainContext {
	private static final ThreadLocal<TerrainContext> CURRENT = ThreadLocal.withInitial(TerrainContext::new);

	private boolean active;
	private float centreX, centreY, centreZ;
	private int blockId = -1;
	private int emission;
	private boolean fluid;

	private TerrainContext() {
	}

	/**
	 * The block centre along one axis, in section-local units: the block's in-section coordinate plus half a block, plus
	 * the model offset (blocks only). The fluid case passes {@code offset 0} and keeps the same centre.
	 */
	static float centre(int inSection, double offset) {
		return inSection + 0.5f + (float) offset;
	}

	/**
	 * Starts the context of one block or fluid. {@code state} is the block whose id and emission the quads take: the
	 * block itself, or for a fluid the fluid's own legacy block state ({@code FluidState.createLegacyBlock()}), so water
	 * inside kelp or a waterlogged block is {@code minecraft:water}.
	 *
	 * @param inSection the block's position inside its section (Sodium's fourth or fifth render argument)
	 */
	public static void begin(BlockState state, BlockPos pos, BlockPos inSection, boolean fluid) {
		TerrainContext c = CURRENT.get();
		c.active = true;
		c.blockId = TerrainEngine.blockIdOf(state);
		c.emission = state.getLightEmission();
		c.fluid = fluid;
		Vec3 offset = !fluid && state.hasOffsetFunction() ? state.getOffset(pos) : Vec3.ZERO;
		c.centreX = centre(inSection.getX(), offset.x);
		c.centreY = centre(inSection.getY(), offset.y);
		c.centreZ = centre(inSection.getZ(), offset.z);
	}

	/** Clears the context of the current thread (the mixins' TAIL, while a set is active). */
	public static void end() {
		CURRENT.get().active = false;
	}

	/**
	 * Writes the context's values into the quad's extension fields. The quad's sprite centre is the centre of the atlas
	 * sprite that Sodium's sprite finder reports for the quad's mean UV.
	 */
	public static void fillQuad(Vertex[] quad) {
		TerrainContext c = CURRENT.get();
		if (!c.active) {
			return;
		}
		float meanU = 0.0f, meanV = 0.0f;
		for (Vertex v : quad) {
			meanU += v.u;
			meanV += v.v;
		}
		meanU *= 0.25f;
		meanV *= 0.25f;
		float midU = meanU, midV = meanV;
		TextureAtlasSprite sprite = SpriteFinderCache.forBlockAtlas().find(meanU, meanV);
		if (sprite != null) {
			midU = (sprite.getU0() + sprite.getU1()) * 0.5f;
			midV = (sprite.getV0() + sprite.getV1()) * 0.5f;
		}
		for (Vertex v : quad) {
			if (v instanceof TerrainVertex t) {
				t.blockId = c.blockId;
				t.fluid = c.fluid;
				t.emission = c.emission;
				t.centreX = c.centreX;
				t.centreY = c.centreY;
				t.centreZ = c.centreZ;
				t.midU = midU;
				t.midV = midV;
			}
		}
	}
}
