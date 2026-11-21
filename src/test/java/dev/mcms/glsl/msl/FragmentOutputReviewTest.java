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

/** Review round 2 of the fragment output rules: the alpha test's type, out-of-range names, dual-source outputs. */
final class FragmentOutputReviewTest {

	private static final String VS = """
		#version 450
		void main() { gl_Position = ftransform(); }
		""";

	private static List<SourceLine> lines(String text) {
		return IntStream.rangeClosed(1, text.split("\n", -1).length).mapToObj(i -> new SourceLine("test.fsh", i))
			.toList();
	}

	private static ProgramRequest request(String fs, int targets, AlphaTest alpha, UniformSchema schema) {
		List<ColorTarget> listed = IntStream.range(0, targets).mapToObj(i -> new ColorTarget(i, i, 4)).toList();
		return new ProgramRequest("output-review", ProgramRequest.Family.FULL_SCREEN,
			List.of(new StageSource(Stage.VERTEX, VS, lines(VS)), new StageSource(Stage.FRAGMENT, fs, lines(fs))),
			ProgramRequest.VertexLayout.fullScreenQuad(), listed, List.of(), List.of(), List.of(),
			schema == null ? UniformSchema.none() : schema, alpha, false);
	}

	private static ProgramTranslation translateRaw(String fs) {
		return Translator.translate(request(fs, 3, null, null));
	}

	private static List<Diagnostic> diagnostics(ProgramTranslation t, Diagnostic.Severity severity, String code) {
		return t.diagnostics().stream().filter(d -> d.severity() == severity && d.code().equals(code)).toList();
	}

	private static List<Integer> attachments(ProgramTranslation t) {
		return t.reflection().outputs().stream().map(o -> o.attachment()).toList();
	}

	@Test
	void anAlphaTestWithAnIntegerTargetZeroSkipsTheTestWithAWarning() {
		UniformSchema schema = new UniformSchema(List.of(new UniformBlock("frame", 3,
			List.of(new UniformField("alphaTestRef", "float", 0)))));
		ProgramTranslation t = Translator.translate(request("""
			#version 450
			layout(location = 0) out ivec4 a;
			layout(location = 1) out vec4 b;
			void main() {
				a = ivec4(1);
				b = vec4(1.0);
			}
			""", 3, new AlphaTest("GREATER", "alphaTestRef"), schema));
		assertTrue(t.ok(), t.diagnostics().toString());
		assertFalse(t.msl().contains("discard_fragment"), t.msl());
		assertEquals(1, diagnostics(t, Diagnostic.Severity.WARNING, "alpha-test-target").size(),
			t.diagnostics().toString());
	}

	@Test
	void anUnsizedOutArrayIsRejectedBySema() {
		ProgramTranslation t = translateRaw("""
			#version 450
			out vec4 x[];
			void main() {
				x[0] = vec4(1.0);
			}
			""");
		assertFalse(t.ok(), t.diagnostics().toString());
		assertEquals(1, diagnostics(t, Diagnostic.Severity.ERROR, "array-size-missing").size(),
			t.diagnostics().toString());
	}

	@Test
	void twoOutOfRangeOutsWarnSeparatelyAndDoNotClash() {
		ProgramTranslation t = translateRaw("""
			#version 450
			layout(location = 2147483647) out vec4 far;
			out vec4 outColor99999999999;
			void main() {
				far = vec4(1.0);
				outColor99999999999 = vec4(1.0);
			}
			""");
		assertTrue(t.ok(), t.diagnostics().toString());
		assertEquals(List.of(), attachments(t));
		assertEquals(0, diagnostics(t, Diagnostic.Severity.ERROR, "output-location-clash").size(),
			t.diagnostics().toString());
		assertEquals(2, diagnostics(t, Diagnostic.Severity.WARNING, "output-target-missing").size(),
			t.diagnostics().toString());
	}

	@Test
	void leadingZerosInAnOutColorNameParseAsTheirNumber() {
		ProgramTranslation t = translateRaw("""
			#version 450
			out vec4 outColor0000000001;
			void main() {
				outColor0000000001 = vec4(1.0);
			}
			""");
		assertTrue(t.ok(), t.diagnostics().toString());
		assertEquals(List.of(1), attachments(t));
		assertEquals(0, diagnostics(t, Diagnostic.Severity.WARNING, "output-target-missing").size(),
			t.diagnostics().toString());
	}

	@Test
	void aHugeOutArrayClaimsOnlyTheListedTargetsAndWarnsOnce() {
		ProgramTranslation t = translateRaw("""
			#version 450
			layout(location = 0) out vec4 x[3000000];
			void main() {
				x[0] = vec4(1.0);
			}
			""");
		assertTrue(t.ok(), t.diagnostics().toString());
		assertEquals(List.of(0, 1, 2), attachments(t));
		assertEquals(1, diagnostics(t, Diagnostic.Severity.WARNING, "output-target-missing").size(),
			t.diagnostics().toString());
	}

	@Test
	void aDualSourceIndexIsRejectedWithItsOwnError() {
		ProgramTranslation t = translateRaw("""
			#version 450
			layout(location = 0, index = 1) out vec4 blend;
			void main() {
				blend = vec4(1.0);
			}
			""");
		assertFalse(t.ok(), t.diagnostics().toString());
		assertEquals(1, diagnostics(t, Diagnostic.Severity.ERROR, "output-dual-source").size(),
			t.diagnostics().toString());
		assertEquals(0, diagnostics(t, Diagnostic.Severity.ERROR, "output-location-clash").size(),
			t.diagnostics().toString());
	}
}
