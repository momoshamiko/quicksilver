// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest.AlphaTest;
import dev.mcms.glsl.msl.ProgramRequest.BufferBinding;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.ImageBinding;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Unit tests for Stage 2 GLSL-to-MSL compatibility and correctness fixes. */
final class GlslTranslatorStage2Test {

	private static final String VS_QUAD = """
		#version 430
		void main() {
			gl_Position = vec4(0.0);
		}
		""";

	private static List<SourceLine> lines(String text) {
		return IntStream.rangeClosed(1, text.split("\n", -1).length).mapToObj(i -> new SourceLine("test", i)).toList();
	}

	private static ProgramTranslation translateFragment(String fs, AlphaTest alphaTest) {
		List<ProgramRequest.StageSource> stages = List.of(
			new ProgramRequest.StageSource(Stage.VERTEX, VS_QUAD, lines(VS_QUAD)),
			new ProgramRequest.StageSource(Stage.FRAGMENT, fs, lines(fs)));
		return Translator.translate(new ProgramRequest("stage2", ProgramRequest.Family.FULL_SCREEN,
			stages, ProgramRequest.VertexLayout.fullScreenQuad(), List.of(new ColorTarget(0, 0, 4)), List.of(),
			List.of(), List.of(), UniformSchema.none(), alphaTest, false));
	}

	private static ProgramTranslation translateCompute(String cs, List<BufferBinding> buffers, List<ImageBinding> images) {
		return Translator.translate(new ProgramRequest("stage2_cs", ProgramRequest.Family.COMPUTE,
			List.of(new ProgramRequest.StageSource(Stage.COMPUTE, cs, lines(cs))), null, List.of(), List.of(),
			images, buffers, UniformSchema.none(), null, false));
	}

	@Test
	void braceInitializersForVectorsMatricesAndConstArraysHoistToConstantMemory() {
		String fs = """
			#version 430
			const float LUT[4] = { 0.1, 0.2, 0.3, 0.4 };
			out vec4 fragColor;
			void main() {
				vec3 v = { 1.0, 2.0, 3.0 };
				mat2 m = { { 1.0, 0.0 }, { 0.0, 1.0 } };
				fragColor = vec4(m * v.xy, LUT[2], 1.0);
			}
			""";
		ProgramTranslation t = translateFragment(fs, null);
		assertTrue(t.ok(), "translation failed: " + t.diagnostics());
		assertTrue(t.msl().contains("constant array<float, 4>"), "const array should hoist to constant memory:\n" + t.msl());
		assertTrue(t.msl().contains("float3(1.0f, 2.0f, 3.0f)"), "vector brace init:\n" + t.msl());
		assertTrue(t.msl().contains("float2x2(float2(1.0f, 0.0f), float2(0.0f, 1.0f))"), "matrix brace init:\n" + t.msl());
	}

	@Test
	void sequenceExpressionPreservesNonConstantSideEffects() {
		String fs = """
			#version 430
			out vec4 fragColor;
			void main() {
				float a = 0.0;
				float b = (a = 2.0, 1.0);
				fragColor = vec4(a, b, 0.0, 1.0);
			}
			""";
		ProgramTranslation t = translateFragment(fs, null);
		assertTrue(t.ok(), "translation failed: " + t.diagnostics());
		assertTrue(t.msl().contains("= 2.0f"), "side effect in comma expression must not be dropped:\n" + t.msl());
	}

	@Test
	void outerProductReturnsColumnsFromSecondArgAndRowsFromFirst() {
		String fs = """
			#version 430
			out vec4 fragColor;
			void main() {
				mat2x3 m = outerProduct(vec3(1.0, 2.0, 3.0), vec2(4.0, 5.0));
				fragColor = vec4(m[1], 1.0);
			}
			""";
		ProgramTranslation t = translateFragment(fs, null);
		assertTrue(t.ok(), "outerProduct(vec3, vec2) must return mat2x3: " + t.diagnostics());
	}

