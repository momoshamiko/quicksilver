// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal.translator;

import static dev.mcms.shaders.metal.translator.TranslateSupport.compute;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mcms.glsl.msl.ProgramRequest.BufferBinding;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.shaders.metal.HeadlessGpu;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * GLSL 4.60 semantics, run on the GPU: each kernel writes words into one std430 buffer, and the test compares them with
 * the values the specification defines. Floats are compared by their bits.
 */
@Tag("gpu")
final class ComputeSemanticsGpuTest {

	private static final int WORDS = 16;

	/** The default buffer: an array of unsigned words named {@code o}. */
	private static final String OUT = "buffer Out { uint o[]; };";

	private static int bits(float f) {
		return Float.floatToRawIntBits(f);
	}

	/**
	 * Runs the kernel with {@code localSize} invocations. {@code decls} holds the buffer and any globals or functions;
	 * returns the first {@link #WORDS} words of the buffer.
	 */
	private static int[] run(String decls, String body, int localSize) {
		String glsl = "#version 430\nlayout(local_size_x = " + localSize + ") in;\n" + decls + "\nvoid main() {\n"
			+ body + "\n}\n";
		ProgramTranslation t = TranslateSupport.translateOk(compute(glsl, List.of(new BufferBinding("Out", 0)),
			List.of(), List.of(), List.of()));
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			byte[] out = gpu.runKernel(t.msl(), t.computeEntry(), null, new byte[0][], new int[] {WORDS * 4}, 1, 1,
				1, localSize, 1, 1)[0];
			ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
			int[] words = new int[WORDS];
			for (int i = 0; i < WORDS; i++) {
				words[i] = bb.getInt(i * 4);
			}
			return words;
		}
	}

	private static int[] run(String body) {
		return run(OUT, body, 1);
	}

	@Test
	void conversionsTruncateTowardZeroAndWrap() {
		int[] r = run("""
			int i = -7;
			float f = -2.75;
			o[0] = uint(int(f));
			o[1] = uint(bool(i));
			o[2] = uint(i);
			o[3] = floatBitsToUint(float(i));
			o[4] = uint(3.9);
			o[5] = uint(i / 2);
			o[6] = uint(int(-0.5));
			o[7] = uint(float(4000000000u) > 3.9e9);
			""");
		assertEquals(-2, r[0]);
		assertEquals(1, r[1]);
		assertEquals(-7, r[2]);
		assertEquals(bits(-7.0f), r[3]);
		assertEquals(3, r[4]);
		assertEquals(-3, r[5]);
		assertEquals(0, r[6]);
		assertEquals(1, r[7]);
	}

	@Test
	void constructorsTruncateAndFillInGlslOrder() {
		int[] r = run("""
			vec4 v4 = vec4(1.0, 2.0, 3.0, 4.0);
			o[0] = floatBitsToUint(vec2(v4).y);
			mat3 m3 = mat3(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0);
			mat2 m2 = mat2(m3);
			o[1] = floatBitsToUint(m2[1][1]);
			mat3 d = mat3(2.0);
			o[2] = floatBitsToUint(d[1][1]);
			o[3] = floatBitsToUint(d[0][1]);
			vec4 mx = vec4(vec2(1.0, 2.0), 3.0, 4.0);
			o[4] = floatBitsToUint(mx.z);
			mat3 fc = mat3(vec3(1.0, 0.0, 0.0), vec3(0.0, 2.0, 0.0), vec3(0.0, 0.0, 3.0));
			o[5] = floatBitsToUint(fc[2][2]);
			mat2 ms = mat2(1.0, 2.0, 3.0, 4.0);
			o[6] = floatBitsToUint(ms[0][1]);
			mat3 m4 = mat3(mat4(5.0));
			o[7] = floatBitsToUint(m4[2][2]);
			ivec2 iv = ivec2(vec2(2.9, 3.1));
			o[8] = uint(iv.y);
			vec3 v3 = vec3(v4);
			o[9] = floatBitsToUint(v3.z);
			""");
		assertEquals(bits(2.0f), r[0]);
		assertEquals(bits(5.0f), r[1]);
		assertEquals(bits(2.0f), r[2]);
		assertEquals(bits(0.0f), r[3]);
		assertEquals(bits(3.0f), r[4]);
		assertEquals(bits(3.0f), r[5]);
		assertEquals(bits(2.0f), r[6]);
		assertEquals(bits(5.0f), r[7]);
		assertEquals(3, r[8]);
		assertEquals(bits(3.0f), r[9]);
	}

	@Test
	void swizzleWritesStoreEachComponent() {
		int[] r = run("""
			vec4 v = vec4(1.0, 2.0, 3.0, 4.0);
			v.zx = vec2(9.0, 8.0);
			v.wyx = vec3(5.0, 6.0, 7.0);
			o[0] = floatBitsToUint(v.x);
			o[1] = floatBitsToUint(v.y);
			o[2] = floatBitsToUint(v.z);
			o[3] = floatBitsToUint(v.w);
			""");
		assertEquals(bits(7.0f), r[0]);
		assertEquals(bits(6.0f), r[1]);
		assertEquals(bits(9.0f), r[2]);
		assertEquals(bits(5.0f), r[3]);
	}

	@Test
	void vectorComparisonsReduceWithAllAndAny() {
		int[] r = run("""
			vec3 a = vec3(1.0, 2.0, 3.0);
			vec3 b = vec3(1.0, 2.0, 4.0);
			o[0] = uint(a == b);
			o[1] = uint(a != b);
			o[2] = uint(any(lessThan(a, b)));
			o[3] = uint(all(lessThan(a, b)));
			o[4] = uint(all(equal(a.xy, b.xy)));
			ivec2 p = ivec2(1, 2);
			o[5] = uint(p == ivec2(1, 2));
			o[6] = uint(all(greaterThanEqual(a.xy, vec2(1.0, 2.0))));
			""");
		assertEquals(0, r[0]);
		assertEquals(1, r[1]);
		assertEquals(1, r[2]);
		assertEquals(0, r[3]);
		assertEquals(1, r[4]);
		assertEquals(1, r[5]);
		assertEquals(1, r[6]);
	}

	@Test
	void matrixProductsTransposeAndDeterminant() {
		int[] r = run("""
			mat2 m = mat2(1.0, 2.0, 3.0, 4.0);
			vec2 r1 = m * vec2(1.0, 1.0);
			vec2 r2 = vec2(1.0, 1.0) * m;
			mat2 p = m * m;
			mat2 t = transpose(m);
			o[0] = floatBitsToUint(r1.y);
			o[1] = floatBitsToUint(r2.y);
			o[2] = floatBitsToUint(p[0].y);
			o[3] = floatBitsToUint(p[1].x);
			o[4] = floatBitsToUint(t[0].y);
			o[5] = floatBitsToUint(determinant(m));
			""");
		assertEquals(bits(6.0f), r[0]);
		assertEquals(bits(7.0f), r[1]);
		assertEquals(bits(10.0f), r[2]);
		assertEquals(bits(15.0f), r[3]);
		assertEquals(bits(3.0f), r[4]);
		assertEquals(bits(-2.0f), r[5]);
	}

	@Test
	void arraysAreCopiedByValue() {
		int[] r = run(OUT + """

			void touch(float x[3]) { x[1] = 100.0; }
			float total(float x[3]) { return x[0] + x[1] + x[2]; }
			""", """
			float a[3] = float[3](1.0, 2.0, 3.0);
			float b[3] = a;
			b[0] = 9.0;
			touch(a);
			o[0] = floatBitsToUint(a[0]);
			o[1] = floatBitsToUint(b[0]);
			o[2] = floatBitsToUint(a[1]);
			o[3] = floatBitsToUint(total(a));
			o[4] = uint(a.length());
			""", 1);
		assertEquals(bits(1.0f), r[0]);
		assertEquals(bits(9.0f), r[1]);
		assertEquals(bits(2.0f), r[2]);
		assertEquals(bits(6.0f), r[3]);
		assertEquals(3, r[4]);
	}

	@Test
	void outAndInoutArgumentsWriteSwizzlesElementsAndPackedMembers() {
		int[] r = run(OUT + """

			void split(out float x, out float y) { x = 1.0; y = 2.0; }
			void twice(inout float x) { x = x * 2.0; }
			""", """
			vec3 v = vec3(0.0, 5.0, 0.0);
			split(v.x, v.z);
			twice(v.y);
			float ip = modf(3.25, v.y);
			int e[2] = int[2](0, 0);
			float m = frexp(8.0, e[1]);
			o[0] = floatBitsToUint(v.x);
			o[1] = floatBitsToUint(v.y);
			o[2] = floatBitsToUint(v.z);
			o[3] = floatBitsToUint(ip);
			o[4] = floatBitsToUint(m);
			o[5] = uint(e[1]);
			""", 1);
		assertEquals(bits(1.0f), r[0]);
		assertEquals(bits(3.0f), r[1]);
		assertEquals(bits(2.0f), r[2]);
		assertEquals(bits(0.25f), r[3]);
		assertEquals(bits(0.5f), r[4]);
		assertEquals(4, r[5]);
	}

	@Test
	void globalsRunTheirInitializersInOrder() {
		int[] r = run(OUT + """

			int g = 5;
			int h = g * 2;
			const float K = 6.25;
			float arr[2] = float[2](1.0, 2.0);
			void bump() { g = g + 1; }
			""", """
			bump();
			o[0] = uint(g);
			o[1] = uint(h);
			o[2] = floatBitsToUint(K);
			o[3] = floatBitsToUint(arr[1]);
			""", 1);
		assertEquals(6, r[0]);
		assertEquals(10, r[1]);
		assertEquals(bits(6.25f), r[2]);
		assertEquals(bits(2.0f), r[3]);
	}

	@Test
	void switchFallsThroughUntilBreak() {
		int[] r = run("""
			int r = 0;
			int k = 1;
			switch (k) {
				case 0: r += 100;
				case 1: r += 1;
				case 2: r += 10; break;
				default: r += 1000;
			}
			o[0] = uint(r);
			int q = 3;
			switch (q) {
				default: q = 9; break;
				case 3: q = 4;
			}
			o[1] = uint(q);
			""");
		assertEquals(11, r[0]);
		assertEquals(4, r[1]);
	}

	@Test
	void loopsHonourBreakContinueAndDoWhile() {
		int[] r = run("""
			int s = 0;
			for (int i = 0; i < 10; i++) {
				if (i == 2) continue;
				if (i == 5) break;
				s += i;
			}
			o[0] = uint(s);
			int w = 0;
			int j = 0;
			while (true) {
				j++;
				if (j > 3) break;
				w += j;
			}
			o[1] = uint(w);
			int d = 0;
			do {
				d++;
			} while (d < 8);
			o[2] = uint(d);
			""");
		assertEquals(8, r[0]);
		assertEquals(6, r[1]);
		assertEquals(8, r[2]);
	}

	@Test
	void notEqualToItselfIsNanAtRunTime() {
		int[] r = run("""
			float n = intBitsToFloat(0x7fc00000);
			float z = 2.0;
			o[0] = uint(n != n);
			o[1] = uint(z != z);
			""");
		assertEquals(1, r[0]);
		assertEquals(0, r[1]);
	}

	@Test
	void integerAndBitOperations() {
		int[] r = run("""
			uint u = 0xF0F0u;
			o[0] = uint(bitCount(u));
			o[1] = uint(findLSB(u));
			o[2] = uint(findMSB(u));
			o[3] = uint(bitfieldExtract(int(-16), 2, 3));
			o[4] = bitfieldInsert(0u, 0xFu, 4, 4);
			o[5] = uint(-8) >> 1u;
			o[6] = uint(int(-8) >> 1);
			o[7] = 0xFFFFFFFFu + 2u;
			o[8] = uint(~0);
			o[9] = uint(7 & 12);
			o[10] = uint(7 ^ 2);
			o[11] = 1u << 31u;
			o[12] = uint(-7 / 2);
			o[13] = 5u % 3u;
			""");
		assertEquals(8, r[0]);
		assertEquals(4, r[1]);
		assertEquals(15, r[2]);
		assertEquals(-4, r[3]);
		assertEquals(0xF0, r[4]);
		assertEquals(0x7FFFFFFC, r[5]);
		assertEquals(-4, r[6]);
		assertEquals(1, r[7]);
		assertEquals(-1, r[8]);
		assertEquals(4, r[9]);
		assertEquals(5, r[10]);
		assertEquals(0x80000000, r[11]);
		assertEquals(-3, r[12]);
		assertEquals(2, r[13]);
	}

	@Test
	void atomicsOnSharedVariablesAndBufferMembers() {
		// Words: cnt (0), vals[0..3] (1..4), then o[] from word 5. Each of 64 invocations adds 1 to total and to a
		// shared bin, 2 to cnt and 1 to a buffer bin.
		int[] r = run("buffer Out { uint cnt; uint vals[4]; uint o[]; };\nshared uint total;\nshared uint hist[4];",
			"""
			if (gl_LocalInvocationIndex == 0u) {
				total = 0u;
				hist[0] = 0u;
				hist[1] = 0u;
				hist[2] = 0u;
				hist[3] = 0u;
			}
			barrier();
			atomicAdd(total, 1u);
			atomicAdd(hist[gl_LocalInvocationIndex % 4u], 1u);
			atomicAdd(cnt, 2u);
			atomicAdd(vals[gl_LocalInvocationIndex % 4u], 1u);
			barrier();
			if (gl_LocalInvocationIndex == 0u) {
				o[0] = total;
				o[1] = hist[0];
				o[2] = hist[3];
				o[3] = atomicAdd(cnt, 0u);
			}
			""", 64);
		assertEquals(128, r[0]);
		assertEquals(16, r[1]);
		assertEquals(16, r[4]);
		assertEquals(64, r[5]);
		assertEquals(16, r[6]);
		assertEquals(16, r[7]);
		assertEquals(128, r[8]);
	}

	@Test
	void std430VectorMembersKeepTheirOffsets() {
		int[] r = run("buffer Out { vec3 v; float f; vec3 w; uint o[]; };", """
			v = vec3(1.0, 2.0, 3.0);
			f = 4.0;
			w = vec3(5.0, 6.0, 7.0);
			""", 1);
		assertEquals(bits(1.0f), r[0]);
		assertEquals(bits(2.0f), r[1]);
		assertEquals(bits(3.0f), r[2]);
		assertEquals(bits(4.0f), r[3]);
		assertEquals(bits(5.0f), r[4]);
		assertEquals(bits(6.0f), r[5]);
		assertEquals(bits(7.0f), r[6]);
	}
}
