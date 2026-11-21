// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.VertexAttribute;
import dev.mcms.glsl.msl.ProgramRequest.VertexLayout;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * A one-component attribute read as a wider GLSL input takes GL's defaults for the components it lacks (y and z zero,
 * w one), and a pipeline that draws points writes the program's gl_PointSize.
 */
class AttributeFillAndPointSizeTest {

	private static final String FS = "#version 330\nout vec4 c;\nvoid main() { c = vec4(1.0); }\n";

	private static ProgramTranslation translate(String vs, boolean points) {
		List<SourceLine> vl = IntStream.rangeClosed(1, vs.split("\n", -1).length)
			.mapToObj(i -> new SourceLine("vs", i)).toList();
		List<SourceLine> fl = IntStream.rangeClosed(1, FS.split("\n", -1).length)
			.mapToObj(i -> new SourceLine("fs", i)).toList();
		return Translator.translate(new ProgramRequest("attr", ProgramRequest.Family.GBUFFERS,
			List.of(new ProgramRequest.StageSource(Stage.VERTEX, vs, vl),
				new ProgramRequest.StageSource(Stage.FRAGMENT, FS, fl)),
			VertexLayout.of(new VertexAttribute(ProgramRequest.Semantic.POSITION, "float1", 0, 0)),
			List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(),
			ProgramRequest.UniformSchema.none(), null, points));
	}

	@Test
	void floatAttributeIntoVec4FillsYZeroZeroWOne() {
		ProgramTranslation t = translate("#version 330\nin vec4 vaPosition;\nvoid main() { gl_Position = vaPosition; }\n",
			false);
		assertTrue(t.ok(), t.diagnostics().toString());
		assertTrue(t.msl().contains("float4(float(qs_a0), 0.0f, 0.0f, 1.0f)"), t.msl());
	}

	@Test
	void floatAttributeIntoVec3FillsYAndZZero() {
		ProgramTranslation t = translate("#version 330\nin vec3 vaPosition;\nvoid main() { gl_Position = vec4(vaPosition, 1.0); }\n",
			false);
		assertTrue(t.ok(), t.diagnostics().toString());
		assertTrue(t.msl().contains("float3(float(qs_a0), 0.0f, 0.0f)"), t.msl());
	}

	@Test
	void pointPipelineWritesTheProgramsPointSize() {
		ProgramTranslation t = translate("#version 330\nin vec4 vaPosition;\nvoid main() { gl_Position = vaPosition; "
			+ "gl_PointSize = 3.0; }\n", true);
		assertTrue(t.ok(), t.diagnostics().toString());
		assertTrue(t.msl().contains("[[point_size]]"), t.msl());
		assertTrue(t.msl().contains("o.gl_PointSize = gl_PointSize;"), t.msl());
	}

	@Test
	void nonPointPipelineHasNoPointSizeMember() {
		ProgramTranslation t = translate("#version 330\nin vec4 vaPosition;\nvoid main() { gl_Position = vaPosition; }\n",
			false);
		assertTrue(t.ok(), t.diagnostics().toString());
		assertFalse(t.msl().contains("point_size"), t.msl());
	}
}
