// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal.builtins;

import dev.mcms.shaders.metal.builtins.BuiltinKernel.Case;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.mcms.shaders.metal.builtins.BuiltinKernel.fn;
import static dev.mcms.shaders.metal.builtins.BuiltinKernel.types;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GPU value tests of the built-in table's numeric, common, geometric, matrix and relational mappings (W2-C, ADR 0006).
 * Each case runs the table's MSL on the device under fast and precise math and compares with a Java reference written
 * from the GLSL 4.60 definitions (chapter 8.3-8.7). Tolerances: 1e-6 relative for single IEEE operations, 1e-5 for
 * transcendental functions (their MSL implementations are a few ulp off), 1e-4 for matrix inverses (double-precision
 * Gauss-Jordan in the reference). Edge inputs: negative and zero divisors, signed zeros, values on both sides of every
 * clamp, exact ties of roundEven. Ties of round() are not tested: GLSL leaves them to the implementation.
 */
@Tag("gpu")
final class BuiltinValuesGpuTest {
	private static final float E6 = 1e-6f, E5 = 1e-5f, E4 = 1e-4f;

	@FunctionalInterface
	private interface F1 {
		float ap(float x);
	}

	@FunctionalInterface
	private interface F2 {
		float ap(float x, float y);
	}

	@FunctionalInterface
	private interface F3 {
		float ap(float x, float y, float z);
	}

	private static float[] map1(float[] x, F1 f) {
		float[] o = new float[x.length];
		for (int i = 0; i < x.length; i++) o[i] = f.ap(x[i]);
		return o;
	}

	private static float[] map2(float[] x, float[] y, F2 f) {
		float[] o = new float[Math.max(x.length, y.length)];
		for (int i = 0; i < o.length; i++) o[i] = f.ap(x[x.length == 1 ? 0 : i], y[y.length == 1 ? 0 : i]);
		return o;
	}

	private static float[] map3(float[] x, float[] y, float[] z, F3 f) {
		float[] o = new float[Math.max(x.length, Math.max(y.length, z.length))];
		for (int i = 0; i < o.length; i++)
			o[i] = f.ap(x[x.length == 1 ? 0 : i], y[y.length == 1 ? 0 : i], z[z.length == 1 ? 0 : i]);
		return o;
	}

	/** GLSL 4.60 8.3 mod: x - y * floor(x / y), in float arithmetic. */
	static float glslMod(float x, float y) {
		return x - y * (float) Math.floor(x / y);
	}

	static float glslFract(float x) {
		return x - (float) Math.floor(x);
	}

	static float glslSmoothstep(float e0, float e1, float x) {
		float t = (x - e0) / (e1 - e0);
		t = Math.min(1f, Math.max(0f, t));
		return t * t * (3f - 2f * t);
	}

	@Test
	void modFollowsTheFlooredDefinitionIncludingNegativeDivisors() {
		float[] x = {5.5f, -5.5f, 0.25f, -0.25f, 7f};
		float[] y = {2f, 2f, -0.75f, -0.75f, -3f};
		List<Case> cases = new ArrayList<>();
		cases.add(fn("mod", "vec3", types("vec3", "vec3"), new Object[]{new float[]{5.5f, -5.5f, 0.25f},
			new float[]{2f, 2f, -0.75f}}, a -> new Object[]{map2((float[]) a[0], (float[]) a[1], BuiltinValuesGpuTest::glslMod)}, E6));
		cases.add(fn("mod", "vec4", types("vec4", "float"), new Object[]{new float[]{x[0], x[1], x[2], x[3]},
			new float[]{-0.75f}}, a -> new Object[]{map2((float[]) a[0], (float[]) a[1], BuiltinValuesGpuTest::glslMod)}, E6));
		cases.add(fn("mod", "float", types("float", "float"), new Object[]{7f, -3f},
			a -> new Object[]{glslMod(7f, -3f)}, E6));
		cases.add(fn("mod", "vec2", types("vec2", "vec2"), new Object[]{new float[]{-1e-3f, 1e3f},
			new float[]{1e-4f, 37f}}, a -> new Object[]{map2((float[]) a[0], (float[]) a[1], BuiltinValuesGpuTest::glslMod)}, E5));
		assertNoFailures(cases);
	}

