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

/** Review round 2 on the standard road's printer: the alpha test's output type and the dual-source refusal. */
final class Glsl450ReviewTest {

	private static final String VS = """
		#version 450
		void main() { gl_Position = ftransform(); }
		""";

	private static List<SourceLine> lines(String text) {
		return IntStream.rangeClosed(1, text.split("\n", -1).length).mapToObj(i -> new SourceLine("test.fsh", i))
			.toList();
	}

	private static Glsl450Program print(String fs, AlphaTest alpha, UniformSchema schema) {
		List<ColorTarget> listed = IntStream.range(0, 3).mapToObj(i -> new ColorTarget(i, i, 4)).toList();
		return Glsl450Printer.print(new ProgramRequest("printer-review", ProgramRequest.Family.FULL_SCREEN,
			List.of(new StageSource(Stage.VERTEX, VS, lines(VS)), new StageSource(Stage.FRAGMENT, fs, lines(fs))),
			ProgramRequest.VertexLayout.fullScreenQuad(), listed, List.of(), List.of(), List.of(),
			schema == null ? UniformSchema.none() : schema, alpha, false));
	}

	@Test
	void anAlphaTestWithAnIntegerTargetZeroSkipsTheTestWithAWarning() {
		UniformSchema schema = new UniformSchema(List.of(new UniformBlock("frame", 3,
			List.of(new UniformField("alphaTestRef", "float", 0)))));
		Glsl450Program p = print("""
			#version 450
			layout(location = 0) out ivec4 a;
			layout(location = 1) out vec4 b;
			void main() {
				a = ivec4(1);
				b = vec4(1.0);
			}
			""", new AlphaTest("GREATER", "alphaTestRef"), schema);
		assertTrue(p.ok(), p.errors().toString());
		String fs = p.stage(Stage.FRAGMENT).source();
		assertFalse(fs.contains("discard"), fs);
		assertTrue(p.warnings().stream().anyMatch(w -> w.contains("alpha-test-target")), p.warnings().toString());
	}

	@Test
	void aDualSourceIndexIsRefusedWithItsOwnError() {
		Glsl450Program p = print("""
			#version 450
			layout(location = 0, index = 1) out vec4 blend;
			void main() {
				blend = vec4(1.0);
			}
			""", null, null);
		assertFalse(p.ok());
		assertTrue(p.errors().stream().anyMatch(e -> e.contains("output-dual-source")), p.errors().toString());
		assertFalse(p.errors().stream().anyMatch(e -> e.contains("output-location-clash")), p.errors().toString());
	}
}
