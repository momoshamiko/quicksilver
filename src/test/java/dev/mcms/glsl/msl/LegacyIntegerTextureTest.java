// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.Filter;
import dev.mcms.glsl.msl.ProgramRequest.SamplerState;
import dev.mcms.glsl.msl.ProgramRequest.StageSource;
import dev.mcms.glsl.msl.ProgramRequest.TextureBinding;
import dev.mcms.glsl.msl.ProgramRequest.TextureKind;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.msl.ProgramRequest.Wrap;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A legacy texture name on an integer sampler (Complementary's gbuffers_terrain samples its own usampler2D with
 * texture2D) translates to the same Metal as the modern texture function of that sampler (ADR 0006 E6).
 */
final class LegacyIntegerTextureTest {

	private static final String VS = """
		#version 130
		void main() {
			gl_Position = vec4(0.0, 0.0, 0.5, 1.0);
		}
		""";

	private static final String FS_LEGACY = """
		#version 130
		uniform usampler2D puddle;
		out vec4 color;
		void main() {
			uvec4 p = texture2D(puddle, gl_FragCoord.xy * 0.5);
			color = vec4(p);
		}
		""";

	/** The same program with the modern name: only the call's name differs. */
	private static final String FS_MODERN = FS_LEGACY.replace("texture2D(", "texture(");

	private static List<SourceLine> lines(String text) {
		int n = text.split("\n", -1).length;
		return java.util.stream.IntStream.rangeClosed(1, n).mapToObj(i -> new SourceLine("legacy", i)).toList();
	}

	private static ProgramTranslation translate(String fs) {
		SamplerState nearest = new SamplerState(Filter.NEAREST, null, Wrap.CLAMP_TO_EDGE, false, true);
		return Translator.translate(new ProgramRequest("legacy", ProgramRequest.Family.FULL_SCREEN,
			List.of(new StageSource(Stage.VERTEX, VS, lines(VS)), new StageSource(Stage.FRAGMENT, fs, lines(fs))),
			ProgramRequest.VertexLayout.fullScreenQuad(), List.of(new ColorTarget(0, 0, 4)),
			List.of(new TextureBinding("puddle", 0, TextureKind.INTEGER, -1, nearest, -1)), List.of(), List.of(),
			UniformSchema.none(), null, false));
	}

	@Test
	void legacyIntegerTextureTranslatesAndMatchesTheModernCall() {
		ProgramTranslation legacy = translate(FS_LEGACY);
		assertTrue(legacy.ok(), legacy.diagnostics().toString());
		ProgramTranslation modern = translate(FS_MODERN);
		assertTrue(modern.ok(), modern.diagnostics().toString());
		assertEquals(modern.msl(), legacy.msl());
	}
}