	@Test
	void atanTwoArgumentsCoversEverySignQuadrant() {
		float[] y = {1f, 1f, -1f, -1f, 0f, 0f, 1f, -1f};
		float[] x = {1f, -1f, -1f, 1f, -1f, 1f, 0f, 0f};
		assertNoFailures(List.of(fn("atan", "vec4", types("vec4", "vec4"), new Object[]{
				new float[]{y[0], y[1], y[2], y[3]}, new float[]{x[0], x[1], x[2], x[3]}},
			a -> new Object[]{map2((float[]) a[0], (float[]) a[1], (p, q) -> (float) Math.atan2(p, q))}, E5),
			fn("atan", "vec4", types("vec4", "vec4"), new Object[]{
					new float[]{y[4], y[5], y[6], y[7]}, new float[]{x[4], x[5], x[6], x[7]}},
				a -> new Object[]{map2((float[]) a[0], (float[]) a[1], (p, q) -> (float) Math.atan2(p, q))}, E5),
			fn("atan", "float", types("float"), new Object[]{-0.5f}, a -> new Object[]{(float) Math.atan(-0.5f)}, E5),
			fn("atan", "vec3", types("vec3"), new Object[]{new float[]{0.1f, 10f, -30f}},
				a -> new Object[]{map1((float[]) a[0], v -> (float) Math.atan(v))}, E5)));
	}

	@Test
	void exponentialAndRootFunctions() {
		float[] pos = {0.25f, 2f, 1e4f};
		assertNoFailures(List.of(
			fn("inversesqrt", "vec3", types("vec3"), new Object[]{pos},
				a -> new Object[]{map1((float[]) a[0], v -> (float) (1.0 / Math.sqrt(v)))}, E5),
			fn("pow", "vec2", types("vec2", "vec2"), new Object[]{new float[]{2f, 3f}, new float[]{3f, 0.5f}},
				a -> new Object[]{map2((float[]) a[0], (float[]) a[1], (p, q) -> (float) Math.pow(p, q))}, E5),
			fn("exp", "vec3", types("vec3"), new Object[]{new float[]{-2f, 0f, 3f}},
				a -> new Object[]{map1((float[]) a[0], v -> (float) Math.exp(v))}, E5),
			fn("log", "vec3", types("vec3"), new Object[]{pos}, a -> new Object[]{map1((float[]) a[0], v -> (float) Math.log(v))}, E5),
			fn("exp2", "float", types("float"), new Object[]{-3.5f}, a -> new Object[]{(float) Math.pow(2, -3.5f)}, E5),
			fn("log2", "float", types("float"), new Object[]{10f}, a -> new Object[]{(float) (Math.log(10f) / Math.log(2))}, E5),
			fn("sqrt", "vec3", types("vec3"), new Object[]{pos}, a -> new Object[]{map1((float[]) a[0], v -> (float) Math.sqrt(v))}, E6),
			fn("radians", "vec3", types("vec3"), new Object[]{new float[]{180f, 90f, -45f}},
				a -> new Object[]{map1((float[]) a[0], v -> (float) Math.toRadians(v))}, E5),
			fn("degrees", "vec3", types("vec3"), new Object[]{new float[]{3.14159274f, 1f, -2f}},
				a -> new Object[]{map1((float[]) a[0], v -> (float) Math.toDegrees(v))}, E5)));
	}

