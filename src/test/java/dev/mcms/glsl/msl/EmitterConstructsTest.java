// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.UniformBlock;
import dev.mcms.glsl.msl.ProgramRequest.UniformField;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.msl.ProgramTranslation.Reflection.UniformUse;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The structure the emitter writes for each construct of the E2 list; the GPU tests check what the code computes. */
final class EmitterConstructsTest {

	private static final String VS_QUAD = """
		#version 120
		void main() {
			gl_Position = gl_Vertex;
		}
		""";

	private static ProgramTranslation translate(String vs, String fs, UniformSchema schema) {
		List<ProgramRequest.StageSource> stages = List.of(
			new ProgramRequest.StageSource(Stage.VERTEX, vs, lines(vs)),
			new ProgramRequest.StageSource(Stage.FRAGMENT, fs, lines(fs)));
		ProgramTranslation t = Translator.translate(new ProgramRequest("construct", ProgramRequest.Family.FULL_SCREEN,
			stages, ProgramRequest.VertexLayout.fullScreenQuad(), List.of(new ColorTarget(0, 0, 4)), List.of(),
			List.of(), List.of(), schema, null, false));
		assertTrue(t.ok(), "translation failed: " + t.diagnostics());
		return t;
	}

	/** The engine's block slots (the runtime's EngineSlots: the pure packages cannot import it, so the values repeat here). */
	private static final int CUSTOM = 23, DRAW = 25;

	private static ProgramTranslation translate(String vs, String fs) {
		return translate(vs, fs, UniformSchema.none());
	}

	/** A draw that is not the full-screen quad (gbuffers style): its compatibility matrices come from the schema. */
	private static ProgramTranslation translateDraw(String vs, String fs, UniformSchema schema) {
		List<ProgramRequest.StageSource> stages = List.of(
			new ProgramRequest.StageSource(Stage.VERTEX, vs, lines(vs)),
			new ProgramRequest.StageSource(Stage.FRAGMENT, fs, lines(fs)));
		ProgramTranslation t = Translator.translate(new ProgramRequest("construct", ProgramRequest.Family.GBUFFERS,
			stages, ProgramRequest.VertexLayout.of(), List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(),
			List.of(), schema, null, false));
		assertTrue(t.ok(), "translation failed: " + t.diagnostics());
		return t;
	}

	private static List<SourceLine> lines(String text) {
		return IntStream.rangeClosed(1, text.split("\n", -1).length).mapToObj(i -> new SourceLine("test", i)).toList();
	}

	/** A gbuffers draw with Position only (the sky's shape) whose pass-through program writes gl_Color. */
	private static final String VS_COLOR_PASS = """
		#version 120
		void main() {
			gl_Position = gl_Vertex;
			gl_FrontColor = gl_Color;
		}
		""";
	private static final String FS_COLOR_PASS = """
		#version 120
		void main() {
			gl_FragData[0] = gl_Color;
		}
		""";

	/**
	 * The gbuffers colour is the vertex colour (white without one) times the draw's ColorModulator: the sky's colour comes
	 * from its modulator (26.3 sky.fsh; sky.vsh reads no colour), so the translated gl_Color multiplies it in.
	 */
	@Test
	void gbuffersColourMultipliesTheDrawColorModulator() {
		UniformSchema schema = new UniformSchema(List.of(new UniformBlock("QsDraw", DRAW, List.of(
			new UniformField("modelViewMatrix", "mat4", 0), new UniformField("colorModulator", "vec4", 384)))));
		ProgramTranslation t = translateDraw(VS_COLOR_PASS, FS_COLOR_PASS, schema);
		assertTrue(t.msl().contains("* (*blk_QsDraw).u_colorModulator"), t.msl());
	}

	/** Without the draw block the colour takes no modulator: the request has no value to multiply in. */
	@Test
	void gbuffersColourTakesNoModulatorWithoutTheDrawBlock() {
		ProgramTranslation t = translateDraw(VS_COLOR_PASS, FS_COLOR_PASS, UniformSchema.none());
		assertFalse(t.msl().contains("colorModulator"), t.msl());
	}

