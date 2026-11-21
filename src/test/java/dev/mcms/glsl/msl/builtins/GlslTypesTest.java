// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl.builtins;

import dev.mcms.glsl.msl.builtins.GlslTypes.Dim;
import dev.mcms.glsl.msl.builtins.GlslTypes.Kind;
import dev.mcms.glsl.msl.builtins.GlslTypes.Scalar;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Parsing of the GLSL type strings the emitter hands the table (W2-C). */
final class GlslTypesTest {
	@Test
	void scalarsAndVectors() {
		assertEquals(Kind.FLOAT, GlslTypes.parse("float").kind());
		assertEquals(Kind.VECTOR, GlslTypes.parse("vec3").kind());
		assertEquals(3, GlslTypes.parse("vec3").components());
		assertEquals(Scalar.UINT, GlslTypes.parse("uvec4").scalar());
		assertEquals(4, GlslTypes.parse("uvec4").components());
		assertEquals(Scalar.BOOL, GlslTypes.parse("bvec2").scalar());
	}

	@Test
	void matricesAreColumnsFirst() {
		// GLSL matCxR: C columns, R rows; the MSL name keeps that order (matrix<float, C, R>).
		assertEquals(3, GlslTypes.parse("mat3x2").cols());
		assertEquals(2, GlslTypes.parse("mat3x2").rows());
		assertEquals("float3x2", GlslTypes.mslMatrix(3, 2));
		assertEquals(4, GlslTypes.parse("mat4").cols());
		assertEquals(4, GlslTypes.parse("mat4").rows());
		assertEquals("float4x4", GlslTypes.mslMatrix(4, 4));
	}

	@Test
	void samplerShapes() {
		assertEquals(new GlslTypes.SamplerShape(Dim.D2, false, ""), GlslTypes.parse("sampler2D").sampler());
		assertEquals(new GlslTypes.SamplerShape(Dim.D2_ARRAY, false, "u"), GlslTypes.parse("usampler2DArray").sampler());
		assertEquals(new GlslTypes.SamplerShape(Dim.CUBE, true, ""), GlslTypes.parse("samplerCubeShadow").sampler());
		assertEquals(new GlslTypes.SamplerShape(Dim.D2_MS, false, "i"), GlslTypes.parse("isampler2DMS").sampler());
		assertEquals("texture2d<float>", GlslTypes.mslTexture(GlslTypes.parse("sampler2D").sampler()));
		assertEquals("texture2d<uint>", GlslTypes.mslTexture(GlslTypes.parse("usampler2D").sampler()));
		assertEquals("depth2d_array<float>", GlslTypes.mslTexture(GlslTypes.parse("sampler2DArrayShadow").sampler()));
		assertEquals("texturecube_array<int>", GlslTypes.mslTexture(GlslTypes.parse("isamplerCubeArray").sampler()));
	}

	@Test
	void imageShapesAndFormats() {
		assertEquals(new GlslTypes.ImageShape(Dim.D2, "u", "r32ui"), GlslTypes.parse("uimage2D(r32ui)").image());
		assertEquals(new GlslTypes.ImageShape(Dim.D3, "", "rgba16f"), GlslTypes.parse("image3D(rgba16f)").image());
		assertThrows(IllegalArgumentException.class, () -> GlslTypes.parse("isampler2DShadow"));
	}

	@Test
	void unknownTypesAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> GlslTypes.parse("struct"));
		assertThrows(IllegalArgumentException.class, () -> GlslTypes.parse("mat5"));
		assertThrows(IllegalArgumentException.class, () -> GlslTypes.parse("vec5"));
	}

	@Test
	void doubleIsAParsedTypeSoTableCanRefuseIt() {
		assertEquals(Kind.DOUBLE, GlslTypes.parse("double").kind());
		assertEquals(Scalar.DOUBLE, GlslTypes.parse("dvec3").scalar());
	}

	@Test
	void imageTypesNameReadWriteMetalClasses() {
		assertEquals("texture2d<float, access::read_write>",
			GlslTypes.mslImage(GlslTypes.parse("image2D(rgba16f)").image()));
		assertEquals("texture2d<uint, access::read_write>",
			GlslTypes.mslImage(GlslTypes.parse("image1D(r32ui)").image()));
		assertEquals("texture2d_array<int, access::read_write>",
			GlslTypes.mslImage(GlslTypes.parse("iimage2DArray(r32i)").image()));
		assertEquals("texture_buffer<float, access::read_write>",
			GlslTypes.mslImage(GlslTypes.parse("imageBuffer(r32f)").image()));
		assertEquals("texturecube<uint, access::read_write>",
			GlslTypes.mslImage(GlslTypes.parse("uimageCube(r32ui)").image()));
	}

	@Test
	void samplerBuffersAreTextureBuffers() {
		assertEquals("texture_buffer<uint>", GlslTypes.mslTexture(GlslTypes.parse("usamplerBuffer").sampler()));
		assertEquals("texture2d<float>", GlslTypes.mslTexture(GlslTypes.parse("sampler1D").sampler()));
		assertEquals("depth2d_array<float>", GlslTypes.mslTexture(GlslTypes.parse("sampler1DArrayShadow").sampler()));
	}
}