	@Test
	void roundingFunctionsIncludingEvenTies() {
		float[] v = {2.5f, -2.5f, 3.5f, -0.2f, 0.75f, -7f};
		assertNoFailures(List.of(
			fn("floor", "vec3", types("vec3"), new Object[]{new float[]{v[0], v[1], v[3]}},
				a -> new Object[]{map1((float[]) a[0], x -> (float) Math.floor(x))}, E6),
			fn("ceil", "vec3", types("vec3"), new Object[]{new float[]{v[0], v[1], v[3]}},
				a -> new Object[]{map1((float[]) a[0], x -> (float) Math.ceil(x))}, E6),
			fn("trunc", "vec4", types("vec4"), new Object[]{new float[]{v[0], v[1], v[3], v[4]}},
				a -> new Object[]{map1((float[]) a[0], x -> x < 0 ? (float) Math.ceil(x) : (float) Math.floor(x))}, E6),
			fn("fract", "vec4", types("vec4"), new Object[]{new float[]{v[0], v[1], v[3], v[4]}},
				a -> new Object[]{map1((float[]) a[0], BuiltinValuesGpuTest::glslFract)}, E6),
			fn("roundEven", "vec3", types("vec3"), new Object[]{new float[]{2.5f, -2.5f, 3.5f}},
				a -> new Object[]{new float[]{2f, -2f, 4f}}, 0f),
			fn("roundEven", "vec2", types("vec2"), new Object[]{new float[]{0.75f, -7f}},
				a -> new Object[]{new float[]{1f, -7f}}, 0f),
			fn("round", "vec2", types("vec2"), new Object[]{new float[]{2.25f, -2.75f}},
				a -> new Object[]{new float[]{2f, -3f}}, 0f)));
	}

	@Test
	void signAndAbsoluteValue() {
		assertNoFailures(List.of(
			fn("sign", "vec4", types("vec4"), new Object[]{new float[]{-3.5f, 0f, 2f, -0f}},
				a -> new Object[]{map1((float[]) a[0], x -> x > 0 ? 1f : (x < 0 ? -1f : 0f))}, E6),
			fn("sign", "int", types("int"), new Object[]{new int[]{-7}}, a -> new Object[]{new int[]{-1}}, 0f),
			fn("sign", "ivec3", types("ivec3"), new Object[]{new int[]{-7, 0, 9}},
				a -> new Object[]{new int[]{-1, 0, 1}}, 0f),
			fn("abs", "ivec3", types("ivec3"), new Object[]{new int[]{-7, 0, Integer.MIN_VALUE + 1}},
				a -> new Object[]{new int[]{7, 0, Integer.MAX_VALUE}}, 0f),
			fn("abs", "vec3", types("vec3"), new Object[]{new float[]{-3.5f, 0f, 2f}},
				a -> new Object[]{new float[]{3.5f, 0f, 2f}}, 0f)));
	}

	@Test
	void minMaxClampAndBroadcastOfScalarArguments() {
		float[] v = {-1f, 0.5f, 3f};
		assertNoFailures(List.of(
			fn("min", "vec3", types("vec3", "float"), new Object[]{v, new float[]{1f}},
				a -> new Object[]{map2(v, new float[]{1f}, Math::min)}, 0f),
			fn("max", "vec3", types("vec3", "vec3"), new Object[]{v, new float[]{0f, 0f, 9f}},
				a -> new Object[]{map2(v, new float[]{0f, 0f, 9f}, Math::max)}, 0f),
			fn("min", "ivec2", types("ivec2", "int"), new Object[]{new int[]{-4, 8}, new int[]{3}},
				a -> new Object[]{new int[]{-4, 3}}, 0f),
			fn("max", "uvec2", types("uvec2", "uint"), new Object[]{new int[]{1, -1}, new int[]{5}},
				a -> new Object[]{new int[]{5, -1}}, 0f),
			fn("clamp", "vec3", types("vec3", "float", "float"), new Object[]{v, new float[]{0f}, new float[]{1f}},
				a -> new Object[]{new float[]{0f, 0.5f, 1f}}, 0f),
			fn("clamp", "vec3", types("vec3", "vec3", "vec3"), new Object[]{v, new float[]{-2f, 0f, 0f},
				new float[]{2f, 0.25f, 2f}}, a -> new Object[]{new float[]{-1f, 0.25f, 2f}}, 0f),
			fn("clamp", "uvec3", types("uvec3", "uint", "uint"), new Object[]{new int[]{0, 5, 9}, new int[]{2},
				new int[]{6}}, a -> new Object[]{new int[]{2, 5, 6}}, 0f),
			fn("clamp", "ivec2", types("ivec2", "int", "int"), new Object[]{new int[]{-9, 9}, new int[]{-2},
				new int[]{2}}, a -> new Object[]{new int[]{-2, 2}}, 0f)));
	}

