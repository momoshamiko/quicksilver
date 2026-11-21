// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TypesTest {

	@Test
	void glslSpellingOfEveryKindOfWord() {
		for (String word : new String[] {"float", "int", "uint", "bool", "double", "vec3", "ivec2", "uvec4", "bvec4",
				"dvec2", "mat4", "mat4x3", "mat2x4", "dmat3", "dmat2x3", "usampler2D", "isampler3D",
				"samplerCubeShadow", "sampler2DArrayShadow", "image3D", "uimage2D", "iimageCubeArray",
				"atomic_uint"}) {
			GlslType t = Types.fromWord(word);
			assertEquals(word, t.toString(), word);
		}
	}

	@Test
	void squareMatrixSpellingsAreOneType() {
		assertEquals(Types.fromWord("mat3"), Types.fromWord("mat3x3"));
		assertEquals(Types.fromWord("mat4"), Types.fromWord("mat4x4"));
	}

	@Test
	void nonSquareMatrixHasColumnsAndRowsInGlslOrder() {
		// mat4x3: four columns, each a vec3 (GLSL 4.60 section 4.1.6)
		MatrixType m = assertInstanceOf(MatrixType.class, Types.fromWord("mat4x3"));
		assertEquals(4, m.cols());
		assertEquals(3, m.rows());
		assertEquals(VectorType.VEC3, m.columnType());
	}

	@Test
	void samplerDimensionShadowAndTexelKind() {
		SamplerType shadow = assertInstanceOf(SamplerType.class, Types.fromWord("samplerCubeShadow"));
		assertEquals(SamplerDim.CUBE, shadow.dim());
		assertTrue(shadow.shadow());
		assertEquals(ScalarKind.FLOAT, shadow.texel());

		SamplerType arrayUnsigned = assertInstanceOf(SamplerType.class, Types.fromWord("usampler2DArray"));
		assertEquals(SamplerDim.D2_ARRAY, arrayUnsigned.dim());
		assertTrue(arrayUnsigned.dim().arrayed());
		assertEquals(ScalarKind.UINT, arrayUnsigned.texel());
		assertEquals(2, arrayUnsigned.dim().coords());
	}

	@Test
	void imageKindFollowsItsTexelWord() {
		ImageType i = assertInstanceOf(ImageType.class, Types.fromWord("iimage3D"));
		assertEquals(SamplerDim.D3, i.dim());
		assertEquals(ScalarKind.INT, i.texel());
		assertEquals(ScalarKind.FLOAT, ((ImageType) Types.fromWord("image3D")).texel());
	}

	@Test
	void structTypesAreDistinctByDeclaration() {
		StructField x = new StructField("x", ScalarType.FLOAT);
		StructType first = new StructType("S", List.of(x), 1);
		StructType second = new StructType("S", List.of(x), 2);
		assertNotEquals(first, second);
		assertEquals(first, first);
		assertEquals(x, first.field("x"));
		assertNull(first.field("y"));
	}

	@Test
	void arraySpellingIsInDeclarationOrder() {
		// float a[2][3] is an array of size 2 whose elements are float[3] (GLSL 4.60 section 4.1.9)
		GlslType inner = new ArrayType(ScalarType.FLOAT, 3);
		ArrayType outer = new ArrayType(inner, 2);
		assertEquals("float[2][3]", outer.toString());
		assertEquals(3, ((ArrayType) outer.element()).size());
	}

	@Test
	void unsizedOuterArrayOfSizedElements() {
		GlslType inner = new ArrayType(ScalarType.FLOAT, 3);
		assertEquals("float[][3]", new ArrayType(inner, null).toString());
	}

	@Test
	void scalarAndVectorPredicates() {
		assertTrue(Types.isInteger(VectorType.UVEC3));
		assertFalse(Types.isInteger(VectorType.VEC3));
		assertTrue(Types.isBoolean(VectorType.BVEC2));
		assertFalse(Types.isNumeric(ScalarType.BOOL));
	}
}
