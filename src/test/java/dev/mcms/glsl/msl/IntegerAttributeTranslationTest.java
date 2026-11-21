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
 * A three-component integer vertex attribute with no decode is declared with three integer components and read as three components.
 * The read is a float3 of the input, so Metal converts three components; a four-component read of the three-component input (the
 * width the component count used to give it) is what Metal refuses, and the text must carry none.
 */
class IntegerAttributeTranslationTest {

	private static final String FS = "#version 330\nout vec4 c;\nvoid main() { c = vec4(1.0); }\n";

	private static ProgramTranslation translate(String format, String vs) {
		List<SourceLine> vl = IntStream.rangeClosed(1, vs.split("\n", -1).length)
			.mapToObj(i -> new SourceLine("vs", i)).toList();
		List<SourceLine> fl = IntStream.rangeClosed(1, FS.split("\n", -1).length)
			.mapToObj(i -> new SourceLine("fs", i)).toList();
		return Translator.translate(new ProgramRequest("intattr", ProgramRequest.Family.GBUFFERS,
			List.of(new ProgramRequest.StageSource(Stage.VERTEX, vs, vl),
				new ProgramRequest.StageSource(Stage.FRAGMENT, FS, fl)),
			VertexLayout.of(new VertexAttribute(ProgramRequest.Semantic.POSITION, format, 0, 0)),
			List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(),
			ProgramRequest.UniformSchema.none(), null, false));
	}

	private static final String VS = "#version 330\nin vec3 vaPosition;\nvoid main() { gl_Position = vec4(vaPosition, 1.0); }\n";

	@Test
	void aPlainShort3AttributeIsDeclaredAndReadAsThreeComponents() {
		ProgramTranslation t = translate("short3", VS);
		assertTrue(t.ok(), t.diagnostics().toString());
		assertTrue(t.msl().contains("int3 qs_a0 [[attribute(0)]]"), t.msl());
		assertTrue(t.msl().contains("float3(qs_a0)"), t.msl());
		assertFalse(t.msl().contains("int4 qs_a0"), t.msl());
		assertFalse(t.msl().contains("float4(qs_a0)"), t.msl());
	}

	@Test
	void aPlainUshort3AttributeIsDeclaredAndReadAsThreeComponents() {
		ProgramTranslation t = translate("ushort3", VS);
		assertTrue(t.ok(), t.diagnostics().toString());
		assertTrue(t.msl().contains("uint3 qs_a0 [[attribute(0)]]"), t.msl());
		assertTrue(t.msl().contains("float3(qs_a0)"), t.msl());
		assertFalse(t.msl().contains("uint4 qs_a0"), t.msl());
		assertFalse(t.msl().contains("float4(qs_a0)"), t.msl());
	}
}