	@Test
	void mixSelectsAndInterpolates() {
		float[] x = {1f, 2f, 3f}, y = {10f, 20f, 30f};
		assertNoFailures(List.of(
			fn("mix", "vec3", types("vec3", "vec3", "float"), new Object[]{x, y, new float[]{0.25f}},
				a -> new Object[]{map3(x, y, new float[]{0.25f}, (p, q, t) -> p * (1 - t) + q * t)}, E6),
			fn("mix", "vec3", types("vec3", "vec3", "vec3"), new Object[]{x, y, new float[]{0f, 1f, 0.5f}},
				a -> new Object[]{new float[]{1f, 20f, 16.5f}}, E6),
			fn("mix", "vec2", types("vec2", "vec2", "bvec2"), new Object[]{new float[]{1f, 2f}, new float[]{5f, 6f},
				new int[]{1, 0}}, a -> new Object[]{new float[]{5f, 2f}}, 0f),
			fn("mix", "bvec2", types("bvec2", "bvec2", "bvec2"), new Object[]{new int[]{1, 0}, new int[]{0, 1},
				new int[]{1, 1}}, a -> new Object[]{new int[]{0, 1}}, 0f),
			fn("mix", "ivec3", types("ivec3", "ivec3", "bvec3"), new Object[]{new int[]{1, 2, 3},
				new int[]{-1, -2, -3}, new int[]{0, 1, 0}}, a -> new Object[]{new int[]{1, -2, 3}}, 0f)));
	}

	@Test
	void stepAndSmoothstepWithScalarEdges() {
		float[] v = {-1f, 0.5f, 0.9f, 2f};
		assertNoFailures(List.of(
			fn("step", "vec4", types("float", "vec4"), new Object[]{new float[]{0.5f}, v},
				a -> new Object[]{map2(new float[]{0.5f}, v, (e, x) -> x < e ? 0f : 1f)}, 0f),
			fn("step", "vec4", types("vec4", "vec4"), new Object[]{new float[]{0f, 0.5f, 1f, 1f}, v},
				a -> new Object[]{new float[]{0f, 1f, 0f, 1f}}, 0f),
			fn("smoothstep", "vec4", types("float", "float", "vec4"), new Object[]{new float[]{0f}, new float[]{1f}, v},
				a -> new Object[]{map3(new float[]{0f}, new float[]{1f}, v, BuiltinValuesGpuTest::glslSmoothstep)}, E6),
			fn("smoothstep", "float", types("float", "float", "float"), new Object[]{2f, 4f, 3f},
				a -> new Object[]{glslSmoothstep(2f, 4f, 3f)}, E6)));
	}

	@Test
	void fusedMultiplyAddAndLdexp() {
		assertNoFailures(List.of(
			fn("fma", "vec3", types("vec3", "vec3", "vec3"), new Object[]{new float[]{1.5f, -2f, 3f},
				new float[]{4f, 0.5f, -1f}, new float[]{0.25f, 1f, 1f}},
				a -> new Object[]{map3((float[]) a[0], (float[]) a[1], (float[]) a[2], (p, q, r) -> Math.fma(p, q, r))}, E6),
			fn("ldexp", "vec2", types("vec2", "ivec2"), new Object[]{new float[]{0.75f, -3f}, new int[]{4, -2}},
				a -> new Object[]{new float[]{12f, -0.75f}}, 0f)));
	}

	@Test
	void reductionsOfBooleansAndNumericChecks() {
		assertNoFailures(List.of(
			fn("isnan", "bvec2", types("vec2"), new Object[]{new float[]{1f, -0.5f}},
				a -> new Object[]{new int[]{0, 0}}, 0f),
			fn("isinf", "bvec2", types("vec2"), new Object[]{new float[]{1f, -0.5f}},
				a -> new Object[]{new int[]{0, 0}}, 0f),
			fn("any", "bool", types("bvec3"), new Object[]{new int[]{0, 0, 1}}, a -> new Object[]{1}, 0f),
			fn("all", "bool", types("bvec3"), new Object[]{new int[]{1, 0, 1}}, a -> new Object[]{0}, 0f),
			fn("not", "bvec2", types("bvec2"), new Object[]{new int[]{1, 0}}, a -> new Object[]{new int[]{0, 1}}, 0f)));
	}

