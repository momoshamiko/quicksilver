// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.Filter;
import dev.mcms.glsl.msl.ProgramRequest.SamplerState;
import dev.mcms.glsl.msl.ProgramRequest.TextureBinding;
import dev.mcms.glsl.msl.ProgramRequest.TextureKind;
import dev.mcms.glsl.msl.ProgramRequest.VertexAttribute;
import dev.mcms.glsl.msl.ProgramRequest.VertexLayout;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * A sampler2DRect reads texel coordinates. The caller's request leaves the pixel flag off here: the translator takes
 * the coordinate kind from the GLSL sampler type, so the sampler is emitted with coord::pixel and not normalized.
 */
class RectangleSamplerTest {

	@Test
	void rectangleSamplerIsEmittedWithPixelCoordinates() {
		String vs = "#version 330\nin vec4 vaPosition;\nvoid main() { gl_Position = vaPosition; }\n";
		String fs = "#version 330\nuniform sampler2DRect s;\nout vec4 c;\nvoid main() { c = texture(s, vec2(1.0)); }\n";
		List<SourceLine> vl = IntStream.rangeClosed(1, vs.split("\n", -1).length)
			.mapToObj(i -> new SourceLine("vs", i)).toList();
		List<SourceLine> fl = IntStream.rangeClosed(1, fs.split("\n", -1).length)
			.mapToObj(i -> new SourceLine("fs", i)).toList();
		SamplerState linear = new SamplerState(Filter.LINEAR, null, ProgramRequest.Wrap.CLAMP_TO_EDGE, false, false);
		ProgramTranslation t = Translator.translate(new ProgramRequest("rect", ProgramRequest.Family.GBUFFERS,
			List.of(new ProgramRequest.StageSource(Stage.VERTEX, vs, vl),
				new ProgramRequest.StageSource(Stage.FRAGMENT, fs, fl)),
			VertexLayout.of(new VertexAttribute(ProgramRequest.Semantic.POSITION, "float1", 0, 0)),
			List.of(new ColorTarget(0, 0, 4)), List.of(TextureBinding.constexpr("s", 0, TextureKind.COLOUR, linear)),
			List.of(), List.of(), ProgramRequest.UniformSchema.none(), null, false));
		assertTrue(t.ok(), t.diagnostics().toString());
		assertTrue(t.msl().contains("coord::pixel"), t.msl());
	}
}
