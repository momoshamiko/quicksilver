// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.types.GlslType;
import dev.mcms.glsl.types.SamplerDim;
import dev.mcms.glsl.types.SamplerType;
import dev.mcms.glsl.types.ScalarKind;
import dev.mcms.glsl.types.ScalarType;
import dev.mcms.glsl.types.VectorType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BuiltinFunctionsTest {
	private static final GlslType F = ScalarType.FLOAT;
	private static final GlslType I = ScalarType.INT;
	private static final GlslType VEC2 = VectorType.VEC2;
	private static final GlslType VEC3 = VectorType.VEC3;
	private static final GlslType VEC4 = VectorType.VEC4;
	private static final GlslType IVEC2 = VectorType.IVEC2;
	private static final GlslType BVEC3 = VectorType.BVEC3;

	private static List<BuiltinFunction> available(String name, int version) {
		return BuiltinFunctions.byName(name).stream().filter(f -> f.available(version)).toList();
	}

	private static boolean hasParams(BuiltinFunction f, GlslType... types) {
		return f.params().stream().map(BuiltinFunction.Param::type).toList().equals(List.of(types));
	}

	@Test
	void scalarFormOfMaxIsOneSignatureInGlsl110() {
		// minMax writes the float scalar form from both the vector family and the scalar form; it must be kept once.
		long count = available("max", 110).stream().filter(f -> hasParams(f, F, F)).count();
		assertEquals(1, count);
	}

	@Test
	void boolVectorMixIsOnlyFrom130() {
		assertTrue(available("mix", 120).stream().noneMatch(f -> hasParams(f, VEC3, VEC3, BVEC3)));
		assertTrue(available("mix", 130).stream().anyMatch(f -> hasParams(f, VEC3, VEC3, BVEC3)));
	}

	@Test
	void scalarMixSelectsWithAScalarBool() {
		// genBType includes bool (GLSL 4.60 section 8.3): mix(float, float, bool) is a signature
		assertTrue(available("mix", 130).stream().anyMatch(f -> hasParams(f, F, F, ScalarType.BOOL)));
	}

	@Test
	void plainSampler2DLookupTakesAVec2AndNoLayer() {
		// coordinates have one component per dimension; only arrayed samplers add the layer (GLSL 4.60 section 8.9)
		SamplerType s = new SamplerType(SamplerDim.D2, false, ScalarKind.FLOAT);
		assertTrue(available("texture", 130).stream().anyMatch(f -> hasParams(f, s, VEC2)));
		assertTrue(available("texture", 130).stream().noneMatch(f -> hasParams(f, s, VEC3)));
	}

	@Test
	void sampler2DShadowLookupTakesAVec3Coordinate() {
		// the reference is the third component of a 2D shadow coordinate (GLSL 4.60 section 8.9.1)
		SamplerType s = new SamplerType(SamplerDim.D2, true, ScalarKind.FLOAT);
		List<BuiltinFunction> lookups = available("texture", 400).stream()
				.filter(f -> f.params().size() == 2 && f.params().get(0).type().equals(s)).toList();
		assertTrue(lookups.stream().anyMatch(f -> f.params().get(1).type().equals(VEC3)));
		assertTrue(lookups.stream().noneMatch(f -> f.params().get(1).type().equals(VEC4)));
	}

	@Test
	void cubeArrayShadowLookupTakesAVec4AndASeparateReference() {
		// the cube-array shadow form takes its reference as a separate argument (GLSL 4.60 section 8.9.2)
		SamplerType s = new SamplerType(SamplerDim.CUBE_ARRAY, true, ScalarKind.FLOAT);
		assertTrue(available("texture", 400).stream().anyMatch(f -> hasParams(f, s, VEC4, F) && f.result().equals(F)));
	}

	@Test
	void texelFetchTakesIntegerCoordinatesAndALevel() {
		SamplerType s = new SamplerType(SamplerDim.D2, false, ScalarKind.FLOAT);
		assertTrue(available("texelFetch", 130).stream().anyMatch(f -> hasParams(f, s, IVEC2, I)));
		assertTrue(available("texelFetch", 130).stream().noneMatch(f -> hasParams(f, s, VEC3, I)));
	}

	@Test
	void derivativesExistOnlyInFragmentStages() {
		assertTrue(available("dFdx", 400).stream().noneMatch(f -> f.availableIn(Stage.VERTEX)));
		assertTrue(available("dFdx", 400).stream().anyMatch(f -> f.availableIn(Stage.FRAGMENT)));
	}

	@Test
	void noSignatureIsWrittenTwiceInTheTable() {
		for (String name : List.of("max", "min", "clamp", "mix", "mod", "step", "smoothstep", "texture",
				"texelFetch", "textureProj", "textureGather")) {
			Set<String> keys = new HashSet<>();
			for (BuiltinFunction f : BuiltinFunctions.byName(name)) {
				String key = f.params() + " -> " + f.result();
				assertTrue(keys.add(key), name + " has two entries for " + key);
			}
		}
	}

	@Test
	void unknownNameHasNoSignatures() {
		assertFalse(BuiltinFunctions.isBuiltinName("notARealFunction"));
		assertTrue(BuiltinFunctions.byName("notARealFunction").isEmpty());
	}
}
