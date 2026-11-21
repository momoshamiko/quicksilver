// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.distant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.msl.ProgramRequest.Semantic;
import dev.mcms.glsl.msl.ProgramRequest.VertexAttribute;
import dev.mcms.glsl.msl.ProgramRequest.VertexLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/**
 * The DH terrain record (javap of {@code LodQuadBuilder.putVertex}, DH 3.3.4, 2026-11-15): the reference encoder below
 * is that method's arithmetic, and the decoders are the expressions the layout's MSL implements.
 */
class DistantVerticesTest {

	/** The reference encoder: {@code putVertex} with its argument order (position, meta bits, colour, material, direction). */
	static ByteBuffer encode(int x, int y, int z, int argb, int material, int direction, int skyLight, int blockLight,
			int microX, int microY, int microZ, int tile) {
		ByteBuffer b = ByteBuffer.allocate(DistantVertices.STRIDE).order(ByteOrder.LITTLE_ENDIAN);
		b.putShort((short) x).putShort((short) y).putShort((short) z);
		int lights = (skyLight % 16) | ((blockLight % 16) << 4);
		int micro = (microX == 0 ? 0 : microX > 0 ? 1 : 3)
			| (microY == 0 ? 0 : microY > 0 ? 4 : 12)
			| (microZ == 0 ? 0 : microZ > 0 ? 16 : 48);
		b.putShort((short) (lights | (micro << 8)));
		b.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
		b.put((byte) material).put((byte) direction).putShort((short) tile);
		return b.flip();
	}

	static int u8(ByteBuffer b, int at) {
		return b.get(at) & 0xFF;
	}

	static int u16(ByteBuffer b, int at) {
		return b.getShort(at) & 0xFFFF;
	}

	@Test
	void recordIsSixteenBytesWithTheDhOffsets() {
		assertEquals(16, DistantVertices.STRIDE);
		assertEquals(0, DistantVertices.OFFSET_POSITION);
		assertEquals(6, DistantVertices.OFFSET_META);
		assertEquals(8, DistantVertices.OFFSET_COLOUR);
		assertEquals(12, DistantVertices.OFFSET_MATERIAL);
		assertEquals(13, DistantVertices.OFFSET_DIRECTION);
		assertEquals(14, DistantVertices.OFFSET_TILE);
		// The members tile the record: 3 shorts, meta, 4 colour bytes, material, direction, tile.
		assertEquals(16, 6 + 2 + 4 + 1 + 1 + 2);
	}

	@Test
	void layoutServesTheIrisNamesAtTheRecordOffsets() {
		VertexLayout layout = DistantVertices.layout();
		assertEquals(6, layout.attributes().size());
		VertexAttribute position = layout.attributes().get(DistantVertices.A_POSITION);
		assertEquals(Semantic.POSITION, position.semantic());
		assertEquals("ushort3", position.format());
		assertEquals(0, position.offset());
		VertexAttribute light = layout.attributes().get(DistantVertices.A_META_LIGHT);
		assertEquals(Semantic.LIGHTMAP, light.semantic());
		assertEquals(6, light.offset());
		VertexAttribute colour = layout.attributes().get(DistantVertices.A_COLOUR);
		assertEquals(Semantic.COLOR, colour.semantic());
		assertEquals(8, colour.offset());
		VertexAttribute material = layout.attributes().get(DistantVertices.A_MATERIAL);
		assertEquals(Semantic.DH_MATERIAL_ID, material.semantic());
		assertEquals(12, material.offset());
		VertexAttribute direction = layout.attributes().get(DistantVertices.A_DIRECTION);
		assertEquals(Semantic.NORMAL, direction.semantic());
		assertEquals(13, direction.offset());
		VertexAttribute texcoord2 = layout.attributes().get(DistantVertices.A_META_TEXCOORD2);
		assertEquals(Semantic.TEXCOORD2, texcoord2.semantic());
		assertEquals(6, texcoord2.offset());
	}

