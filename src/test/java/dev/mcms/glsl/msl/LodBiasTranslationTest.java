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
import dev.mcms.glsl.msl.ProgramRequest.UniformBlock;
import dev.mcms.glsl.msl.ProgramRequest.UniformField;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * ADR 0008's LOD bias: the fragment stage's implicit-LOD lookups of a texture whose binding asks for it add the engine block's
 * {@link ProgramRequest#LOD_BIAS_FIELD} to their own bias, and the stage binds that block although the pack reads none of its
 * uniforms. Explicit-level lookups, other textures and the vertex stage take none (Metal's bias is fragment-only).
 */
final class LodBiasTranslationTest {

	private static final String VS = """
		#version 130
		uniform sampler2D gtexture;
		out vec2 uv;
		out vec4 tint;
		void main() {
			vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
			gl_Position = vec4(corner * 2.0 - 1.0, 0.5, 1.0);
			uv = corner;
			tint = texture(gtexture, corner);
		}
		""";

	private static final String FS = """
		#version 130
		uniform sampler2D gtexture;
		uniform sampler2D colortex0;
		in vec2 uv;
		in vec4 tint;
		out vec4 color;
		void main() {
			color = texture(gtexture, uv) + texture(gtexture, uv, 1.5) + textureLod(gtexture, uv, 2.0) + texture(colortex0, uv)
				+ tint;
		}
		""";

	private static StageSource stage(Stage stage, String text) {
		int n = text.split("\n", -1).length;
		return new StageSource(stage, text, IntStream.rangeClosed(1, n).mapToObj(i -> new SourceLine("lodbias", i)).toList());
	}

	private static ProgramTranslation translate() {
		SamplerState mip = new SamplerState(ProgramRequest.Filter.LINEAR, ProgramRequest.Filter.LINEAR,
			ProgramRequest.Wrap.REPEAT, false, false);
		List<TextureBinding> bindings = List.of(
			new TextureBinding("gtexture", 0, TextureKind.COLOUR, -1, mip, -1).withLodBias(),
			new TextureBinding("colortex0", 1, TextureKind.COLOUR, 0, mip, -1));
		UniformSchema blocks = new UniformSchema(List.of(new UniformBlock("QsFrame", 3,
			List.of(new UniformField(ProgramRequest.LOD_BIAS_FIELD, "float", 0)))));
		ProgramRequest request = new ProgramRequest("lodbias", ProgramRequest.Family.FULL_SCREEN,
			List.of(stage(Stage.VERTEX, VS), stage(Stage.FRAGMENT, FS)), ProgramRequest.VertexLayout.fullScreenQuad(),
			List.of(new ColorTarget(0, 0, 4)), bindings, List.of(), List.of(), blocks, null, false);
		ProgramTranslation t = Translator.translate(request);
		assertTrue(t.ok(), t.diagnostics().toString());
		return t;
	}

	private static int count(String text, String regex) {
		Matcher m = Pattern.compile(regex).matcher(text);
		int n = 0;
		while (m.find()) {
			n++;
		}
		return n;
	}

	@Test
	void theFragmentsImplicitAtlasLookupsAddTheEngineBiasAndNothingElseDoes() {
		ProgramTranslation t = translate();
		String msl = t.msl();
		String field = "\\(\\*blk_QsFrame\\)\\.\\w*" + ProgramRequest.LOD_BIAS_FIELD;
		assertEquals(1, count(msl, "bias\\(" + field + "\\)"), "texture(gtexture, uv) takes the engine bias alone:\n" + msl);
		assertEquals(1, count(msl, "bias\\(\\(1\\.5f?\\) \\+ " + field + "\\)"),
			"texture(gtexture, uv, 1.5) sums the pack's bias and the engine's:\n" + msl);
		assertEquals(2, count(msl, field), "only the two implicit atlas lookups read the bias (not textureLod, colortex0, the"
			+ " vertex stage):\n" + msl);
		assertTrue(t.reflection().buffers().stream().anyMatch(b -> b.name().equals("QsFrame")),
			"the stage binds the engine block for the bias: " + t.reflection().buffers());
	}

	@Test
	void aBindingWithoutTheFlagTakesNoBias() {
		SamplerState mip = new SamplerState(ProgramRequest.Filter.LINEAR, ProgramRequest.Filter.LINEAR,
			ProgramRequest.Wrap.REPEAT, false, false);
		List<TextureBinding> bindings = List.of(new TextureBinding("gtexture", 0, TextureKind.COLOUR, -1, mip, -1),
			new TextureBinding("colortex0", 1, TextureKind.COLOUR, 0, mip, -1));
		UniformSchema blocks = new UniformSchema(List.of(new UniformBlock("QsFrame", 3,
			List.of(new UniformField(ProgramRequest.LOD_BIAS_FIELD, "float", 0)))));
		ProgramTranslation t = Translator.translate(new ProgramRequest("plain", ProgramRequest.Family.FULL_SCREEN,
			List.of(stage(Stage.VERTEX, VS), stage(Stage.FRAGMENT, FS)), ProgramRequest.VertexLayout.fullScreenQuad(),
			List.of(new ColorTarget(0, 0, 4)), bindings, List.of(), List.of(), blocks, null, false));
		assertTrue(t.ok(), t.diagnostics().toString());
		assertEquals(0, count(t.msl(), "\\(\\*blk_QsFrame\\)\\.\\w*" + ProgramRequest.LOD_BIAS_FIELD),
			"no flag, no read of the bias (the block's struct is declared either way):\n" + t.msl());
	}

	@Test
	void aLookupInAHelperFunctionTakesTheBiasAndTheStageBindsTheBlock() {
		// Packs sample the atlas in helpers as often as in main: the block pointer is a member of the stage's context, which
		// every helper reaches, so the helper's lookup reads the bias and the stage binds the block.
		String fs = """
			#version 130
			uniform sampler2D gtexture;
			in vec2 uv;
			out vec4 color;
			vec4 atlasColour(vec2 at) {
				return texture(gtexture, at);
			}
			void main() {
				color = atlasColour(uv);
			}
			""";
		String vs = """
			#version 130
			out vec2 uv;
			void main() {
				vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
				gl_Position = vec4(corner * 2.0 - 1.0, 0.5, 1.0);
				uv = corner;
			}
			""";
		SamplerState mip = new SamplerState(ProgramRequest.Filter.LINEAR, ProgramRequest.Filter.LINEAR,
			ProgramRequest.Wrap.REPEAT, false, false);
		UniformSchema blocks = new UniformSchema(List.of(new UniformBlock("QsFrame", 3,
			List.of(new UniformField(ProgramRequest.LOD_BIAS_FIELD, "float", 0)))));
		ProgramTranslation t = Translator.translate(new ProgramRequest("helper", ProgramRequest.Family.FULL_SCREEN,
			List.of(stage(Stage.VERTEX, vs), stage(Stage.FRAGMENT, fs)), ProgramRequest.VertexLayout.fullScreenQuad(),
			List.of(new ColorTarget(0, 0, 4)), List.of(new TextureBinding("gtexture", 0, TextureKind.COLOUR, -1, mip, -1)
				.withLodBias()), List.of(), List.of(), blocks, null, false));
		assertTrue(t.ok(), t.diagnostics().toString());
		assertEquals(1, count(t.msl(), "bias\\(\\(\\*blk_QsFrame\\)\\.\\w*" + ProgramRequest.LOD_BIAS_FIELD + "\\)"),
			"the helper's lookup reads the bias:\n" + t.msl());
		assertTrue(t.reflection().buffers().stream().anyMatch(b -> b.name().equals("QsFrame")),
			"the stage binds the block for the helper's bias: " + t.reflection().buffers());
	}
}
