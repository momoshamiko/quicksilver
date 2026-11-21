// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.types.ArrayType;
import dev.mcms.glsl.types.MatrixType;
import dev.mcms.glsl.types.ScalarKind;
import dev.mcms.glsl.types.VectorType;
import org.junit.jupiter.api.Test;

class BuiltinVariablesTest {

	private static boolean visibleIn(String name, Stage stage) {
		return BuiltinVariables.byName(name).stream().anyMatch(e -> e.stage() == stage);
	}

	@Test
	void fragmentCoordinateIsOnlyAFragmentBuiltin() {
		assertTrue(visibleIn("gl_FragCoord", Stage.FRAGMENT));
		assertFalse(visibleIn("gl_FragCoord", Stage.VERTEX));
		assertFalse(visibleIn("gl_FragCoord", Stage.COMPUTE));
	}

	@Test
	void positionIsAVertexBuiltinOfTypeVec4() {
		assertTrue(visibleIn("gl_Position", Stage.VERTEX));
		assertFalse(visibleIn("gl_Position", Stage.FRAGMENT));
		assertEquals(VectorType.VEC4, BuiltinVariables.byName("gl_Position").getFirst().type());
	}

	@Test
	void computeIdsAreOnlyInCompute() {
		assertTrue(visibleIn("gl_WorkGroupID", Stage.COMPUTE));
		assertFalse(visibleIn("gl_WorkGroupID", Stage.FRAGMENT));
		assertFalse(visibleIn("gl_LocalInvocationID", Stage.VERTEX));
	}

	@Test
	void drawBufferLimitIsAConstantAtLeastTheSpecMinimum() {
		// GLSL 4.60 section 7.2: gl_MaxDrawBuffers is at least 8 in every implementation
		BuiltinVariables.Entry e = BuiltinVariables.byName("gl_MaxDrawBuffers").getFirst();
		assertEquals(BuiltinVariables.VarKind.CONST, e.kind());
		assertTrue(e.value().intValue() >= 8, "gl_MaxDrawBuffers is " + e.value());
	}

	@Test
	void compatibilityVertexAttributesAreMarkedCompat() {
		BuiltinVariables.Entry e = BuiltinVariables.byName("gl_Vertex").getFirst();
		assertTrue(e.compat());
		assertEquals(Stage.VERTEX, e.stage());
		assertEquals(VectorType.VEC4, e.type());
	}

	@Test
	void textureMatrixIsAnArrayOfMat4() {
		BuiltinVariables.Entry e = BuiltinVariables.byName("gl_TextureMatrix").getFirst();
		ArrayType array = (ArrayType) e.type();
		assertEquals(MatrixType.of(ScalarKind.FLOAT, 4, 4), array.element());
		assertTrue(array.size() != null && array.size() >= 1);
	}

	@Test
	void unknownNameIsNotABuiltinVariable() {
		assertFalse(BuiltinVariables.isBuiltinName("gl_NotAVariable"));
		assertTrue(BuiltinVariables.byName("gl_NotAVariable").isEmpty());
	}
}
