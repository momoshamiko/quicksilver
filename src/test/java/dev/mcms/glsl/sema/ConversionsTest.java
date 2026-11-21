// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.types.ArrayType;
import dev.mcms.glsl.types.ErrorType;
import dev.mcms.glsl.types.MatrixType;
import dev.mcms.glsl.types.ScalarKind;
import dev.mcms.glsl.types.ScalarType;
import dev.mcms.glsl.types.VectorType;
import org.junit.jupiter.api.Test;

class ConversionsTest {

	@Test
	void intToUintIsImplicitAndUintToIntIsNot() {
		// The 4.00 table adds int to uint. The engine runs the 4.60 feature set at every #version (ADR 0006 E6), so the
		// table holds for every version; Complementary's composite compares a uint with an int in a 1.30 unit.
		assertTrue(Conversions.converts(ScalarType.INT, ScalarType.UINT));
		assertFalse(Conversions.converts(ScalarType.UINT, ScalarType.INT));
	}

	@Test
	void intAndUintToFloatAreImplicit() {
		assertTrue(Conversions.converts(ScalarType.INT, ScalarType.FLOAT));
		assertTrue(Conversions.converts(ScalarType.UINT, ScalarType.FLOAT));
		assertTrue(Conversions.converts(VectorType.UVEC3, VectorType.VEC3));
	}

	@Test
	void intUintAndFloatToDoubleAreImplicit() {
		assertTrue(Conversions.converts(ScalarType.INT, ScalarType.DOUBLE));
		assertTrue(Conversions.converts(ScalarType.UINT, ScalarType.DOUBLE));
		assertTrue(Conversions.converts(ScalarType.FLOAT, ScalarType.DOUBLE));
		assertTrue(Conversions.converts(MatrixType.MAT2, MatrixType.of(ScalarKind.DOUBLE, 2, 2)));
	}

	@Test
	void narrowingAndDowncastsAreNeverImplicit() {
		assertFalse(Conversions.converts(ScalarType.DOUBLE, ScalarType.FLOAT));
		assertFalse(Conversions.converts(ScalarType.FLOAT, ScalarType.INT));
		assertFalse(Conversions.converts(ScalarType.BOOL, ScalarType.INT));
	}

	@Test
	void vectorsConvertComponentwiseOnlyAtEqualSize() {
		assertTrue(Conversions.converts(VectorType.IVEC3, VectorType.VEC3));
		assertTrue(Conversions.converts(VectorType.IVEC3, VectorType.UVEC3));
		assertFalse(Conversions.converts(VectorType.IVEC2, VectorType.VEC3));
	}

	@Test
	void arraysAndSameTypesAreNotConverted() {
		assertFalse(Conversions.converts(new ArrayType(ScalarType.INT, 2), new ArrayType(ScalarType.FLOAT, 2)));
		assertFalse(Conversions.converts(ScalarType.FLOAT, ScalarType.FLOAT));
	}

	@Test
	void theErrorTypeConvertsToEverythingSoOneErrorDoesNotCascade() {
		assertTrue(Conversions.converts(ErrorType.ERROR, ScalarType.FLOAT));
	}

	@Test
	void eachVersionHasItsOwnConversionsAndTheFourZeroTableAddsTheRest() {
		// section 4.1.10 of each specification: 1.20 int to float; 1.30 uint to float; 4.00 int to uint and the double targets
		assertFalse(Conversions.convertsIn(ScalarType.INT, ScalarType.FLOAT, 110));
		assertTrue(Conversions.convertsIn(ScalarType.INT, ScalarType.FLOAT, 120));
		assertFalse(Conversions.convertsIn(ScalarType.UINT, ScalarType.FLOAT, 120));
		assertTrue(Conversions.convertsIn(ScalarType.UINT, ScalarType.FLOAT, 130));
		assertFalse(Conversions.convertsIn(ScalarType.INT, ScalarType.UINT, 330));
		assertTrue(Conversions.convertsIn(ScalarType.INT, ScalarType.UINT, 400));
		assertFalse(Conversions.convertsIn(ScalarType.FLOAT, ScalarType.DOUBLE, 330));
		assertTrue(Conversions.convertsIn(ScalarType.FLOAT, ScalarType.DOUBLE, 400));
		assertTrue(Conversions.converts(ScalarType.INT, ScalarType.UINT));
	}
}
