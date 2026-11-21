// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.uniforms;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.AlphaTest;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.glsl.Translator;

/**
 * The frame block served to a translated full-screen program on the GPU (plan.md E3): a {@code #version 120} program
 * that declares the uniforms by their GLSL names reads them through the translator's field lookup, and the pixels hold
 * the block's values (fogColour, screen values, the sun's view-space position, the projection's m22 and the alpha-test
 * reference with the translator's alpha test discarding below it).
 */
@Tag("gpu")
final class FrameBlockGpuTest {
	private static final int W = 16, H = 16;
	private static final double TOL = 1e-4;

	private static final String VS = """
		#version 120
		void main() {
			gl_Position = ftransform();
		}
		""";

	/** Each pixel holds one frame value the pack reads by its documented name. */
	private static final String FS = """
		#version 120
		uniform vec3 fogColor;
		uniform float viewWidth;
		uniform float aspectRatio;
		uniform float near;
		uniform float far;
		uniform vec3 sunPosition;
		uniform mat4 gbufferProjection;
		uniform ivec2 eyeBrightness;
		uniform int isEyeInWater;
		uniform float frameTimeCounter;
		void main() {
			gl_FragData[0] = vec4(fogColor, viewWidth / 1920.0);
			gl_FragData[1] = vec4(aspectRatio, near * 20.0, far / 192.0, frameTimeCounter);
			gl_FragData[2] = vec4(sunPosition / 100.0, gbufferProjection[2][2]);
			gl_FragData[3] = vec4(float(eyeBrightness.x) / 240.0, float(eyeBrightness.y) / 240.0,
				float(isEyeInWater), 1.0);
		}
		""";

	private static ProgramRequest request(UniformSchema schema, AlphaTest alpha) {
		List<ColorTarget> outs = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			outs.add(new ColorTarget(i, i, 4));
		}
		return new ProgramRequest("frame-block", ProgramRequest.Family.FULL_SCREEN,
			List.of(stage(Stage.VERTEX, VS), stage(Stage.FRAGMENT, FS)),
			ProgramRequest.VertexLayout.fullScreenQuad(), outs, List.of(), List.of(), List.of(), schema, alpha, false);
	}

	private static ProgramRequest.StageSource stage(Stage stage, String text) {
		int n = text.split("\n", -1).length;
		return new ProgramRequest.StageSource(stage, text,
			java.util.stream.IntStream.rangeClosed(1, n).mapToObj(i -> new SourceLine("frame", i)).toList());
	}

	@Test
	void aTranslatedProgramReadsTheFrameBlock() {
		FrameUniforms u = new FrameUniforms();
		var in = new FrameUniformsTest.TestInputs();
		in.eyeFluid = 1;
		in.eyeBlockLight = 15;
		in.eyeSkyLight = 12;
		u.advance(in.build(), 0.016f, -1f);
		ProgramTranslation t = Translator.translate(request(
			new UniformSchema(List.of(FrameUniforms.schema(0))), null));
		assertTrue(t.diagnostics().isEmpty(), String.join("\n", t.diagnostics().stream().map(Object::toString).toList()));
		byte[] uniforms = new byte[FrameUniforms.size()];
		u.write(ByteBuffer.wrap(uniforms).order(ByteOrder.LITTLE_ENDIAN));
		int[] formats = new int[4];
		java.util.Arrays.fill(formats, HeadlessGpu.RGBA32_FLOAT);
		byte[][] px;
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			px = gpu.renderFullscreenCleared(t.msl(), t.vertexEntry(), t.fragmentEntry(), formats, W, H, null,
				uniforms, null, new float[] { -1, -1, -1, -1 });
		}
		List<String> problems = new ArrayList<>();
		float[][] want = {
			{ 0.7f, 0.8f, 0.9f, 1f },
			{ 1920f / 1080f, 0.05f * 20f, 192f / 192f, 0.016f },
			{ 0f, 100f / 100f, 0f, -1.0001f },
			{ 240f / 240f, 192f / 240f, 1f, 1f } };
		for (int target = 0; target < 4; target++) {
			for (int ch = 0; ch < 4; ch++) {
				double got = ByteBuffer.wrap(px[target]).order(ByteOrder.LITTLE_ENDIAN)
					.getFloat(((0 * W) + 0) * 16 + ch * 4);
				if (Math.abs(got - want[target][ch]) > TOL && problems.size() < 8) {
					problems.add(String.format("target %d component %d: got %.6f, expected %.6f", target, ch, got,
						want[target][ch]));
				}
			}
		}
		assertTrue(problems.isEmpty(), String.join("\n", problems) + "\n" + t.msl());
	}
}