	@Test
	void geometricFunctions() {
		float[] a3 = {1f, -2f, 0.5f}, b3 = {-4f, 0.25f, 3f};
		assertNoFailures(List.of(
			fn("length", "float", types("vec3"), new Object[]{a3}, a -> new Object[]{(float) Math.sqrt(1 + 4 + 0.25)}, E6),
			fn("distance", "float", types("vec3", "vec3"), new Object[]{a3, b3}, a -> new Object[]{
				(float) Math.sqrt(5 * 5 + 2.25 * 2.25 + 2.5 * 2.5)}, E6),
			fn("dot", "float", types("vec3", "vec3"), new Object[]{a3, b3},
				a -> new Object[]{1f * -4f + -2f * 0.25f + 0.5f * 3f}, E6),
			fn("cross", "vec3", types("vec3", "vec3"), new Object[]{a3, b3}, a -> new Object[]{new float[]{
				-2f * 3f - 0.5f * 0.25f, 0.5f * -4f - 1f * 3f, 1f * 0.25f - -2f * -4f}}, E6),
			fn("normalize", "vec3", types("vec3"), new Object[]{a3}, a -> {
				double n = Math.sqrt(1 + 4 + 0.25);
				return new Object[]{new float[]{(float) (1 / n), (float) (-2 / n), (float) (0.5 / n)}};
			}, E5),
			// OpenGL on this device gives NaN for the zero vector (Iris 1.11.7, measured with an instrumented Bliss 2.1.2);
			// MSL's own normalize gives 0 under fast math, which brightened Bliss's sky.
			fn("normalize", "vec2", types("vec2"), new Object[]{new float[]{0f, 0f}},
				a -> new Object[]{new float[]{Float.NaN, Float.NaN}}, 0f),
			fn("normalize", "vec3", types("vec3"), new Object[]{new float[]{0f, 0f, 0f}},
				a -> new Object[]{new float[]{Float.NaN, Float.NaN, Float.NaN}}, 0f),
			fn("normalize", "vec4", types("vec4"), new Object[]{new float[]{0f, 0f, 0f, 0f}},
				a -> new Object[]{new float[]{Float.NaN, Float.NaN, Float.NaN, Float.NaN}}, 0f),
			fn("normalize", "vec2", types("vec2"), new Object[]{new float[]{3f, -4f}},
				a -> new Object[]{new float[]{0.6f, -0.8f}}, E5),
			fn("normalize", "vec4", types("vec4"), new Object[]{new float[]{1f, -2f, 2f, 4f}},
				a -> new Object[]{new float[]{0.2f, -0.4f, 0.4f, 0.8f}}, E5),
			// E9 (x * rsqrt(dot(x, x))): an axis vector stays a unit axis exactly, so a pack's acos(dot(n, up)) or
			// sqrt(1 - d * d) of it gets no NaN from a length above 1.
			fn("normalize", "vec3", types("vec3"), new Object[]{new float[]{0f, 2f, 0f}},
				a -> new Object[]{new float[]{0f, 1f, 0f}}, 0f),
			fn("normalize", "vec3", types("vec3"), new Object[]{new float[]{0f, 0f, -7f}},
				a -> new Object[]{new float[]{0f, 0f, -1f}}, 0f),
			fn("normalize", "float", types("float"), new Object[]{0f}, a -> new Object[]{Float.NaN}, 0f),
			fn("normalize", "float", types("float"), new Object[]{-3f}, a -> new Object[]{-1f}, 0f),
			fn("reflect", "vec3", types("vec3", "vec3"), new Object[]{a3, new float[]{0f, 1f, 0f}}, a -> new Object[]{
				new float[]{1f, 2f, 0.5f}}, E6),
			fn("refract", "vec3", types("vec3", "vec3", "float"), new Object[]{new float[]{0f, -1f, 0f},
				new float[]{0f, 1f, 0f}, 0.5f}, a -> new Object[]{new float[]{0f, -1f, 0f}}, E6),
			fn("refract", "vec3", types("vec3", "vec3", "float"), new Object[]{new float[]{0.9f, -0.1f, 0f},
				new float[]{0f, 1f, 0f}, 2f}, a -> new Object[]{new float[]{0f, 0f, 0f}}, E6),
			fn("faceforward", "vec3", types("vec3", "vec3", "vec3"), new Object[]{a3, a3, new float[]{0f, 1f, 0f}},
				a -> new Object[]{a3}, 0f)));
	}

