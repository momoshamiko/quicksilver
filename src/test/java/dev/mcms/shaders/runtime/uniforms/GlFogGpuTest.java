// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.uniforms;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import dev.mcms.shaders.metal.HeadlessGpu;

/**
 * gl_Fog and the documented fog uniforms on the GPU (plan.md E4). A vertex stage reads gl_Fog.end and a fragment stage
 * reads every gl_Fog member next to fogStart, fogEnd, fogDensity, fogShape, fogMode and fogColor; the pixels hold the
 * frame's fog state. With the environmental range closer the frame reports it (start 10, end 100, sphere); with the
 * render-distance range closer it reports that one (start 90, end 192, cylinder).
 */
@Tag("gpu")
final class GlFogGpuTest {
	private static final int W = 16, H = 16, TARGETS = 4;
	private static final double TOL = 1e-4;

	/** The vertex stage reads gl_Fog.end and passes it on, so a vertex-stage read of the fog is served too. */
	private static final String VS = """
		#version 120
		varying float vFogEnd;
		void main() {
			gl_Position = ftransform();
			vFogEnd = gl_Fog.end;
		}
		""";

	private static final String FS = """
		#version 120
		varying float vFogEnd;
		uniform vec3 fogColor;
		uniform float fogStart;
		uniform float fogEnd;
		uniform float fogDensity;
		uniform int fogMode;
		uniform int fogShape;
		void main() {
			gl_FragData[0] = vec4(gl_Fog.color.rgb, gl_Fog.density);
			gl_FragData[1] = vec4(gl_Fog.start, vFogEnd, gl_Fog.scale, gl_Fog.color.a);
			gl_FragData[2] = vec4(fogStart, fogEnd, fogDensity, float(fogShape));
			gl_FragData[3] = vec4(fogColor, float(fogMode));
		}
		""";

	@Test
	void glFogAndTheDocumentedFogUniformsHoldTheFrameFogWithEitherRange() {
		ProgramTranslation t = translate();
		FrameUniforms u = new FrameUniforms();
		var in = new FrameUniformsTest.TestInputs();
		u.advance(in.build(), 0.016f, -1f);
		List<String> problems = new ArrayList<>();
		check(problems, "environmental range", render(t, u), new float[][] {
			{ 0.7f, 0.8f, 0.9f, 0f },
			{ 10f, 100f, 1f / 90f, 1f },
			{ 10f, 100f, 0f, 0f },
			{ 0.7f, 0.8f, 0.9f, 9729f } });

		in.fogEnvironmentalStart = 200f;
		in.fogEnvironmentalEnd = 300f;
		u.advance(in.build(), 0.016f, -1f);
		check(problems, "render-distance range", render(t, u), new float[][] {
			{ 0.7f, 0.8f, 0.9f, 0f },
			{ 90f, 192f, 1f / 102f, 1f },
			{ 90f, 192f, 0f, 1f },
			{ 0.7f, 0.8f, 0.9f, 9729f } });
		assertTrue(problems.isEmpty(), String.join("\n", problems) + "\n" + t.msl());
	}

	private static void check(List<String> problems, String state, float[][] got, float[][] want) {
		for (int target = 0; target < want.length; target++) {
			for (int ch = 0; ch < 4; ch++) {
				if (Math.abs(got[target][ch] - want[target][ch]) > TOL) {
					problems.add(String.format("%s, target %d component %d: got %.6f, expected %.6f", state, target, ch,
						got[target][ch], want[target][ch]));
				}
			}
		}
	}

	private static ProgramTranslation translate() {
		List<ColorTarget> outs = new ArrayList<>();
		for (int i = 0; i < TARGETS; i++) {
			outs.add(new ColorTarget(i, i, 4));
		}
		ProgramRequest request = new ProgramRequest("gl-fog", ProgramRequest.Family.FULL_SCREEN,
			List.of(stage(Stage.VERTEX, VS), stage(Stage.FRAGMENT, FS)), ProgramRequest.VertexLayout.fullScreenQuad(),
			outs, List.of(), List.of(), List.of(), new UniformSchema(List.of(FrameUniforms.schema(0))), null, false);
		ProgramTranslation t = Translator.translate(request);
		assertTrue(t.diagnostics().isEmpty(), String.join("\n", t.diagnostics().stream().map(Object::toString).toList()));
		return t;
	}

	/** The first pixel of every target: one row of four floats per target. */
	private static float[][] render(ProgramTranslation t, FrameUniforms u) {
		byte[] uniforms = new byte[FrameUniforms.size()];
		u.write(ByteBuffer.wrap(uniforms).order(ByteOrder.LITTLE_ENDIAN));
		int[] formats = new int[TARGETS];
		java.util.Arrays.fill(formats, HeadlessGpu.RGBA32_FLOAT);
		byte[][] px;
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			px = gpu.renderFullscreenCleared(t.msl(), t.vertexEntry(), t.fragmentEntry(), formats, W, H, null,
				uniforms, null, new float[] { -1, -1, -1, -1 });
		}
		float[][] out = new float[TARGETS][4];
		for (int target = 0; target < TARGETS; target++) {
			for (int ch = 0; ch < 4; ch++) {
				out[target][ch] = ByteBuffer.wrap(px[target]).order(ByteOrder.LITTLE_ENDIAN).getFloat(ch * 4);
			}
		}
		return out;
	}

	private static ProgramRequest.StageSource stage(Stage stage, String text) {
		int n = text.split("\n", -1).length;
		return new ProgramRequest.StageSource(stage, text,
			java.util.stream.IntStream.rangeClosed(1, n).mapToObj(i -> new SourceLine("gl-fog", i)).toList());
	}
}