	@Test
	void atomicAndImageCompSwapRetrySpuriousWeakFailures() {
		String cs = """
			#version 430
			layout(local_size_x = 1) in;
			layout(std430, binding = 0) buffer Buf { uint val; };
			layout(r32ui) uniform uimage2D img;
			void main() {
				uint a = atomicCompSwap(val, 0u, 1u);
				uint b = imageAtomicCompSwap(img, ivec2(0), 0u, a);
				val = b;
			}
			""";
		ProgramTranslation t = translateCompute(cs, List.of(new BufferBinding("Buf", 0, 0)),
			List.of(new ImageBinding("img", 0)));
		assertTrue(t.ok(), "translation failed: " + t.diagnostics());
		assertTrue(t.msl().contains("while (!atomic_compare_exchange_weak_explicit("), t.msl());
		assertTrue(t.msl().contains("if (c.x >= t.get_width() || c.y >= t.get_height()) return T(0);"), t.msl());
		assertTrue(t.msl().contains("while (!t.atomic_compare_exchange_weak("), t.msl());
	}

	@Test
	void pragmaInsideFunctionAndLoopParsesCleanly() {
		String fs = """
			#version 430
			out vec4 fragColor;
			void main() {
				float s = 0.0;
				#pragma unroll
				for (int i = 0; i < 4; i++) {
					#pragma optionNV(unroll all)
					s += float(i);
				}
				#pragma optimize(on)
				fragColor = vec4(s);
			}
			""";
		ProgramTranslation t = translateFragment(fs, null);
		assertTrue(t.ok(), "pragma inside function/loop failed: " + t.diagnostics());
	}

	@Test
	void unnamedFunctionParametersPreserveCallArity() {
		String fs = """
			#version 430
			out vec4 fragColor;
			float keepFirst(float a, int, float c) {
				return a + c;
			}
			void main() {
				fragColor = vec4(keepFirst(1.0, 42, 2.0));
			}
			""";
		ProgramTranslation t = translateFragment(fs, null);
		assertTrue(t.ok(), "unnamed parameter failed: " + t.diagnostics());
		assertTrue(t.msl().contains("int _qs_p1"), "unnamed parameter must emit placeholder:\n" + t.msl());
	}

	@Test
	void outArgumentOnBufferAndSharedVariableUsesTemporaryCopy() {
		String cs = """
			#version 430
			layout(local_size_x = 1) in;
			layout(std430, binding = 0) buffer Buf { float data[4]; };
			shared float sh[4];
			void inc(inout float x) { x += 1.0; }
			void main() {
				inc(data[0]);
				inc(sh[0]);
			}
			""";
		ProgramTranslation t = translateCompute(cs, List.of(new BufferBinding("Buf", 0, 0)), List.of());
		assertTrue(t.ok(), "translation failed: " + t.diagnostics());
		assertTrue(t.msl().contains("qs_t0"), "SSBO inout arg should copy via thread temp:\n" + t.msl());
		assertTrue(t.msl().contains("qs_t1"), "shared inout arg should copy via thread temp:\n" + t.msl());
	}

	@Test
	void constructorsHandleScalarFromVectorMatrixVectorFromMatrixAndTruncation() {
		String fs = """
			#version 430
			out vec4 fragColor;
			void main() {
				vec3 v3 = vec3(1.0, 2.0, 3.0);
				mat2 m2 = mat2(1.0, 2.0, 3.0, 4.0);
				float a = float(v3);
				float b = float(m2);
				vec4 c = vec4(m2);
				vec3 d = vec3(a, v3);
				fragColor = vec4(a + b + d.z, c.w, 0.0, 1.0);
			}
			""";
		ProgramTranslation t = translateFragment(fs, null);
		assertTrue(t.ok(), "constructor forms failed: " + t.diagnostics());
		assertTrue(t.msl().contains(".x)"), "float(vec3) should extract .x:\n" + t.msl());
		assertTrue(t.msl().contains("[0][0])"), "float(mat2) should extract [0][0]:\n" + t.msl());
		assertTrue(t.msl().contains(".xy"), "vec3(a, vec3) should truncate trailing vector to .xy:\n" + t.msl());
	}