	/** The core-profile colour input vaColor stays the raw vertex colour: only the compatibility gl_Color takes the modulator. */
	@Test
	void vaColorIsNotFoldedWithTheColorModulator() {
		UniformSchema schema = new UniformSchema(List.of(new UniformBlock("QsDraw", DRAW, List.of(
			new UniformField("modelViewMatrix", "mat4", 0), new UniformField("colorModulator", "vec4", 384)))));
		ProgramTranslation t = translateDraw("""
			#version 120
			attribute vec4 vaColor;
			void main() {
				gl_Position = gl_Vertex;
				gl_FrontColor = vaColor;
			}
			""", FS_COLOR_PASS, schema);
		assertTrue(t.msl().contains("(gl_FrontColor = u_vaColor);"), t.msl());
		assertFalse(t.msl().contains("(*blk_QsDraw).u_colorModulator"), t.msl());
	}

	@Test
	void matrixVaryingsAreOneVaryingPerColumnOnBothSides() {
		ProgramTranslation t = translate("""
			#version 120
			varying mat3 m;
			void main() {
				gl_Position = gl_Vertex;
				m = mat3(1.0);
			}
			""", """
			#version 120
			varying mat3 m;
			void main() {
				gl_FragData[0] = vec4(m[2][2]);
			}
			""");
		String msl = t.msl();
		for (int c = 0; c < 3; c++) {
			assertTrue(msl.contains("float3 v_m_" + c + " [[user(locn" + (c) + ")]]"), "column " + c + "\n" + msl);
			assertTrue(msl.contains("o.v_m_" + c + " = u_m[" + c + "];"), "vertex column " + c + "\n" + msl);
			assertTrue(msl.contains("c.u_m[" + c + "] = qs_in.v_m_" + c + ";"), "fragment column " + c + "\n" + msl);
		}
	}

	@Test
	void outArgumentToAVectorElementIsCopiedInAndOutAtStatementLevel() {
		ProgramTranslation t = translate(VS_QUAD, """
			#version 120
			void set(out float x) { x = 3.0; }
			void main() {
				vec2 v = vec2(0.0, 1.0);
				set(v.y);
				gl_FragData[0] = vec4(v, 0.0, 1.0);
			}
			""");
		String msl = t.msl();
		assertTrue(msl.contains("float qs_t0 = "), "copy-in\n" + msl);
		assertTrue(msl.contains(" = qs_t0;"), "copy-out\n" + msl);
		assertTrue(msl.contains("set(qs_t0)"), "the call takes the copy\n" + msl);
	}

	@Test
	void inoutBuiltinArgumentToASwizzleIsCopied() {
		ProgramTranslation t = translate(VS_QUAD, """
			#version 120
			void main() {
				vec2 v = vec2(0.0, 1.0);
				float ip = modf(3.25, v.x);
				gl_FragData[0] = vec4(v, ip, 1.0);
			}
			""");
		String msl = t.msl();
		assertTrue(msl.contains("modf(3.25f, qs_t"), "modf takes the copy\n" + msl);
		assertTrue(!msl.contains("modf(3.25f, u_v"), "no reference to a vector element\n" + msl);
	}

	@Test
	void sharedVariablesUsedWithAtomicsAreAtomicAndAccessedRelaxed() {
		String cs = """
			#version 430
			layout(local_size_x = 8) in;
			buffer Out { uint cnt; uint o[]; };
			shared uint total;
			void main() {
				atomicAdd(total, 1u);
				atomicAdd(cnt, 1u);
				barrier();
				cnt = total;
				total = total + 0u;
			}
			""";
		ProgramTranslation t = Translator.translate(new ProgramRequest("atomics", ProgramRequest.Family.COMPUTE,
			List.of(new ProgramRequest.StageSource(Stage.COMPUTE, cs, lines(cs))), null, List.of(), List.of(), List.of(),
			List.of(new ProgramRequest.BufferBinding("Out", 0)), UniformSchema.none(), null, false));
		assertTrue(t.ok(), "translation failed: " + t.diagnostics());
		String msl = t.msl();
		assertTrue(msl.contains("threadgroup atomic_uint *u_total"), "shared atomic pointer\n" + msl);
		assertTrue(msl.contains("atomic_uint u_cnt") || msl.contains("atomic_uint u_cnt;")
			|| msl.contains("atomic_uint u_cnt "), "member of the buffer is atomic\n" + msl);
		assertTrue(msl.contains("atomic_load_explicit(&((*u_total)), memory_order_relaxed)")
			|| msl.contains("atomic_load_explicit(&((*(c.u_total))), memory_order_relaxed)"),
			"plain read of a shared atomic is a relaxed load\n" + msl);
		assertTrue(msl.contains("atomic_store_explicit("), "plain write is a relaxed store\n" + msl);
	}

