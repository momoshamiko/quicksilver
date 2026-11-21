// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest.AlphaTest;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.StageSource;
import dev.mcms.glsl.msl.ProgramRequest.UniformBlock;
import dev.mcms.glsl.msl.ProgramRequest.UniformField;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.msl.ProgramTranslation.Diagnostic;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The fragment outputs' locations (engine-pack-format section 9): an explicit layout, an outColor<n> name, a
 * gl_FragData index or declaration order maps the output to that listed target; a listed target nothing writes is
 * absent from the reflection and the Fs_out struct. Outputs the request does not list are warned, clashes are errors.
 */
final class FragmentOutputLocationsTest {

	private static final String VS_QUAD = """
		#version 450
		void main() { gl_Position = ftransform(); }
		""";

	private static List<SourceLine> lines(String text) {
		return IntStream.rangeClosed(1, text.split("\n", -1).length).mapToObj(i -> new SourceLine("test.fsh", i))
			.toList();
	}

	private static ProgramRequest request(String fs, int targets, AlphaTest alpha, UniformSchema schema) {
		List<ColorTarget> listed = IntStream.range(0, targets).mapToObj(i -> new ColorTarget(i, i, 4)).toList();
		return new ProgramRequest("output-locations", ProgramRequest.Family.FULL_SCREEN,
			List.of(new StageSource(Stage.VERTEX, VS_QUAD, lines(VS_QUAD)), new StageSource(Stage.FRAGMENT, fs,
				lines(fs))),
			ProgramRequest.VertexLayout.fullScreenQuad(), listed, List.of(), List.of(), List.of(),
			schema == null ? UniformSchema.none() : schema, alpha, false);
	}

	/** A translation of a fragment shader with three listed targets, whatever its diagnostics. */
	private static ProgramTranslation translateRaw(String fs) {
		return Translator.translate(request(fs, 3, null, null));
	}

	/** A translation that must succeed (warnings allowed). */
	private static ProgramTranslation translate(String fs) {
		ProgramTranslation t = translateRaw(fs);
		assertTrue(t.ok(), t.diagnostics().toString());
		return t;
	}

	private static List<Diagnostic> diagnostics(ProgramTranslation t, Diagnostic.Severity severity, String code) {
		return t.diagnostics().stream().filter(d -> d.severity() == severity && d.code().equals(code)).toList();
	}

	private static List<Integer> attachments(ProgramTranslation t) {
		return t.reflection().outputs().stream().map(o -> o.attachment()).toList();
	}

	@Test
	void explicitLocationsOutOfDeclarationOrderMapToTheirListedTargets() {
		ProgramTranslation t = translate("""
			#version 450
			layout(location = 2) out vec4 second;
			layout(location = 0) out vec4 first;
			void main() {
				first = vec4(1.0, 0.0, 0.0, 1.0);
				second = vec4(0.0, 0.0, 1.0, 1.0);
			}
			""");
		assertEquals(List.of(0, 2), attachments(t));
		assertTrue(t.msl().contains("[[color(0)]]"), t.msl());
		assertTrue(t.msl().contains("[[color(2)]]"), t.msl());
		assertFalse(t.msl().contains("[[color(1)]]"), t.msl());
		assertFalse(t.msl().contains("c1 "), t.msl());
	}

	@Test
	void sparseGlFragDataReflectsOnlyTheWrittenAttachments() {
		ProgramTranslation t = translate("""
			#version 120
			void main() {
				gl_FragData[0] = vec4(1.0);
				gl_FragData[2] = vec4(1.0);
			}
			""");
		assertEquals(List.of(0, 2), attachments(t));
		assertFalse(t.msl().contains("[[color(1)]]"), t.msl());
	}

	@Test
	void outColorNamesWithAGapWriteTheirListedTargets() {
		ProgramTranslation t = translate("""
			#version 450
			out vec4 outColor2;
			out vec4 outColor0;
			void main() {
				outColor0 = vec4(1.0);
				outColor2 = vec4(1.0);
			}
			""");
		assertEquals(List.of(0, 2), attachments(t));
		assertFalse(t.msl().contains("[[color(1)]]"), t.msl());
	}

