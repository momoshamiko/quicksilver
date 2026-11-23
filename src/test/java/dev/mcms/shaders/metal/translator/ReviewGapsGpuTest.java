// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal.translator;

import static dev.mcms.shaders.metal.translator.TranslateSupport.compute;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.BufferBinding;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.sema.Stage;
import dev.mcms.shaders.metal.HeadlessGpu;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Valid GLSL that the corpus does not reach (E2 review item 2): each program's values are the ones the GLSL
 * specifications define, read back from the GPU. Kernels write words into one buffer block named {@code Out}; floats
 * are compared by their bits.
 */
@Tag("gpu")
final class ReviewGapsGpuTest {

	private static final int WORDS = 16;
	private static final String OUT = "buffer Out { uint o[]; } ob;";

	private static int bits(float f) {
		return Float.floatToRawIntBits(f);
	}

	/** Runs one kernel (local size 1) whose declarations include the {@code Out} block; returns its first words. */
	private static int[] kernel(String decls, String body) {
		String glsl = "#version 430\nlayout(local_size_x = 1) in;\n" + decls + "\nvoid main() {\n" + body + "\n}\n";
		ProgramTranslation t = TranslateSupport.translateOk(compute(glsl, List.of(new BufferBinding("Out", 0)),
			List.of(), List.of(), List.of()));
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			byte[] out = gpu.runKernel(t.msl(), t.computeEntry(), null, new byte[0][], new int[] {WORDS * 4}, 1, 1, 1,
				1, 1, 1)[0];
			ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
			int[] words = new int[WORDS];
			for (int i = 0; i < WORDS; i++) {
				words[i] = bb.getInt(i * 4);
			}
			return words;
		}
	}

	@Test
	void namedBlockInstanceReachesItsMembersThroughTheInstance() {
		int[] r = kernel("buffer Out { uint o[]; } ob;", """
			ob.o[0] = 7u;
			ob.o[1] = 9u;
			""");
		assertEquals(7, r[0]);
		assertEquals(9, r[1]);
	}

	@Test
	void structArrayInStd430BlockUsesTheStd430Layout() {
		// std430: vec3 has alignment 16, so S is 16 bytes (a at 0, b at 12) and s[1] starts at byte 16.
		int[] r = kernel("struct S { vec3 a; float b; };\nbuffer Out { S s[2]; uint tail; } ob;", """
			ob.s[0].a = vec3(1.0, 2.0, 3.0);
			ob.s[0].b = 4.0;
			ob.s[1].a = vec3(5.0, 6.0, 7.0);
			ob.s[1].b = 8.0;
			ob.tail = 9u;
			""");
		for (int i = 0; i < 8; i++) {
			assertEquals(bits(i + 1.0f), r[i], "word " + i);
		}
		assertEquals(9, r[8]);
	}

	@Test
	void structArrayInStd140BlockPadsEachElementToAVec4() {
		// std140: an array element is rounded to 16 bytes, so p[1] starts at byte 16 and tail at byte 32.
		int[] r = kernel("struct P { float f; };\nlayout(std140) buffer Out { P p[2]; uint tail; } ob;", """
			ob.p[0].f = 1.5;
			ob.p[1].f = 2.5;
			ob.tail = 9u;
			""");
		assertEquals(bits(1.5f), r[0]);
		assertEquals(bits(2.5f), r[4]);
		assertEquals(9, r[8]);
	}

	@Test
	void atomicsOnAnUnsizedBufferArray() {
		int[] r = kernel(OUT, """
			atomicAdd(ob.o[0], 5u);
			ob.o[1] = atomicAdd(ob.o[0], 2u);
			""");
		assertEquals(7, r[0]);
		assertEquals(5, r[1]);
	}

	@Test
	void matrixTimesAssignSquaresTheMatrix() {
		// M = [1 3; 2 4] (columns (1,2) and (3,4)); M*M = [7 15; 10 22].
		int[] r = kernel(OUT, """
			mat2 m = mat2(1.0, 2.0, 3.0, 4.0);
			m *= m;
			ob.o[0] = floatBitsToUint(m[0][0]);
			ob.o[1] = floatBitsToUint(m[0][1]);
			ob.o[2] = floatBitsToUint(m[1][0]);
			ob.o[3] = floatBitsToUint(m[1][1]);
			""");
		assertEquals(bits(7.0f), r[0]);
		assertEquals(bits(10.0f), r[1]);
		assertEquals(bits(15.0f), r[2]);
		assertEquals(bits(22.0f), r[3]);
	}

	@Test
	void floatOfAVectorTakesItsFirstComponent() {
		int[] r = kernel(OUT, """
			ob.o[0] = floatBitsToUint(float(vec3(2.5, 3.0, 4.0)));
			""");
		assertEquals(bits(2.5f), r[0]);
	}

	@Test
	void mixedScalarAndVectorConstructorTakesOnlyTheComponentsItNeeds() {
		int[] r = kernel(OUT, """
			vec3 t = vec3(1.0, vec4(2.0, 3.0, 4.0, 5.0));
			ob.o[0] = floatBitsToUint(t.x);
			ob.o[1] = floatBitsToUint(t.y);
			ob.o[2] = floatBitsToUint(t.z);
			""");
		assertEquals(bits(1.0f), r[0]);
		assertEquals(bits(2.0f), r[1]);
		assertEquals(bits(3.0f), r[2]);
	}

	@Test
	void compoundAssignmentToAnIndexedSwizzleEvaluatesTheIndexOnce() {
		int[] r = kernel(OUT + "\nint n = 0;\nint pick() {\n\tn = n + 1;\n\treturn 1;\n}", """
			vec2 arr[2] = vec2[2](vec2(1.0, 2.0), vec2(3.0, 4.0));
			arr[pick()].xy *= 2.0;
			ob.o[0] = floatBitsToUint(arr[1].x);
			ob.o[1] = floatBitsToUint(arr[1].y);
			ob.o[2] = uint(n);
			""");
		assertEquals(bits(6.0f), r[0]);
		assertEquals(bits(8.0f), r[1]);
		assertEquals(1, r[2]);
	}

	private static ProgramTranslation fullscreen(String vs, String fs, int components) {
		return TranslateSupport.translateOk(new ProgramRequest("review-gaps", ProgramRequest.Family.FULL_SCREEN,
			List.of(TranslateSupport.stage(Stage.VERTEX, vs), TranslateSupport.stage(Stage.FRAGMENT, fs)),
			ProgramRequest.VertexLayout.fullScreenQuad(), List.of(new ColorTarget(0, 0, components)), List.of(),
			List.of(), List.of(), ProgramRequest.UniformSchema.none(), null, false));
	}

	/** The bytes of target 0 in a 4 by 4 render with the given pixel format. */
	private static byte[] render(ProgramTranslation t, int format) {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			return gpu.renderFullscreen(t.msl(), t.vertexEntry(), t.fragmentEntry(), new int[] {format}, 4, 4, null,
				new byte[0], new byte[0][])[0];
		}
	}

	@Test
	void integerFragmentOutputKeepsItsBitsInAnIntegerTarget() {
		ProgramTranslation t = fullscreen("""
			#version 130
			void main() {
				gl_Position = ftransform();
			}
			""", """
			#version 130
			out uvec4 c;
			void main() {
				c = uvec4(1u, 2u, 3u, 4000000000u);
			}
			""", 4);
		ByteBuffer bb = ByteBuffer.wrap(render(t, HeadlessGpu.RGBA32_UINT)).order(ByteOrder.LITTLE_ENDIAN);
		assertEquals(1L, Integer.toUnsignedLong(bb.getInt(0)), t.msl());
		assertEquals(2L, Integer.toUnsignedLong(bb.getInt(4)), t.msl());
		assertEquals(3L, Integer.toUnsignedLong(bb.getInt(8)), t.msl());
		assertEquals(4000000000L, Integer.toUnsignedLong(bb.getInt(12)), t.msl());
	}

	@Test
	void arrayVaryingsPassEachElementToTheFragmentStage() {
		ProgramTranslation t = fullscreen("""
			#version 130
			out vec4 vals[2];
			void main() {
				gl_Position = ftransform();
				vals[0] = vec4(0.25, 0.0, 0.0, 1.0);
				vals[1] = vec4(0.0, 0.5, 0.0, 1.0);
			}
			""", """
			#version 130
			in vec4 vals[2];
			void main() {
				gl_FragData[0] = vals[0] + vals[1];
			}
			""", 4);
		ByteBuffer bb = ByteBuffer.wrap(render(t, HeadlessGpu.RGBA32_FLOAT)).order(ByteOrder.LITTLE_ENDIAN);
		assertEquals(0.25f, bb.getFloat(0), 1e-6f, t.msl());
		assertEquals(0.5f, bb.getFloat(4), 1e-6f, t.msl());
		assertEquals(0.0f, bb.getFloat(8), 1e-6f, t.msl());
		assertEquals(2.0f, bb.getFloat(12), 1e-6f, t.msl());
	}

	@Test
	void outArgumentUnderACaseLabelIsCopiedInsideItsOwnBlock() {
		int[] r = kernel(OUT + "\nvoid bump(inout float x) {\n\tx = x + 1.0;\n}", """
			vec2 g = vec2(0.0);
			int k = 1;
			switch (k) {
			case 0:
				bump(g.x);
				break;
			case 1:
				bump(g.y);
				break;
			}
			ob.o[0] = floatBitsToUint(g.x);
			ob.o[1] = floatBitsToUint(g.y);
			""");
		assertEquals(bits(0.0f), r[0]);
		assertEquals(bits(1.0f), r[1]);
	}

	@Test
	void matrixToVectorConstructorEvaluatesSideEffectOnce() {
		int[] r = kernel(OUT + """

			uint calls = 0u;
			const vec4 CONST_FROM_MAT = vec4(mat2(1.0, 2.0, 3.0, 4.0));
			mat2 nextMat2() {
				calls++;
				float b = float(calls) * 10.0;
				return mat2(b + 1.0, b + 2.0, b + 3.0, b + 4.0);
			}
			mat3 nextMat3() {
				calls++;
				float b = float(calls) * 10.0;
				return mat3(b + 1.0, b + 2.0, b + 3.0, b + 4.0, b + 5.0, b + 6.0, b + 7.0, b + 8.0, b + 9.0);
			}""", """
			vec4 a = vec4(nextMat2());
			vec3 b = vec3(nextMat2());
			ivec4 c = ivec4(nextMat2());
			vec4 d = vec4(nextMat3());
			vec4 skipped = calls == 0u ? vec4(nextMat2()) : vec4(99.0);
			ob.o[0] = calls;
			ob.o[1] = floatBitsToUint(a.x);
			ob.o[2] = floatBitsToUint(a.y);
			ob.o[3] = floatBitsToUint(a.z);
			ob.o[4] = floatBitsToUint(a.w);
			ob.o[5] = floatBitsToUint(b.x);
			ob.o[6] = floatBitsToUint(b.y);
			ob.o[7] = floatBitsToUint(b.z);
			ob.o[8] = uint(c.x);
			ob.o[9] = uint(c.w);
			ob.o[10] = floatBitsToUint(d.x);
			ob.o[11] = floatBitsToUint(d.w);
			ob.o[12] = floatBitsToUint(skipped.x);
			ob.o[13] = floatBitsToUint(CONST_FROM_MAT.w);
			""");
		assertEquals(4, r[0], "each matrix-to-vector constructor must call its argument once and respect short-circuiting");
		assertEquals(bits(11.0f), r[1]);
		assertEquals(bits(12.0f), r[2]);
		assertEquals(bits(13.0f), r[3]);
		assertEquals(bits(14.0f), r[4]);
		assertEquals(bits(21.0f), r[5]);
		assertEquals(bits(22.0f), r[6]);
		assertEquals(bits(23.0f), r[7]);
		assertEquals(31, r[8]);
		assertEquals(34, r[9]);
		assertEquals(bits(41.0f), r[10]);
		assertEquals(bits(44.0f), r[11]);
		assertEquals(bits(99.0f), r[12]);
		assertEquals(bits(4.0f), r[13]);
	}

	@Test
	void functionReturningSizedArrayExecutesOnGpu() {
		int[] r = kernel(OUT + """

			float[4] makeWeights(float base) {
				return float[4](base, base + 1.0, base + 2.0, base + 3.0);
			}
			vec3[2] makePairs(vec3 v) {
				vec3 arr[2] = vec3[2](v, v * 2.0);
				return arr;
			}""", """
			float w[4] = makeWeights(10.0);
			float direct = makeWeights(20.0)[2];
			vec3 p = makePairs(vec3(1.0, 2.0, 3.0))[1];
			ob.o[0] = floatBitsToUint(w[0]);
			ob.o[1] = floatBitsToUint(w[3]);
			ob.o[2] = floatBitsToUint(direct);
			ob.o[3] = floatBitsToUint(p.x);
			ob.o[4] = floatBitsToUint(p.y);
			ob.o[5] = floatBitsToUint(p.z);
			""");
		assertEquals(bits(10.0f), r[0]);
		assertEquals(bits(13.0f), r[1]);
		assertEquals(bits(22.0f), r[2]);
		assertEquals(bits(2.0f), r[3]);
		assertEquals(bits(4.0f), r[4]);
		assertEquals(bits(6.0f), r[5]);
	}

	@Test
	void scalarSwizzleAndAtomicInoutOverloadExecuteOnGpu() {
		int[] r = kernel(OUT + """

			shared uint flag;
			uint calls = 0u;
			const float K = 3.5;
			float nextVal() {
				calls++;
				return float(calls) * 5.0;
			}""", """
			float a = nextVal().x;
			vec3 b = nextVal().rrr;
			float c = 1.0;
			c.s = 7.5;
			vec2 ck = K.xx;
			flag = 9u;
			uint prev = atomicExchange(flag, 0);
			flag.x = 4u;
			uint readBack = flag.x;
			uvec2 dup = flag.xx;
			ob.o[0] = calls;
			ob.o[1] = floatBitsToUint(a);
			ob.o[2] = floatBitsToUint(b.x);
			ob.o[3] = floatBitsToUint(b.z);
			ob.o[4] = floatBitsToUint(c);
			ob.o[5] = floatBitsToUint(ck.y);
			ob.o[6] = prev;
			ob.o[7] = readBack;
			ob.o[8] = dup.y;
			""");
		assertEquals(2, r[0], "scalar swizzle broadcast must evaluate its argument once");
		assertEquals(bits(5.0f), r[1]);
		assertEquals(bits(10.0f), r[2]);
		assertEquals(bits(10.0f), r[3]);
		assertEquals(bits(7.5f), r[4]);
		assertEquals(bits(3.5f), r[5]);
		assertEquals(9, r[6]);
		assertEquals(4, r[7]);
		assertEquals(4, r[8]);
	}

	@Test
	void synthesizedAtTangentCompilesAndRendersFiniteOnGpu() {
		String vs = """
			#version 120
			attribute vec4 at_tangent;
			varying vec4 vT;
			void main() {
				vec3 t = normalize(at_tangent.xyz);
				vec3 b = normalize(cross(t, gl_Normal.xyz + vec3(0.0, 1.0, 0.0)) * at_tangent.w);
				vT = vec4(t, length(b));
				gl_Position = ftransform();
			}
			""";
		String fs = """
			#version 120
			varying vec4 vT;
			void main() {
				gl_FragData[0] = vT;
			}
			""";
		ProgramTranslation full = fullscreen(vs, fs, 4);
		ByteBuffer bb = ByteBuffer.wrap(render(full, HeadlessGpu.RGBA32_FLOAT)).order(ByteOrder.LITTLE_ENDIAN);
		assertEquals(1.0f, bb.getFloat(0), 1e-5f, full.msl());
		assertEquals(0.0f, bb.getFloat(4), 1e-5f, full.msl());
		assertEquals(0.0f, bb.getFloat(8), 1e-5f, full.msl());
		assertEquals(1.0f, bb.getFloat(12), 1e-5f, full.msl());

		String entityVs = """
			#version 120
			attribute vec4 at_tangent;
			varying vec4 vT;
			void main() {
				vec3 t = normalize(at_tangent.xyz);
				vec3 b = normalize(cross(t, gl_Normal.xyz) * at_tangent.w);
				vT = vec4(t, length(b));
				gl_Position = gl_Vertex;
			}
			""";
		ProgramRequest.VertexLayout entityLayout = new ProgramRequest.VertexLayout(
			List.of(
				new ProgramRequest.VertexAttribute(ProgramRequest.Semantic.POSITION, "float3", 0, 0),
				new ProgramRequest.VertexAttribute(ProgramRequest.Semantic.NORMAL, "char3Normalized", 0, 12)),
			false);
		ProgramTranslation entity = TranslateSupport.translateOk(new ProgramRequest("entity-tangent-gpu",
			ProgramRequest.Family.GBUFFERS,
			List.of(TranslateSupport.stage(Stage.VERTEX, entityVs), TranslateSupport.stage(Stage.FRAGMENT, fs)),
			entityLayout, List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(),
			ProgramRequest.UniformSchema.none(), null, false));
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			HeadlessGpu.CompileResult compiled = gpu.compile(entity.msl());
			assertEquals(true, compiled.ok(), "entity tangent MSL failed Metal compile: " + compiled.diagnostics() + "\n" + entity.msl());
		}
	}
}