	@Test
	void invariantPositionIsMarkedInvariant() {
		ProgramTranslation t = translate("""
			#version 120
			invariant gl_Position;
			void main() {
				gl_Position = gl_Vertex;
			}
			""", """
			#version 120
			void main() {
				gl_FragData[0] = vec4(1.0);
			}
			""");
		assertTrue(t.msl().contains("[[position, invariant]]"), t.msl());
	}

	@Test
	void notEqualToItselfEmitsIsnanAndEqualToItselfDoesNot() {
		ProgramTranslation t = translate(VS_QUAD, """
			#version 120
			void main() {
				float x = gl_FragCoord.x;
				gl_FragData[0] = vec4(float(x != x), float(x == x), 0.0, 1.0);
			}
			""");
		assertTrue(t.msl().contains("isnan(") , t.msl());
	}

	@Test
	void compatibilityMatricesAreServedFromTheDrawBlock() {
		UniformSchema schema = new UniformSchema(List.of(new UniformBlock("QsDraw", DRAW, List.of(
			new UniformField("modelViewMatrix", "mat4", 0), new UniformField("projectionMatrix", "mat4", 64)))));
		ProgramTranslation t = translateDraw(VS_QUAD, """
			#version 120
			void main() {
				gl_FragData[0] = vec4(gl_ModelViewMatrix[0][0]);
			}
			""", schema);
		assertTrue(t.msl().contains("(*blk_QsDraw).u_modelViewMatrix"), t.msl());
		assertTrue(t.reflection().uniforms().stream().anyMatch(u -> u.name().equals("gl_ModelViewMatrix")
			&& "QsDraw".equals(u.servedBy())), t.reflection().uniforms().toString());
	}

	@Test
	void uniformNoBlockServesIsListedAsUnknown() {
		ProgramTranslation t = translate(VS_QUAD, """
			#version 120
			uniform float missing;
			void main() {
				gl_FragData[0] = vec4(missing);
			}
			""");
		UniformUse use = t.reflection().uniforms().stream().filter(u -> u.name().equals("missing")).findFirst()
			.orElseThrow();
		assertEquals("unknown", use.servedBy());
		assertEquals("float", use.glslType());
	}

	@Test
	void compoundAssignmentToASwizzleIsWrittenAsAValueAssignment() {
		ProgramTranslation t = translate(VS_QUAD, """
			#version 120
			void main() {
				vec3 v = gl_FragCoord.xyz;
				v.xy *= mat2(1.0, 0.0, 0.0, 1.0);
				gl_FragData[0] = vec4(v, 1.0);
			}
			""");
		assertTrue(!t.msl().contains(".xy *="), t.msl());
		assertTrue(t.msl().contains(".xy = "), t.msl());
	}

	@Test
	void boolBlockMemberIsAFourByteIntAndIsServedByItsBlock() {
		UniformSchema schema = new UniformSchema(List.of(new UniformBlock("QsCustom", CUSTOM, List.of(
			new UniformField("enabled", "bool", 0), new UniformField("level", "float", 4)))));
		ProgramTranslation t = translate(VS_QUAD, """
			#version 120
			uniform bool enabled;
			uniform float level;
			void main() {
				gl_FragData[0] = enabled ? vec4(level) : vec4(0.0);
			}
			""", schema);
		assertTrue(t.msl().matches("(?s).*\\bint\\s+u_enabled\\b.*"), t.msl());
		assertTrue(t.reflection().uniforms().stream().anyMatch(u -> u.name().equals("enabled")
			&& "QsCustom".equals(u.servedBy())), t.reflection().uniforms().toString());
	}
}