	@Test
	void relationalComparisonsAreComponentWise() {
		assertNoFailures(List.of(
			fn("lessThan", "bvec3", types("vec3", "vec3"), new Object[]{new float[]{1f, 2f, -0f},
				new float[]{2f, 2f, 0f}}, a -> new Object[]{new int[]{1, 0, 0}}, 0f),
			fn("lessThanEqual", "bvec3", types("vec3", "vec3"), new Object[]{new float[]{1f, 2f, -0f},
				new float[]{2f, 2f, 0f}}, a -> new Object[]{new int[]{1, 1, 1}}, 0f),
			fn("greaterThan", "bvec4", types("ivec4", "ivec4"), new Object[]{new int[]{-3, 0, 5, 9},
				new int[]{-4, 0, 5, 10}}, a -> new Object[]{new int[]{1, 0, 0, 0}}, 0f),
			fn("greaterThanEqual", "bvec2", types("uvec2", "uvec2"), new Object[]{new int[]{-1, 1},
				new int[]{1, 2}}, a -> new Object[]{new int[]{1, 0}}, 0f),
			fn("equal", "bvec2", types("ivec2", "ivec2"), new Object[]{new int[]{4, -1}, new int[]{4, 1}},
				a -> new Object[]{new int[]{1, 0}}, 0f),
			fn("notEqual", "bvec3", types("bvec3", "bvec3"), new Object[]{new int[]{1, 0, 1}, new int[]{1, 1, 0}},
				a -> new Object[]{new int[]{0, 1, 1}}, 0f)));
	}

	@Test
	void matrixFunctions() {
		float[] m2 = {3f, 1f, 2f, 4f}, m3 = {4f, 2f, 1f, 7f, 6f, 0f, 2f, 1f, 3f};
		float[] m4 = {5f, 1f, 0f, 2f, 1f, 6f, 1f, 0f, 0f, 1f, 7f, 1f, 2f, 0f, 1f, 8f};
		assertNoFailures(List.of(
			fn("matrixCompMult", "mat3", types("mat3", "mat3"), new Object[]{m3, m3},
				a -> new Object[]{map2(m3, m3, (p, q) -> p * q)}, E6),
			fn("outerProduct", "mat2x3", types("vec3", "vec2"), new Object[]{new float[]{1f, -2f, 0.5f},
				new float[]{3f, 4f}}, a -> new Object[]{new float[]{3f, -6f, 1.5f, 4f, -8f, 2f}}, E6),
			fn("outerProduct", "mat3", types("vec3", "vec3"), new Object[]{new float[]{1f, 2f, 3f},
				new float[]{4f, 5f, 6f}}, a -> new Object[]{new float[]{4f, 8f, 12f, 5f, 10f, 15f, 6f, 12f, 18f}}, E6),
			fn("transpose", "mat2", types("mat2"), new Object[]{m2}, a -> new Object[]{new float[]{3f, 2f, 1f, 4f}}, 0f),
			fn("transpose", "mat2x3", types("mat3x2"), new Object[]{new float[]{1f, 2f, 3f, 4f, 5f, 6f}},
				a -> new Object[]{new float[]{1f, 3f, 5f, 2f, 4f, 6f}}, 0f),
			fn("determinant", "float", types("mat2"), new Object[]{m2}, a -> new Object[]{10f}, E6),
			fn("determinant", "float", types("mat3"), new Object[]{m3}, a -> new Object[]{25f}, E5),
			fn("inverse", "mat2", types("mat2"), new Object[]{m2}, a -> new Object[]{invert(m2, 2)}, E4),
			fn("inverse", "mat3", types("mat3"), new Object[]{m3}, a -> new Object[]{invert(m3, 3)}, E4),
			fn("inverse", "mat4", types("mat4"), new Object[]{m4}, a -> new Object[]{invert(m4, 4)}, E4)));
	}

