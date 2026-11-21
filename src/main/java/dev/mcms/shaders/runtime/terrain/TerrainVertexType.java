// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;

/**
 * The engine's terrain vertex type (plan.md E4 "Sodium terrain"): Sodium's 20-byte compact vertex, bit-identical at
 * its offsets so Sodium's code that reads it keeps working, widened by 24 bytes of shader-pack attributes. One
 * interleaved stream, stride 44 (engine-sodium-hooks.md section 1: there is no second attribute stream).
 *
 * <table border="1"><caption>the layout</caption>
 * <tr><th>offset</th><th>attribute</th><th>format</th><th>carries</th></tr>
 * <tr><td>0</td><td>{@code a_Position}</td><td>RG32_UINT</td><td>Sodium's packed 20-bit section-local position</td></tr>
 * <tr><td>8</td><td>{@code a_Color}</td><td>RGBA8_UNORM</td><td>Sodium's AO-tinted colour</td></tr>
 * <tr><td>12</td><td>{@code a_TexCoord}</td><td>RG16_UINT</td><td>Sodium's UV quantised around the quad mean, sign bit bias</td></tr>
 * <tr><td>16</td><td>{@code a_LightAndData}</td><td>RGBA8_UINT</td><td>Sodium's light (biased), material bits, section index</td></tr>
 * <tr><td>20</td><td>{@code a_Entity}</td><td>RG16_UINT</td><td>x = block.properties id + 1 (0 = listed nowhere), y = 1 for a fluid block (mc_Entity.y), else 0</td></tr>
 * <tr><td>24</td><td>{@code a_Tangent}</td><td>RGBA8_SNORM</td><td>xyz tangent (1/127 steps), w handedness (+1/-1): {@code at_tangent}</td></tr>
 * <tr><td>28</td><td>{@code a_Normal}</td><td>RGBA8_SNORM</td><td>xyz unit normal (1/127 steps), w unused: {@code gl_Normal}/{@code vaNormal}</td></tr>
 * <tr><td>32</td><td>{@code a_MidBlock}</td><td>RGBA8_SNORM</td><td>xyz offset to the block centre in 1/64 m (decoded x127), w emission (decoded x127): {@code at_midBlock}</td></tr>
 * <tr><td>36</td><td>{@code a_MidTexCoord}</td><td>RG32_FLOAT</td><td>the sprite's centre in the block atlas: {@code mc_midTexCoord}</td></tr>
 * </table>
 *
 * <p>The first four attributes are Sodium's own names and formats (CompactChunkVertex's static init, javap 2026-11-14
 * on both jars ADR 0007 allows): {@code ChunkMeshBufferBuilder} takes its stride from {@link #getVertexFormat()}, and
 * {@code ShaderChunkRenderer.createShader} binds vertex buffer 0 to it, so the widened format flows everywhere the
 * swapped {@link ChunkVertexType} answer reaches. {@code a_Entity.x} stores id + 1 so the {@code ushort} range covers
 * ids -1 to 65534 (packs' substitute ids are small; OptiFine's doc: "blockId is used only for blocks specified in
 * block.properties", and the engine gives unlisted blocks -1).
 */
public final class TerrainVertexType implements ChunkVertexType {
	/** The stride of the whole interleaved vertex. */
	public static final int STRIDE = 44;
	/** The offset where Sodium's 20 bytes end and the engine's extension begins. */
	public static final int EXTENSION_OFFSET = 20;
	/** The attribute offsets, for the layout registration and the tests. */
	public static final int A_POSITION = 0, A_COLOR = 8, A_TEXCOORD = 12, A_LIGHT_AND_DATA = 16;
	/** The extension attribute offsets. */
	public static final int A_ENTITY = 20, A_TANGENT = 24, A_NORMAL = 28, A_MID_BLOCK = 32, A_MID_TEXCOORD = 36;

	private static final VertexFormat FORMAT = VertexFormat.builder(0)
		.addAttribute("a_Position", GpuFormat.RG32_UINT)
		.addAttribute("a_Color", GpuFormat.RGBA8_UNORM)
		.addAttribute("a_TexCoord", GpuFormat.RG16_UINT)
		.addAttribute("a_LightAndData", GpuFormat.RGBA8_UINT)
		.addAttribute("a_Entity", GpuFormat.RG16_UINT)
		.addAttribute("a_Tangent", GpuFormat.RGBA8_SNORM)
		.addAttribute("a_Normal", GpuFormat.RGBA8_SNORM)
		.addAttribute("a_MidBlock", GpuFormat.RGBA8_SNORM)
		.addAttribute("a_MidTexCoord", GpuFormat.RG32_FLOAT)
		.build();

	private final ChunkVertexEncoder encoder = new TerrainEncoder();

	/** The one instance; {@link TerrainEngine} hands it to Sodium through the {@code getCurrent} mixin. */
	public static final TerrainVertexType INSTANCE = new TerrainVertexType();

	private TerrainVertexType() {
	}

	@Override
	public VertexFormat getVertexFormat() {
		return FORMAT;
	}

	@Override
	public ChunkVertexEncoder getEncoder() {
		return this.encoder;
	}
}
