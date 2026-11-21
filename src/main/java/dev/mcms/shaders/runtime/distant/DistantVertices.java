// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.distant;

import dev.mcms.glsl.msl.ProgramRequest.Semantic;
import dev.mcms.glsl.msl.ProgramRequest.VertexAttribute;
import dev.mcms.glsl.msl.ProgramRequest.VertexLayout;

/**
 * Distant Horizons' terrain vertex as the engine's pack programs see it (plan.md E7, "Distant Horizons, as built"; the
 * record is DH 3.3.4's {@code LodQuadBuilder.putVertex}, javap 2026-11-15). One vertex is 16 bytes, little-endian, in one
 * buffer:
 *
 * <pre>
 *   0  ushort x, y, z      block offsets from the draw's model offset (RGB16_UINT, "Short XYZ Pos")
 *   6  ushort meta         low byte: sky light in bits 0-3, block light in bits 4-7; high byte: micro offsets
 *                          (bit 0 x+, bit 1 x-, bit 2 y+, bit 3 y-, bit 4 z+, bit 5 z-)
 *   8  uchar4 colour       RGBA8 (alpha 255 unless the quad is translucent)
 *  12  uchar material      EDhApiBlockMaterial index (R8_UINT, "Iris Material"; the dhMaterialId attribute)
 *  13  uchar direction     EDhDirection index: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east (R8_UINT, "Iris Normal")
 *  14  ushort tile         block texture tile id (R16_UINT, "DH Texture Tile"; DH's own atlas: the pack never reads it)
 * </pre>
 *
 * <p>DH's Blaze3D terrain pipelines read the same bytes ({@code BlazeDhTerrainRenderer}: attributes {@code vPosition},
 * {@code meta}, {@code vColor}, {@code irisMaterial}, {@code irisNormal}, {@code textureTile}). The sky nibble is the low
 * one: DH's shader names the high nibble {@code skyLight} and passes it as the light map's u, which is MC's block axis,
 * so the high nibble is the block light and the low one the sky light (the two names of the shader and the encoder
 * agree on that; {@code putVertex}'s argument order is the proof).
 *
 * <p>The pack's programs read the vertex as Iris presents it (engine-pack-format.md 17, the Iris DH page): {@code gl_Vertex}
 * is the position, {@code gl_Color} the colour, {@code gl_Normal} the unit direction of the index, {@code dhMaterialId} the
 * material, and the light map is served on both {@code gl_MultiTexCoord1} (what {@code GetLightMapCoordinates} reads in
 * Complementary's dh_terrain) and {@code gl_MultiTexCoord2} (the Iris page's name for it). The light is MC's light-map
 * coordinate, {@code level * 16} per axis (u = block, v = sky), so the translator's light-map matrix ({@code /256 + 1/32})
 * gives the cell centre. The position adds DH's micro offset ({@code uMircoOffset} = 0.01, DH's shader) so the seams
 * stay as DH draws them.
 *
 * <p>Attribute indices in the decode expressions: {@code qs_a<n>} is the attribute's index in {@link #layout()}; the
 * order below is fixed by the constants {@link #A_POSITION} to {@link #A_TEXCOORD2}.
 */
public final class DistantVertices {

	/** The vertex stride of DH's terrain buffers (16 bytes). */
	public static final int STRIDE = 16;

	/** Byte offsets of the record's members. */
	public static final int OFFSET_POSITION = 0;
	public static final int OFFSET_META = 6;
	public static final int OFFSET_COLOUR = 8;
	public static final int OFFSET_MATERIAL = 12;
	public static final int OFFSET_DIRECTION = 13;
	public static final int OFFSET_TILE = 14;

	/** The micro offset DH's terrain shader applies per axis (DH's {@code uMircoOffset}, 0.01 in its shared block). */
	public static final float MICRO_OFFSET = 0.01f;

	/** Indices of the attributes in {@link #layout()}, which the decode expressions name as {@code qs_a<n>}. */
	public static final int A_POSITION = 0;
	public static final int A_META_LIGHT = 1;
	public static final int A_COLOUR = 2;
	public static final int A_MATERIAL = 3;
	public static final int A_DIRECTION = 4;
	public static final int A_META_TEXCOORD2 = 5;

	/** The light-map coordinate of one light level pair: {@code level * 16} (MC's light-map units). */
	private static final String LIGHT_DECODE =
		"float2(float((qs_a%1$d >> 4u) & 0xFu) * 16.0, float(qs_a%1$d & 0xFu) * 16.0)";

	/**
	 * The micro offset of the position: the bits of the meta high byte, applied as DH's shader does (x and z; y is
	 * commented out there). A set {@code +} bit gives {@code +MICRO_OFFSET}, the {@code -} bit flips its sign.
	 */
	private static final String POSITION_DECODE =
		"float3(qs_a0) + float3("
			+ "((((uint(qs_a1) >> 8u) & 1u) != 0u) ? 0.01 : 0.0) * ((((uint(qs_a1) >> 8u) & 2u) != 0u) ? -1.0 : 1.0), "
			+ "0.0, "
			+ "((((uint(qs_a1) >> 8u) & 16u) != 0u) ? 0.01 : 0.0) * ((((uint(qs_a1) >> 8u) & 32u) != 0u) ? -1.0 : 1.0))";

	/**
	 * The unit direction of the index: the axis is {@code index >> 1} (0 y, 1 z, 2 x) and the sign is positive for odd
	 * indices (up, south, east) and negative for even ones (down, north, west).
	 */
	private static final String DIRECTION_DECODE =
		"(float3(float((uint(qs_a4) >> 1u) == 2u), float((uint(qs_a4) >> 1u) == 0u), float((uint(qs_a4) >> 1u) == 1u))"
			+ " * (((uint(qs_a4) & 1u) != 0u) ? 1.0 : -1.0))";

	private DistantVertices() {
	}

	/**
	 * The layout the translator sees: six attributes of buffer 0. The position and the direction and the light are decoded;
	 * the colour and the material are the plain conversions. The tile is not an attribute: the pack has no use for DH's
	 * atlas index.
	 */
	public static VertexLayout layout() {
		return VertexLayout.of(
			new VertexAttribute(Semantic.POSITION, "ushort3", 0, OFFSET_POSITION, POSITION_DECODE),
			new VertexAttribute(Semantic.LIGHTMAP, "ushort1", 0, OFFSET_META, String.format(LIGHT_DECODE, A_META_LIGHT)),
			new VertexAttribute(Semantic.COLOR, "uchar4Normalized", 0, OFFSET_COLOUR),
			new VertexAttribute(Semantic.DH_MATERIAL_ID, "uchar1", 0, OFFSET_MATERIAL, "int(qs_a3)"),
			new VertexAttribute(Semantic.NORMAL, "uchar1", 0, OFFSET_DIRECTION, DIRECTION_DECODE),
			new VertexAttribute(Semantic.TEXCOORD2, "ushort1", 0, OFFSET_META,
				String.format(LIGHT_DECODE, A_META_TEXCOORD2)));
	}
}