	/** Column-major inverse by Gauss-Jordan in double precision (the reference; the GPU result is compared to it). */
	static float[] invert(float[] m, int n) {
		double[][] a = new double[n][2 * n];
		for (int r = 0; r < n; r++) {
			for (int c = 0; c < n; c++) a[r][c] = m[c * n + r];
			a[r][n + r] = 1;
		}
		for (int col = 0; col < n; col++) {
			int piv = col;
			for (int r = col + 1; r < n; r++) if (Math.abs(a[r][col]) > Math.abs(a[piv][col])) piv = r;
			double[] t = a[col];
			a[col] = a[piv];
			a[piv] = t;
			double d = a[col][col];
			for (int c = 0; c < 2 * n; c++) a[col][c] /= d;
			for (int r = 0; r < n; r++) {
				if (r == col) continue;
				double f = a[r][col];
				for (int c = 0; c < 2 * n; c++) a[r][c] -= f * a[col][c];
			}
		}
		float[] out = new float[n * n];
		for (int c = 0; c < n; c++)
			for (int r = 0; r < n; r++) out[c * n + r] = (float) a[r][n + c];
		return out;
	}

	/** GLSL 4.60 8.3 frexp of a finite x: x = significand * 2^exponent with |significand| in [0.5, 1); frexp(0) = (0, 0). */
	static Object[] glslFrexp(float x) {
		if (x == 0f) return new Object[]{0f, 0};
		double d = x;
		int bias = 0;
		if (Math.abs(d) < Float.MIN_NORMAL) {  // a subnormal: scale into the normal range first (exact in double)
			d = Math.scalb(d, 100);
			bias = -100;
		}
		int e = Math.getExponent(d) + 1;
		return new Object[]{(float) Math.scalb(d, -e), e + bias};
	}

	/** isnan and isinf of run-time values: NaN and the infinities are read from the input buffer, not folded. */
	@Test
	void numericChecksOfRunTimeNaNAndInfinity() {
		float nan = Float.intBitsToFloat(0x7fc00000), inf = Float.POSITIVE_INFINITY, ninf = Float.NEGATIVE_INFINITY;
		assertNoFailures(List.of(
			fn("isnan", "bvec4", types("vec4"), new Object[]{new float[]{nan, inf, ninf, 0f}},
				a -> new Object[]{new int[]{1, 0, 0, 0}}, 0f),
			fn("isinf", "bvec4", types("vec4"), new Object[]{new float[]{nan, inf, ninf, 0f}},
				a -> new Object[]{new int[]{0, 1, 1, 0}}, 0f),
			fn("isnan", "bool", types("float"), new Object[]{nan}, a -> new Object[]{1}, 0f),
			fn("isinf", "bool", types("float"), new Object[]{ninf}, a -> new Object[]{1}, 0f)));
	}

	@Test
	void frexpSplitsFiniteValuesIntoSignificandAndExponent() {
		List<Case> cases = new ArrayList<>();
		for (float x : new float[]{1f, -3f, 0.75f, 1e30f, -1e-30f, 1.5e-38f, 0f})
			cases.add(BuiltinKernel.withOuts("frexp", "float", types("float", "int"), new Object[]{x, null},
				List.of("float", "int"), a -> glslFrexp(((float[]) a[0])[0]), 0f));
		assertNoFailures(cases);
	}

	/**
	 * frexp of a subnormal (GLSL 4.60 8.3 defines it for every finite x): precise math must return the exact split. Fast
	 * math runs too and its words are printed: whether it flushes the subnormal is recorded, not asserted.
	 */
	@Test
	void frexpOfASubnormalUnderPreciseMath() {
		Case c = BuiltinKernel.withOuts("frexp", "float", types("float", "int"), new Object[]{1e-40f, null},
			List.of("float", "int"), a -> glslFrexp(((float[]) a[0])[0]), 0f);
		BuiltinKernel.Outcome precise = BuiltinKernel.run(c, dev.mcms.shaders.metal.ShaderNative.MATH_SAFE);
		assertNull(precise.mismatch(), precise.mismatch());
		BuiltinKernel.Outcome fast = BuiltinKernel.run(c, dev.mcms.shaders.metal.ShaderNative.MATH_FAST);
		System.out.println("frexp(1e-40) under fast math: words " + java.util.Arrays.toString(fast.got())
			+ ", mismatch " + fast.mismatch());
	}

	static void assertNoFailures(List<Case> cases) {
		List<String> failures = BuiltinKernel.verify(cases);
		assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
	}
}
