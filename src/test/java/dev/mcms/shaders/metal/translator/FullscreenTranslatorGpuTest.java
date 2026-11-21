// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal.translator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.SamplerState;
import dev.mcms.glsl.msl.ProgramRequest.TextureBinding;
import dev.mcms.glsl.msl.ProgramRequest.TextureKind;
import dev.mcms.glsl.msl.ProgramRequest.UniformBlock;
import dev.mcms.glsl.msl.ProgramRequest.UniformField;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.sema.Stage;
import dev.mcms.shaders.metal.HeadlessGpu;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("gpu")
final class FullscreenTranslatorGpuTest {

	private static final String VS = """
		#version 120
		varying vec2 uv;
		void main() {
			gl_Position = ftransform();
			uv = gl_MultiTexCoord0.xy;
		}
		""";

	private static final String FS = """
		#version 120
		uniform sampler2D tex;
		uniform float scale;
		varying vec2 uv;
		void main() {
			gl_FragData[0] = texture2D(tex, uv) * scale;
		}
		""";

	@Test
	void fullScreenPassSamplesATextureAndReadsAUniformFromTheEngineBlock() {
		ProgramRequest request = new ProgramRequest("fullscreen", ProgramRequest.Family.FULL_SCREEN,
			List.of(TranslateSupport.stage(Stage.VERTEX, VS), TranslateSupport.stage(Stage.FRAGMENT, FS)),
			ProgramRequest.VertexLayout.fullScreenQuad(),
			List.of(new ColorTarget(0, 0, 4)),
			List.of(TextureBinding.constexpr("tex", 0, TextureKind.COLOUR, SamplerState.linear())),
			List.of(),
			List.of(),
			new UniformSchema(List.of(new UniformBlock("frame", 0, List.of(new UniformField("scale", "float", 0))))),
			null, false);
		ProgramTranslation t = TranslateSupport.translateOk(request);
		byte[] frame = new byte[16];
		ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).putFloat(0, 1.0f);
		byte[] texel = {(byte) 64, (byte) 128, (byte) 255, (byte) 255};
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			byte[][] out = gpu.renderFullscreen(t.msl(), t.vertexEntry(), t.fragmentEntry(),
				new int[] {HeadlessGpu.RGBA8_UNORM}, 4, 4,
				new HeadlessGpu.InputTexture[] {HeadlessGpu.InputTexture.tex2d(HeadlessGpu.RGBA8_UNORM, 1, 1, texel)},
				frame, new byte[0][]);
			for (int i = 0; i < 4; i++) {
				assertEquals(texel[i] & 0xff, out[0][i] & 0xff, "channel " + i + "\n" + t.msl());
			}
		}
	}

	/**
	 * A pass that scales gl_Position to a part of the screen covers exactly that rectangle, as with the quad OptiFine and
	 * Iris draw: Bliss 2.1.2 renders its clouds and light at half resolution this way ({@code (pos * 0.5 + 0.5) * s * 2 - 1}).
	 * The one large triangle the engine drew before covered a triangle past the rectangle (E7, Bliss's colortex0).
	 */
	@Test
	void aScaledPassCoversExactlyItsRectangle() {
		String vs = """
			#version 120
			void main() {
				gl_Position = ftransform();
				gl_Position.xy = (gl_Position.xy * 0.5 + 0.5) * 0.5 * 2.0 - 1.0;
			}
			""";
		String fs = """
			#version 120
			void main() {
				gl_FragData[0] = vec4(1.0);
			}
			""";
		ProgramRequest request = new ProgramRequest("scaled", ProgramRequest.Family.FULL_SCREEN,
			List.of(TranslateSupport.stage(Stage.VERTEX, vs), TranslateSupport.stage(Stage.FRAGMENT, fs)),
			ProgramRequest.VertexLayout.fullScreenQuad(), List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(),
			UniformSchema.none(), null, false);
		ProgramTranslation t = TranslateSupport.translateOk(request);
		int size = 8;
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			byte[][] out = gpu.renderFullscreenCleared(t.msl(), t.vertexEntry(), t.fragmentEntry(),
				new int[] {HeadlessGpu.RGBA8_UNORM}, size, size, new HeadlessGpu.InputTexture[0], new byte[16], new byte[0][],
				new float[] {0f, 0f, 0f, 0f});
			for (int y = 0; y < size; y++) {
				for (int x = 0; x < size; x++) {
					// Rows are stored from the bottom (the engine's Y flip): the rectangle is x and y below half the size.
					boolean inside = x < size / 2 && y < size / 2;
					assertEquals(inside ? 255 : 0, out[0][(y * size + x) * 4] & 0xff, x + "," + y);
				}
			}
		}
	}
}
