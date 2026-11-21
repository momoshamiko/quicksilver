// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.SamplerState;
import dev.mcms.glsl.msl.ProgramRequest.StageSource;
import dev.mcms.glsl.msl.ProgramRequest.TextureBinding;
import dev.mcms.glsl.msl.ProgramRequest.TextureKind;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Emission facts the GPU suite cannot see directly: program-scope names, unserved built-ins, the sampler form. */
final class EmitterSilentWrongTest {

	private static final String VS_ID = """
		#version 130
		out vec2 uv;
		void main() {
			vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
			gl_Position = vec4(corner * 2.0 - 1.0, 0.5, 1.0);
			uv = corner;
		}
		""";

	private static List<SourceLine> lines(String text) {
		int n = text.split("\n", -1).length;
		return java.util.stream.IntStream.rangeClosed(1, n).mapToObj(i -> new SourceLine("silent", i)).toList();
	}

	private static StageSource stage(Stage stage, String text) {
		return new StageSource(stage, text, lines(text));
	}

	private static ProgramRequest quad(String vs, String fs, List<TextureBinding> textures, UniformSchema schema) {
		return new ProgramRequest("silent", ProgramRequest.Family.FULL_SCREEN,
			List.of(stage(Stage.VERTEX, vs), stage(Stage.FRAGMENT, fs)), ProgramRequest.VertexLayout.fullScreenQuad(),
			List.of(new ColorTarget(0, 0, 4)), textures, List.of(), List.of(), schema, null, false);
	}

	/**
	 * A program-scope constant is defined once per stage: two stages that declare constants of one name with different
	 * values each keep their own definition (the definitions were keyed by name alone).
	 */
	@Test
	void eachStageKeepsItsOwnProgramScopeConstant() {
		String vs = """
			#version 130
			const float tbl = 1.0;
			out float tv;
			void main() {
				vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
				gl_Position = vec4(corner * 2.0 - 1.0, 0.5, 1.0);
				tv = tbl;
			}
			""";
		String fs = """
			#version 130
			const float tbl = 5.0;
			in float tv;
			out vec4 color;
			void main() {
				color = vec4(tv, tbl, 0.0, 1.0);
			}
			""";
		ProgramTranslation t = Translator.translate(quad(vs, fs, List.of(), UniformSchema.none()));
		assertTrue(t.ok(), t.diagnostics().toString());
		Matcher m = Pattern.compile("constant float (\\w+) = ([0-9.]+)f;").matcher(t.msl());
		Map<String, String> defs = new HashMap<>();
		while (m.find()) {
			defs.put(m.group(1), m.group(2));
		}
		assertEquals(2, defs.size(), "one definition per stage: " + defs);
		assertTrue(defs.containsValue("1.0") && defs.containsValue("5.0"), "values: " + defs);
	}

	/**
	 * A compatibility uniform the schema does not serve is a translation error, not a silent zero: gl_NormalScale is a
	 * float the engine never supplies here.
	 */
	@Test
	void anUnservedCompatibilityUniformIsATranslationError() {
		String vs = """
			#version 130
			out vec2 uv;
			void main() {
				vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
				gl_Position = vec4(corner * 2.0 - 1.0, 0.5, 1.0) * gl_NormalScale;
				uv = corner;
			}
			""";
		String fs = """
			#version 130
			in vec2 uv;
			out vec4 color;
			void main() {
				color = vec4(uv, 0.0, 1.0);
			}
			""";
		ProgramTranslation t = Translator.translate(quad(vs, fs, List.of(), UniformSchema.none()));
		assertFalse(t.ok(), "an unserved compatibility uniform must fail the translation");
		assertTrue(t.diagnostics().stream().anyMatch(d -> d.code().equals("compat-unbound")), t.diagnostics().toString());
	}

	/** A rectangle texture's sampler takes pixel coordinates (coord::pixel) and is declared as such. */
	@Test
	void aPixelSamplerIsEmittedWithCoordPixel() {
		String fs = """
			#version 140
			uniform sampler2DRect tex;
			out vec4 color;
			void main() {
				color = texture(tex, vec2(1.5, 2.5));
			}
			""";
		SamplerState pixel = new SamplerState(ProgramRequest.Filter.NEAREST, null, ProgramRequest.Wrap.CLAMP_TO_EDGE,
			false, true);
		ProgramTranslation t = Translator.translate(quad(VS_ID, fs,
			List.of(new TextureBinding("tex", 0, TextureKind.COLOUR, -1, pixel, -1)), UniformSchema.none()));
		assertTrue(t.ok(), t.diagnostics().toString());
		assertTrue(t.msl().contains("coord::pixel"), "the sampler must take pixel coordinates:\n" + t.msl());
	}
}
