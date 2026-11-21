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
}