	@Test
	void mixedSignVectorShiftsCastShiftAmountToLeftType() {
		String cs = """
			#version 430
			layout(local_size_x = 1) in;
			layout(std430, binding = 0) buffer Buf { ivec3 packedVec; };
			void main() {
				ivec4 a = ivec4(1, 2, 3, 4);
				uvec4 s = uvec4(1u, 2u, 1u, 0u);
				ivec4 b = a << s;
				a >>= s;
				packedVec <<= uvec3(1u, 2u, 1u);
				packedVec.x += b.x + a.x;
			}
			""";
		ProgramTranslation t = translateCompute(cs, List.of(new BufferBinding("Buf", 0, 0)), List.of());
		assertTrue(t.ok(), "mixed-sign shift failed: " + t.diagnostics());
		assertTrue(t.msl().contains("int4("), "uvec4 shift rhs must cast to int4:\n" + t.msl());
		assertTrue(t.msl().contains("int3("), "packed ivec3 compound shift rhs must cast to int3:\n" + t.msl());
	}

	@Test
	void fragmentMemoryBarrierEmitsNoOpAndGlAlwaysAlphaTestSucceeds() {
		String fs = """
			#version 430
			out vec4 fragColor;
			void main() {
				memoryBarrier();
				fragColor = vec4(1.0);
			}
			""";
		ProgramTranslation t = translateFragment(fs, new AlphaTest("GL_ALWAYS", "alphaTestRef"));
		assertTrue(t.ok(), "fragment memoryBarrier + GL_ALWAYS failed: " + t.diagnostics());
		assertFalse(t.msl().contains("threadgroup_barrier"), "fragment stage must not emit threadgroup_barrier:\n" + t.msl());
		assertTrue(t.msl().contains("((void)0)"), t.msl());
	}

	@Test
	void matrixToVectorConstructorEvaluatesMatrixArgumentOnce() {
		String fs = """
			#version 430
			out vec4 fragColor;
			int counter = 0;
			mat2 nextMat2() {
				counter++;
				float b = float(counter) * 10.0;
				return mat2(b + 1.0, b + 2.0, b + 3.0, b + 4.0);
			}
			mat3 nextMat3() {
				counter++;
				return mat3(1.0);
			}
			void main() {
				vec4 v4 = vec4(nextMat2());
				vec3 v3 = vec3(nextMat2());
				ivec4 iv4 = ivec4(nextMat2());
				vec4 from3 = vec4(nextMat3());
				vec4 mixed = vec4(0.5, nextMat2());
				vec4 cond = counter > 0 ? vec4(nextMat2()) : vec4(0.0);
				fragColor = v4 + vec4(v3, 0.0) + vec4(iv4) + from3 + mixed + cond;
			}
			""";
		ProgramTranslation t = translateFragment(fs, null);
		assertTrue(t.ok(), "matrix-to-vector translation failed: " + t.diagnostics());
		assertTrue(t.msl().contains("qs::matToVec<4>("), t.msl());
		assertTrue(t.msl().contains("qs::matToVec<3>("), t.msl());
		int mainIdx = t.msl().lastIndexOf("void u_main(");
		assertTrue(mainIdx >= 0, "expected fragment u_main method:\n" + t.msl());
		String mainBody = t.msl().substring(mainIdx);
		long mat2Calls = mainBody.split("nextMat2\\(", -1).length - 1;
		long mat3Calls = mainBody.split("nextMat3\\(", -1).length - 1;
		assertTrue(mat2Calls == 5, "expected exactly 5 nextMat2() calls, got " + mat2Calls + ":\n" + mainBody);
		assertTrue(mat3Calls == 1, "expected exactly 1 nextMat3() call, got " + mat3Calls + ":\n" + mainBody);
	}
}
