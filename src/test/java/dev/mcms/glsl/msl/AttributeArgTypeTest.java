// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The MSL argument type a vertex attribute's stage input is declared with. The Distant Horizons terrain's position is a ushort3, and
 * its meta a ushort1; the position must keep three integer components, not four.
 */
class AttributeArgTypeTest {

	@Test
	void theDistantPositionIsAThreeComponentIntegerInput() {
		assertEquals("uint3", StageEmitter.attributeArgType("ushort3"));
	}

	@Test
	void aOneComponentIntegerIsAScalarInput() {
		assertEquals("uint", StageEmitter.attributeArgType("ushort1"));
	}

	@Test
	void aNormalisedColourIsAFloatFourInput() {
		assertEquals("float4", StageEmitter.attributeArgType("uchar4Normalized"));
	}

	@Test
	void aFloatThreeInputKeepsItsPaddingToFour() {
		assertEquals("float4", StageEmitter.attributeArgType("float3"));
	}

	@Test
	void aSignedThreeComponentIntegerKeepsThreeComponents() {
		assertEquals("int3", StageEmitter.attributeArgType("short3"));
	}
}