	@Test
	void decodesReadTheAttributeIndicesTheLayoutAssigns() {
		VertexLayout layout = DistantVertices.layout();
		String position = layout.attributes().get(DistantVertices.A_POSITION).decode();
		assertTrue(position.contains("uint(qs_a1)"), "the micro offsets read the meta attribute");
		assertTrue(layout.attributes().get(DistantVertices.A_META_LIGHT).decode().contains("qs_a1"));
		assertTrue(layout.attributes().get(DistantVertices.A_META_TEXCOORD2).decode().contains("qs_a5"));
		assertEquals("int(qs_a3)", layout.attributes().get(DistantVertices.A_MATERIAL).decode());
		assertTrue(layout.attributes().get(DistantVertices.A_DIRECTION).decode().contains("qs_a4"));
	}

	@Test
	void lightNibblesAreSkyLowAndBlockHigh() {
		ByteBuffer v = encode(0, 0, 0, 0xFFFFFFFF, 2, 1, 5, 9, 0, 0, 0, 0);
		int meta = u16(v, DistantVertices.OFFSET_META);
		assertEquals(5, meta & 0xF, "sky light: the low nibble");
		assertEquals(9, (meta >> 4) & 0xF, "block light: the high nibble");
		// The light-map coordinate is level * 16 per axis: u = block, v = sky (MC's light-map axes).
		assertEquals(9 * 16, ((meta >> 4) & 0xF) * 16);
		assertEquals(5 * 16, (meta & 0xF) * 16);
	}

	@Test
	void microOffsetBitsFollowTheShaderAxes() {
		ByteBuffer plus = encode(0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1, 0);
		int micro = u16(plus, DistantVertices.OFFSET_META) >> 8;
		assertEquals(1, micro & 1, "x positive is bit 0");
		assertEquals(16, micro & 16, "z positive is bit 4");
		ByteBuffer minus = encode(0, 0, 0, 0, 0, 0, 0, 0, -1, 0, -1, 0);
		int negative = u16(minus, DistantVertices.OFFSET_META) >> 8;
		assertEquals(2, negative & 2, "x negative is bit 1");
		assertEquals(32, negative & 32, "z negative is bit 5");
	}

	@Test
	void recordFieldsLandAtTheirOffsets() {
		ByteBuffer v = encode(7, 300, 65535, 0x11223344, 42, 5, 0, 0, 0, 0, 0, 0x0102);
		assertEquals(7, u16(v, 0));
		assertEquals(300, u16(v, 2));
		assertEquals(65535, u16(v, 4));
		// ARGB (DH's ColorUtil): red, green, blue, then alpha, as putVertex writes them
		assertEquals(0x22, u8(v, DistantVertices.OFFSET_COLOUR));
		assertEquals(0x33, u8(v, DistantVertices.OFFSET_COLOUR + 1));
		assertEquals(0x44, u8(v, DistantVertices.OFFSET_COLOUR + 2));
		assertEquals(0x11, u8(v, DistantVertices.OFFSET_COLOUR + 3));
		assertEquals(42, u8(v, DistantVertices.OFFSET_MATERIAL));
		assertEquals(5, u8(v, DistantVertices.OFFSET_DIRECTION));
		assertEquals(0x0102, u16(v, DistantVertices.OFFSET_TILE));
	}

	/**
	 * The direction table of DH's terrain shader ({@code vNormalIndex} comments): index 0 down, 1 up, 2 north, 3 south,
	 * 4 west, 5 east, with the axis {@code index >> 1} and the sign negative for even indices. The MSL decode computes
	 * the same vector; this is its reference.
	 */
	static int[] directionOf(int index) {
		int axis = index >> 1;
		int sign = (index & 1) != 0 ? 1 : -1;
		int[] v = new int[3];
		// axis 0 is y (down, up), 1 is z (north, south), 2 is x (west, east)
		v[axis == 0 ? 1 : axis == 1 ? 2 : 0] = sign;
		return v;
	}

	@Test
	void directionIndicesMatchDhsEnumOrder() {
		assertEquals(0, directionOf(0)[0]);
		assertEquals(-1, directionOf(0)[1], "down is -y");
		assertEquals(1, directionOf(1)[1], "up is +y");
		assertEquals(-1, directionOf(2)[2], "north is -z");
		assertEquals(1, directionOf(3)[2], "south is +z");
		assertEquals(-1, directionOf(4)[0], "west is -x");
		assertEquals(1, directionOf(5)[0], "east is +x");
	}
}