	@Test
	void denseOutputsKeepTheirDeclarationOrderSlots() {
		ProgramTranslation t = translate("""
			#version 450
			out vec4 first;
			out vec4 second;
			void main() {
				first = vec4(1.0);
				second = vec4(1.0);
			}
			""");
		assertEquals(List.of(0, 1), attachments(t));
		assertTrue(t.msl().contains("o.c0 = "), t.msl());
		assertTrue(t.msl().contains("o.c1 = "), t.msl());
		assertFalse(t.msl().contains("o.c2"), t.msl());
	}

	@Test
	void anUnnamedOutDoesNotTakeAnotherOutsExplicitLocation() {
		ProgramTranslation t = translate("""
			#version 450
			out vec4 first;
			layout(location = 0) out vec4 second;
			void main() {
				first = vec4(1.0);
				second = vec4(1.0);
			}
			""");
		assertEquals(List.of(0, 1), attachments(t));
		assertEquals("u_second", t.reflection().outputs().get(0).name(), "location 0 holds the explicit out");
		assertEquals("u_first", t.reflection().outputs().get(1).name(), "the unnamed out takes the next free slot");
	}

	@Test
	void aLocationAfterAnExplicitOneTakesTheNextFreeLocation() {
		ProgramTranslation t = translate("""
			#version 450
			layout(location = 1) out vec4 second;
			out vec4 first;
			void main() {
				first = vec4(1.0);
				second = vec4(1.0);
			}
			""");
		assertEquals(List.of(0, 1), attachments(t));
		assertTrue(t.msl().contains("o.c0 = "), t.msl());
		assertTrue(t.msl().contains("o.c1 = "), t.msl());
	}

	@Test
	void anOutColorNameTooLargeForAnIntegerIsOutOfRangeNotACrash() {
		ProgramTranslation t = translateRaw("""
			#version 450
			out vec4 outColor99999999999;
			void main() {
				outColor99999999999 = vec4(1.0);
			}
			""");
		assertTrue(t.ok(), t.diagnostics().toString());
		assertEquals(List.of(), attachments(t));
		assertEquals(1, diagnostics(t, Diagnostic.Severity.WARNING, "output-target-missing").size(),
			t.diagnostics().toString());
	}

	@Test
	void anExplicitLocationTooLargeForAnIntegerIsOutOfRange() {
		ProgramTranslation t = translateRaw("""
			#version 450
			layout(location = 2147483647) out vec4 far;
			void main() {
				far = vec4(1.0);
			}
			""");
		assertTrue(t.ok(), t.diagnostics().toString());
		assertEquals(List.of(), attachments(t));
		assertEquals(1, diagnostics(t, Diagnostic.Severity.WARNING, "output-target-missing").size(),
			t.diagnostics().toString());
	}

	@Test
	void aNegativeExplicitLocationIsAnError() {
		ProgramTranslation t = translateRaw("""
			#version 450
			layout(location = -1) out vec4 bad;
			void main() {
				bad = vec4(1.0);
			}
			""");
		assertFalse(t.ok(), t.diagnostics().toString());
		assertEquals(1, diagnostics(t, Diagnostic.Severity.ERROR, "output-location-invalid").size(),
			t.diagnostics().toString());
	}

	@Test
	void twoOutsAtOneExplicitLocationAreAnError() {
		ProgramTranslation t = translateRaw("""
			#version 450
			layout(location = 0) out vec4 a;
			layout(location = 0) out vec4 b;
			void main() {
				a = vec4(1.0);
				b = vec4(1.0);
			}
			""");
		assertFalse(t.ok(), t.diagnostics().toString());
		assertEquals(1, diagnostics(t, Diagnostic.Severity.ERROR, "output-location-clash").size(),
			t.diagnostics().toString());
	}

	@Test
	void anOutColorNameAndAnExplicitLocationAtTheSameTargetAreAnError() {
		ProgramTranslation t = translateRaw("""
			#version 450
			out vec4 outColor1;
			layout(location = 1) out vec4 b;
			void main() {
				outColor1 = vec4(1.0);
				b = vec4(1.0);
			}
			""");
		assertFalse(t.ok(), t.diagnostics().toString());
		assertEquals(1, diagnostics(t, Diagnostic.Severity.ERROR, "output-location-clash").size(),
			t.diagnostics().toString());
	}

