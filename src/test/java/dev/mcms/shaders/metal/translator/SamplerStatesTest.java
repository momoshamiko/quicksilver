// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal.translator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.Filter;
import dev.mcms.glsl.msl.ProgramRequest.SamplerState;
import dev.mcms.glsl.msl.ProgramRequest.TextureBinding;
import dev.mcms.glsl.msl.ProgramRequest.TextureKind;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.msl.ProgramRequest.Wrap;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.Translator;
import dev.mcms.glsl.sema.Stage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pack textures take constexpr samplers, one per distinct sampler state (Metal counts each declared inline sampler toward
 * its limit of 16, with the game's sampler slots). These tests read the translation, not the device.
 */
final class SamplerStatesTest {

	/** Every combination of filter, mip filter and wrap: 2 x 3 x 3 = 18 distinct states without compare or pixel addressing. */
	static List<SamplerState> distinctStates() {
		List<SamplerState> out = new ArrayList<>();
		for (Filter filter : Filter.values()) {
			for (Filter mip : new Filter[] {null, Filter.NEAREST, Filter.LINEAR}) {
				for (Wrap wrap : Wrap.values()) {
					out.add(new SamplerState(filter, mip, wrap, false, false));
				}
			}
		}
		return out;
	}

	static String fragment(int textures) {
		StringBuilder fs = new StringBuilder("#version 120\nvarying vec2 uv;\n");
		for (int i = 0; i < textures; i++) {
			fs.append("uniform sampler2D t").append(i).append(";\n");
		}
		fs.append("void main() {\n\tfloat sum = 0.0;\n");
		for (int i = 0; i < textures; i++) {
			fs.append("\tsum += texture2D(t").append(i).append(", uv).r;\n");
		}
		fs.append("\tgl_FragData[0] = vec4(sum, 0.0, 0.0, 1.0);\n}\n");
		return fs.toString();
	}

	static ProgramRequest request(List<SamplerState> stateOfTexture) {
		List<TextureBinding> textures = new ArrayList<>();
		for (int i = 0; i < stateOfTexture.size(); i++) {
			textures.add(TextureBinding.constexpr("t" + i, i, TextureKind.COLOUR, stateOfTexture.get(i)));
		}
		return new ProgramRequest("states", ProgramRequest.Family.FULL_SCREEN,
			List.of(TranslateSupport.stage(Stage.VERTEX, VS), TranslateSupport.stage(Stage.FRAGMENT, fragment(stateOfTexture.size()))),
			ProgramRequest.VertexLayout.fullScreenQuad(), List.of(new ColorTarget(0, 0, 4)), textures, List.of(), List.of(),
			UniformSchema.none(), null, false);
	}

	static final String VS = """
		#version 120
		varying vec2 uv;
		void main() {
			gl_Position = ftransform();
			uv = gl_MultiTexCoord0.xy;
		}
		""";

	private static int declarations(String msl) {
		int n = 0;
		for (String line : msl.split("\n")) {
			if (line.startsWith("constexpr sampler ")) {
				n++;
			}
		}
		return n;
	}

	@Test
	void sixteenDistinctStatesAreSixteenConstexprSamplersAndTranslate() {
		List<SamplerState> states = distinctStates().subList(0, 16);
		ProgramTranslation t = Translator.translate(request(states));
		assertTrue(t.ok(), t.diagnostics().toString());
		assertEquals(16, declarations(t.msl()), t.msl());
	}

	@Test
	void texturesThatShareAStateShareOneConstexprSampler() {
		// Twenty textures, two states: twenty textures no longer need twenty declarations.
		List<SamplerState> states = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			states.add(i % 2 == 0 ? SamplerState.linear() : new SamplerState(Filter.NEAREST, null, Wrap.REPEAT, false, false));
		}
		ProgramTranslation t = Translator.translate(request(states));
		assertTrue(t.ok(), t.diagnostics().toString());
		assertEquals(2, declarations(t.msl()), t.msl());
	}

	@Test
	void seventeenDistinctStatesAreRefusedAgainstMetalsLimitOfSixteen() {
		List<SamplerState> states = distinctStates().subList(0, 17);
		ProgramTranslation t = Translator.translate(request(states));
		assertFalse(t.ok(), "17 constexpr samplers are above the limit of 16");
		assertTrue(t.diagnostics().stream().anyMatch(d -> d.code().equals("sampler-limit")), t.diagnostics().toString());
	}
}
