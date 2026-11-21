// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal.translator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.SamplerState;
import dev.mcms.glsl.msl.ProgramRequest.TextureBinding;
import dev.mcms.glsl.msl.ProgramRequest.TextureKind;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.sema.Stage;
import dev.mcms.shaders.metal.HeadlessGpu;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * E6-B: a fragment stage that samples twenty textures draws, and every texture's texel reaches the sum. The twenty share one
 * sampler state, so they take one constexpr sampler (one per distinct state; SamplerStatesTest counts the states). The
 * distinct-state case at the limit of sixteen is in SamplerStatesGpuTest.
 */
@Tag("gpu")
final class WideSampledGpuTest {

	private static final int TEXTURES = 20;

	private static final String VS = """
		#version 120
		varying vec2 uv;
		void main() {
			gl_Position = ftransform();
			uv = gl_MultiTexCoord0.xy;
		}
		""";

	static String fragment() {
		StringBuilder fs = new StringBuilder("#version 120\nvarying vec2 uv;\n");
		for (int i = 0; i < TEXTURES; i++) {
			fs.append("uniform sampler2D t").append(i).append(";\n");
		}
		fs.append("void main() {\n\tfloat sum = 0.0;\n");
		for (int i = 0; i < TEXTURES; i++) {
			fs.append("\tsum += texture2D(t").append(i).append(", uv).r;\n");
		}
		fs.append("\tgl_FragData[0] = vec4(sum, 0.0, 0.0, 1.0);\n}\n");
		return fs.toString();
	}

	@Test
	void twentySampledTexturesInOneStageDrawAndEveryTextureIsBound() {
		List<TextureBinding> textures = new ArrayList<>();
		for (int i = 0; i < TEXTURES; i++) {
			textures.add(TextureBinding.constexpr("t" + i, i, TextureKind.COLOUR, SamplerState.linear()));
		}
		ProgramRequest request = new ProgramRequest("wide", ProgramRequest.Family.FULL_SCREEN,
			List.of(TranslateSupport.stage(Stage.VERTEX, VS), TranslateSupport.stage(Stage.FRAGMENT, fragment())),
			ProgramRequest.VertexLayout.fullScreenQuad(), List.of(new ColorTarget(0, 0, 4)), textures, List.of(), List.of(),
			UniformSchema.none(), null, false);
		ProgramTranslation t = TranslateSupport.translateOk(request);
		HeadlessGpu.InputTexture[] inputs = new HeadlessGpu.InputTexture[TEXTURES];
		for (int i = 0; i < TEXTURES; i++) {
			// Texture i holds one texel with red i + 1 (normalised to (i+1)/255): the sum over all twenty is 210/255.
			inputs[i] = HeadlessGpu.InputTexture.tex2d(HeadlessGpu.RGBA8_UNORM, 1, 1,
				new byte[] {(byte) (i + 1), 0, 0, (byte) 255});
		}
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			byte[][] out = gpu.renderFullscreen(t.msl(), t.vertexEntry(), t.fragmentEntry(),
				new int[] {HeadlessGpu.RGBA8_UNORM}, 4, 4, inputs, new byte[0], new byte[0][]);
			assertEquals(210, out[0][0] & 0xff, "the sum of the twenty texels\n" + t.msl());
		}
	}
}