	@Test
	void anOutColorNameBeyondTheListedTargetsIsDroppedWithAWarning() {
		ProgramTranslation t = translate("""
			#version 450
			out vec4 outColor5;
			out vec4 outColor0;
			void main() {
				outColor0 = vec4(1.0);
				outColor5 = vec4(1.0);
			}
			""");
		assertEquals(List.of(0), attachments(t));
		assertEquals(1, diagnostics(t, Diagnostic.Severity.WARNING, "output-target-missing").size(),
			t.diagnostics().toString());
	}

	@Test
	void anArrayElementBeyondTheListedTargetsIsDroppedWithAWarning() {
		ProgramTranslation t = translate("""
			#version 450
			layout(location = 2) out vec4 x[2];
			void main() {
				x[0] = vec4(1.0);
				x[1] = vec4(1.0);
			}
			""");
		assertEquals(List.of(2), attachments(t));
		assertEquals(1, diagnostics(t, Diagnostic.Severity.WARNING, "output-target-missing").size(),
			t.diagnostics().toString());
	}

	@Test
	void anAlphaTestWithTheTargetZeroUnwrittenWarnsAndSkipsTheTest() {
		UniformSchema schema = new UniformSchema(List.of(new UniformBlock("frame", 3,
			List.of(new UniformField("alphaTestRef", "float", 0)))));
		ProgramTranslation t = Translator.translate(request("""
			#version 120
			void main() {
				gl_FragData[1] = vec4(1.0);
			}
			""", 3, new AlphaTest("GREATER", "alphaTestRef"), schema));
		assertTrue(t.ok(), t.diagnostics().toString());
		assertEquals(List.of(1), attachments(t));
		assertFalse(t.msl().contains("discard_fragment"), t.msl());
		assertEquals(1, diagnostics(t, Diagnostic.Severity.WARNING, "alpha-test-target").size(),
			t.diagnostics().toString());
	}

	@Test
	void anExplicitlySizedArrayTakesConsecutiveLocations() {
		ProgramTranslation t = translate("""
			#version 450
			layout(location = 1) out vec4 x[2];
			void main() {
				x[0] = vec4(1.0);
				x[1] = vec4(1.0);
			}
			""");
		assertEquals(List.of(1, 2), attachments(t));
		assertEquals("u_x[0]", t.reflection().outputs().get(0).name());
		assertEquals("u_x[1]", t.reflection().outputs().get(1).name());
	}

	@Test
	void anUnnamedArrayTakesTheFirstFreeConsecutiveRun() {
		ProgramTranslation t = translate("""
			#version 450
			out vec4 y[2];
			out vec4 z;
			void main() {
				y[0] = vec4(1.0);
				y[1] = vec4(1.0);
				z = vec4(1.0);
			}
			""");
		assertEquals(List.of(0, 1, 2), attachments(t));
		assertEquals("u_z", t.reflection().outputs().get(2).name());
	}

	@Test
	void aLoopIndexedGlFragDataFallsBackToEveryIndex() {
		ProgramTranslation t = translate("""
			#version 120
			void main() {
				for (int i = 0; i < 2; i++) {
					gl_FragData[i] = vec4(1.0);
				}
			}
			""");
		assertEquals(List.of(0, 1, 2), attachments(t));
	}

	@Test
	void anOutputWrittenInACalledFunctionIsReflected() {
		ProgramTranslation t = translate("""
			#version 450
			layout(location = 1) out vec4 second;
			void paint(vec4 v) {
				second = v;
			}
			void main() {
				paint(vec4(1.0));
			}
			""");
		assertEquals(List.of(1), attachments(t));
		assertTrue(t.msl().contains("paint("), t.msl());
	}

	@Test
	void anOutputWrittenThroughAnOutParameterIsReflected() {
		ProgramTranslation t = translate("""
			#version 450
			layout(location = 1) out vec4 second;
			void paint(out vec4 o) {
				o = vec4(1.0);
			}
			void main() {
				paint(second);
			}
			""");
		assertEquals(List.of(1), attachments(t));
		assertTrue(t.msl().contains("thread"), t.msl());
	}
}
