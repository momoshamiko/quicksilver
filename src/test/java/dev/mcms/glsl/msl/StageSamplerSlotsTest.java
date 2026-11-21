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
 * A stage numbers its game samplers from zero, in its own order, so the vertex and fragment stages of one program may give the
 * same texture different sampler slots; and a texture bound past index 16 takes a sampler slot below 16, never its own index.
 * The vertex stage here samples one game texture, the fragment stage three (one of them at texture index 17).
 */
final class StageSamplerSlotsTest {

	private static final String VS = """
		#version 130
		uniform sampler2D vtex;
		void main() {
			gl_Position = vec4(0.0, 0.0, texture2D(vtex, vec2(0.5)).x * 0.0 + 0.5, 1.0);
		}
		""";

	private static final String FS = """
		#version 130
		uniform sampler2D ftex;
		uniform sampler2D vtex;
		uniform sampler2D far;
		out vec4 color;
		void main() {
			color = texture2D(ftex, gl_FragCoord.xy) + texture2D(vtex, gl_FragCoord.xy) + texture2D(far, gl_FragCoord.xy);
		}
		""";

	private static List<SourceLine> lines(String text) {
		int n = text.split("\n", -1).length;
		return java.util.stream.IntStream.rangeClosed(1, n).mapToObj(i -> new SourceLine("stage", i)).toList();
	}

	/** Game samplers: sampler null (the game's own sampler), with the program-wide ordinal the request gives them. */
	private static ProgramTranslation translate() {
		SamplerState nearest = new SamplerState(Filter.NEAREST, null, Wrap.CLAMP_TO_EDGE, false, false);
		return Translator.translate(new ProgramRequest("stages", ProgramRequest.Family.FULL_SCREEN,
			List.of(new StageSource(Stage.VERTEX, VS, lines(VS)), new StageSource(Stage.FRAGMENT, FS, lines(FS))),
			ProgramRequest.VertexLayout.fullScreenQuad(), List.of(new ColorTarget(0, 0, 4)),
			List.of(
				new TextureBinding("ftex", 0, TextureKind.COLOUR, -1, null, 0),
				new TextureBinding("vtex", 1, TextureKind.COLOUR, -1, null, 1),
				new TextureBinding("far", 17, TextureKind.COLOUR, -1, null, 2)),
			List.of(), List.of(), UniformSchema.none(), null, false));
	}

	@Test
	void eachStageNumbersItsOwnSamplers() {
		ProgramTranslation t = translate();
		assertTrue(t.ok(), "the program translates: " + t.diagnostics());
		// The vertex stage declares vtex alone, so it is its first sampler; the fragment stage declares ftex, vtex, far in use order.
		assertEquals(0, t.reflection().samplerSlot("vtex", Stage.VERTEX), "vtex is the vertex stage's first sampler");
		assertEquals(1, t.reflection().samplerSlot("vtex", Stage.FRAGMENT), "vtex is the fragment stage's second sampler");
		assertEquals(0, t.reflection().samplerSlot("ftex", Stage.FRAGMENT));
		assertEquals(2, t.reflection().samplerSlot("far", Stage.FRAGMENT));
		assertEquals(-1, t.reflection().samplerSlot("ftex", Stage.VERTEX), "the vertex stage does not sample ftex");
		assertEquals(-1, t.reflection().samplerSlot("far", Stage.VERTEX), "the vertex stage does not sample far");
	}

	@Test
	void aTextureAtIndexSeventeenTakesASamplerBelowSixteen() {
		ProgramTranslation t = translate();
		assertEquals(17, t.reflection().textures().stream().filter(u -> u.name().equals("far")).findFirst()
			.orElseThrow().slot(), "far is the texture at index 17");
		int slot = t.reflection().samplerSlot("far", Stage.FRAGMENT);
		assertTrue(slot >= 0 && slot < 16, "far's sampler is a slot a Metal stage has, not index 17: " + slot);
		assertTrue(t.msl().contains("[[sampler(" + slot + ")]]"), "the fragment function declares sampler(" + slot + ")");
	}

	/**
	 * A texture the fragment only fetches (texelFetch) at index 9, between a sampled texture at 0 and one at 17: its sampler is
	 * still a stage sampler (the game's sampler), numbered in the stage's own order, so the slots are 0, 1, 2, not the indices.
	 */
	@Test
	void aFetchOnlyTextureBetweenSampledOnesGetsTheNextStageSlot() {
		String fs = """
			#version 130
			uniform sampler2D ftex;
			uniform sampler2D fetch;
			uniform sampler2D far;
			out vec4 color;
			void main() {
				color = texture2D(ftex, gl_FragCoord.xy) + texelFetch(fetch, ivec2(0), 0) + texture2D(far, gl_FragCoord.xy);
			}
			""";
		SamplerState nearest = new SamplerState(Filter.NEAREST, null, Wrap.CLAMP_TO_EDGE, false, false);
		ProgramTranslation t = Translator.translate(new ProgramRequest("fetch", ProgramRequest.Family.FULL_SCREEN,
			List.of(new StageSource(Stage.VERTEX, VS_NO_SAMPLERS, lines(VS_NO_SAMPLERS)),
				new StageSource(Stage.FRAGMENT, fs, lines(fs))),
			ProgramRequest.VertexLayout.fullScreenQuad(), List.of(new ColorTarget(0, 0, 4)),
			List.of(
				new TextureBinding("ftex", 0, TextureKind.COLOUR, -1, null, 0),
				new TextureBinding("fetch", 9, TextureKind.COLOUR, -1, null, 1),
				new TextureBinding("far", 17, TextureKind.COLOUR, -1, null, 2)),
			List.of(), List.of(), UniformSchema.none(), null, false));
		assertTrue(t.ok(), "the program translates: " + t.diagnostics());
		assertEquals(0, t.reflection().samplerSlot("ftex", Stage.FRAGMENT));
		assertEquals(1, t.reflection().samplerSlot("fetch", Stage.FRAGMENT), "the fetch-only texture takes slot 1, not its index 9");
		assertEquals(2, t.reflection().samplerSlot("far", Stage.FRAGMENT), "far takes slot 2, not its index 17");
		assertTrue(t.msl().contains("[[texture(9)]]") && t.msl().contains("[[sampler(1)]]"), "the MSL reads fetch at texture 9 with sampler 1");
	}

	private static final String VS_NO_SAMPLERS = """
		#version 130
		void main() {
			gl_Position = vec4(0.0, 0.0, 0.5, 1.0);
		}
		""";
}
