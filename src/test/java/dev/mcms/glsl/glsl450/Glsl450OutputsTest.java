// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.glsl450;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.AlphaTest;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.StageSource;
import dev.mcms.glsl.msl.ProgramRequest.UniformBlock;
import dev.mcms.glsl.msl.ProgramRequest.UniformField;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The standard road's fragment outputs (W4-B's printer) follow the same location rule as the MSL emitter. */
final class Glsl450OutputsTest {

	private static final String VS = """
		#version 450
		void main() { gl_Position = ftransform(); }
		""";

	private static List<SourceLine> lines(String text) {
		return IntStream.rangeClosed(1, text.split("\n", -1).length).mapToObj(i -> new SourceLine("test.fsh", i))
			.toList();
	}

	private static Glsl450Program print(String fs, int targets, AlphaTest alpha, UniformSchema schema) {
		List<ColorTarget> listed = IntStream.range(0, targets).mapToObj(i -> new ColorTarget(i, i, 4)).toList();
		return Glsl450Printer.print(new ProgramRequest("printer-outputs", ProgramRequest.Family.FULL_SCREEN,
			List.of(new StageSource(Stage.VERTEX, VS, lines(VS)), new StageSource(Stage.FRAGMENT, fs, lines(fs))),
			ProgramRequest.VertexLayout.fullScreenQuad(), listed, List.of(), List.of(), List.of(),
			schema == null ? UniformSchema.none() : schema, alpha, false));
	}

	private static Glsl450Program print(String fs) {
		return print(fs, 3, null, null);
	}

	@Test
	void aSparseFragDataSetDeclaresOnlyTheWrittenTargets() {
		Glsl450Program p = print("""
			#version 120
			void main() {
				gl_FragData[0] = vec4(1.0);
				gl_FragData[2] = vec4(1.0);
			}
			""");
		assertTrue(p.ok(), p.errors().toString());
		String fs = p.stage(Stage.FRAGMENT).source();
		assertTrue(fs.contains("layout(location = 0) out"), fs);
		assertTrue(fs.contains("layout(location = 2) out"), fs);
		assertFalse(fs.contains("layout(location = 1) out"), fs);
		assertFalse(fs.contains("qs_c1"), fs);
	}

	@Test
	void outputsTakeTheirExplicitLocationsWhateverTheirOrder() {
		Glsl450Program p = print("""
			#version 450
			layout(location = 2) out vec4 top;
			layout(location = 0) out vec4 bottom;
			void main() {
				bottom = vec4(1.0);
				top = vec4(1.0);
			}
			""");
		assertTrue(p.ok(), p.errors().toString());
		String fs = p.stage(Stage.FRAGMENT).source();
		assertTrue(fs.contains("qs_c0 = "), fs);
		assertTrue(fs.contains("qs_c2 = "), fs);
		assertFalse(fs.contains("qs_c1 = "), fs);
	}

	@Test
	void twoOutsAtOneLocationAreAnError() {
		Glsl450Program p = print("""
			#version 450
			layout(location = 0) out vec4 a;
			layout(location = 0) out vec4 b;
			void main() {
				a = vec4(1.0);
				b = vec4(1.0);
			}
			""");
		assertFalse(p.ok());
		assertTrue(p.errors().stream().anyMatch(e -> e.contains("output-location-clash")), p.errors().toString());
	}

	@Test
	void anOutColorNameBeyondTheListedTargetsIsAWarning() {
		Glsl450Program p = print("""
			#version 450
			out vec4 outColor5;
			void main() {
				outColor5 = vec4(1.0);
			}
			""");
		assertTrue(p.ok(), p.errors().toString());
		assertTrue(p.warnings().stream().anyMatch(w -> w.contains("output-target-missing")), p.warnings().toString());
	}

	@Test
	void anAlphaTestWithTargetZeroUnwrittenIsAWarning() {
		UniformSchema schema = new UniformSchema(List.of(new UniformBlock("frame", 3,
			List.of(new UniformField("alphaTestRef", "float", 0)))));
		Glsl450Program p = print("""
			#version 120
			void main() {
				gl_FragData[1] = vec4(1.0);
			}
			""", 3, new AlphaTest("GREATER", "alphaTestRef"), schema);
		assertTrue(p.ok(), p.errors().toString());
		assertTrue(p.warnings().stream().anyMatch(w -> w.contains("alpha-test-target")), p.warnings().toString());
		assertFalse(p.stage(Stage.FRAGMENT).source().contains("discard"), p.stage(Stage.FRAGMENT).source());
	}

	@Test
	void anExplicitlySizedArrayPrintsOneDeclarationPerElementLocation() {
		Glsl450Program p = print("""
			#version 450
			layout(location = 1) out vec4 x[2];
			void main() {
				x[0] = vec4(1.0);
				x[1] = vec4(1.0);
			}
			""");
		assertTrue(p.ok(), p.errors().toString());
		String fs = p.stage(Stage.FRAGMENT).source();
		assertTrue(fs.contains("qs_c1 = "), fs);
		assertTrue(fs.contains("qs_c2 = "), fs);
	}
}
