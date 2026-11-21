// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import org.junit.jupiter.api.Test;

/**
 * The terrain vertex format against the offsets the encoder writes and the stride the layout registers (E4 review item
 * 11): every attribute's format, its offset (the {@code A_*} constants the encoder and its tests use) and its size, which
 * must tile the 44-byte stride with no gap and no overlap. The first 20 bytes are Sodium's compact vertex.
 */
class TerrainVertexTypeTest {

	/** Each attribute's name, offset, format and size in bytes, in the order the layout lists them. */
	private static final Object[][] LAYOUT = {
		{ "a_Position", TerrainVertexType.A_POSITION, GpuFormat.RG32_UINT, 8 },
		{ "a_Color", TerrainVertexType.A_COLOR, GpuFormat.RGBA8_UNORM, 4 },
		{ "a_TexCoord", TerrainVertexType.A_TEXCOORD, GpuFormat.RG16_UINT, 4 },
		{ "a_LightAndData", TerrainVertexType.A_LIGHT_AND_DATA, GpuFormat.RGBA8_UINT, 4 },
		{ "a_Entity", TerrainVertexType.A_ENTITY, GpuFormat.RG16_UINT, 4 },
		{ "a_Tangent", TerrainVertexType.A_TANGENT, GpuFormat.RGBA8_SNORM, 4 },
		{ "a_Normal", TerrainVertexType.A_NORMAL, GpuFormat.RGBA8_SNORM, 4 },
		{ "a_MidBlock", TerrainVertexType.A_MID_BLOCK, GpuFormat.RGBA8_SNORM, 4 },
		{ "a_MidTexCoord", TerrainVertexType.A_MID_TEXCOORD, GpuFormat.RG32_FLOAT, 8 },
	};

	@Test
	void theStrideIsTheSodiumVertexPlusTheExtension() {
		assertEquals(20, TerrainVertexType.EXTENSION_OFFSET, "Sodium's compact vertex is 20 bytes");
		assertEquals(44, TerrainVertexType.STRIDE);
		assertEquals(TerrainVertexType.STRIDE, TerrainVertexType.INSTANCE.getVertexFormat().getVertexSize(),
			"the registered format must carry the stride the encoder writes");
	}

	@Test
	void everyAttributeHasItsFormatAndItsOffset() {
		VertexFormat format = TerrainVertexType.INSTANCE.getVertexFormat();
		assertEquals(LAYOUT.length, format.getElements().size());
		for (Object[] attribute : LAYOUT) {
			VertexFormatElement element = format.getElement((String) attribute[0]);
			assertEquals((int) attribute[1], element.offset(), attribute[0] + " offset");
			assertEquals(attribute[2], element.format(), attribute[0] + " format");
		}
	}

	@Test
	void theAttributesTileTheStrideWithoutGapOrOverlap() {
		int next = 0;
		for (Object[] attribute : LAYOUT) {
			assertEquals(next, (int) attribute[1], attribute[0] + " starts where the previous attribute ends");
			next += (int) attribute[3];
		}
		assertEquals(TerrainVertexType.STRIDE, next);
	}
}
