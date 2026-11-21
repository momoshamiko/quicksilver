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
import dev.mcms.shaders.metal.HeadlessGpu.InputTexture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Translations whose wrong MSL would still compile (the silent wrong results of the E2 review): each program's
 * values are the ones GLSL defines, read back from the GPU. Every program draws the full-screen quad from the vertex
 * id (its own vertex stage, so the quad contract is not under test here) and writes colour target 0 (RGBA32F) unless
 * the test says otherwise.
 */
@Tag("gpu")
final class SilentWrongGpuTest {

	private static final int W = 8, H = 8;

	/** The full-screen triangle from the vertex id; uv runs 0 to 1 over the window (corner (0,0) bottom left). */
	private static final String VS_ID = """
		#version 130
		out vec2 uv;
		void main() {
			vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
			gl_Position = vec4(corner * 2.0 - 1.0, 0.5, 1.0);
			uv = corner;
		}
		""";

	private static ProgramRequest request(String vs, String fs, int targets, List<TextureBinding> textures) {
		List<ColorTarget> outs = new ArrayList<>();
		for (int i = 0; i < targets; i++) {
			outs.add(new ColorTarget(i, i, 4));
		}
		return new ProgramRequest("silent", ProgramRequest.Family.FULL_SCREEN,
			List.of(TranslateSupport.stage(Stage.VERTEX, vs), TranslateSupport.stage(Stage.FRAGMENT, fs)),
			ProgramRequest.VertexLayout.fullScreenQuad(), outs, textures, List.of(), List.of(),
			UniformSchema.none(), null, false);
	}

	private static byte[][] render(ProgramTranslation t, int targets, InputTexture[] textures) {
		int[] formats = new int[targets];
		Arrays.fill(formats, HeadlessGpu.RGBA32_FLOAT);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			return gpu.renderFullscreenCleared(t.msl(), t.vertexEntry(), t.fragmentEntry(), formats, W, H, textures,
				new byte[16], null, new float[] {-1, -1, -1, -1});
		}
	}

	/** Checks every pixel of target 0 against want(c, r, ch); the first few mismatches go into the message. */
	private static void expectEveryPixel(byte[] pixels, float[] want, ProgramTranslation t) {
		ByteBuffer bb = ByteBuffer.wrap(pixels).order(ByteOrder.LITTLE_ENDIAN);
		List<String> bad = new ArrayList<>();
		for (int r = 0; r < H; r++) {
			for (int c = 0; c < W; c++) {
				for (int ch = 0; ch < 4; ch++) {
					float got = bb.getFloat(((r * W) + c) * 16 + ch * 4);
					if (!(Math.abs(got - want[ch]) <= 1e-4f) && bad.size() < 5) {
						bad.add(String.format("pixel (%d,%d) component %d: got %.6f, expected %.6f", c, r, ch, got,
							want[ch]));
					}
				}
			}
		}
		assertEquals(List.of(), bad, String.join("\n", bad) + "\n" + t.msl());
	}

	/** Item 6: two stages each declare a program-scope constant array of one name with different values. */
	@Test
	void constantArraysOfTheSameNameStayStageLocal() {
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
		ProgramTranslation t = TranslateSupport.translateOk(request(vs, fs, 1, List.of()));
		expectEveryPixel(render(t, 1, null)[0], new float[] {1, 5, 0, 1}, t);
	}

	/** Item 7: a fragment's varying is an input; it takes no output location, so colour output 0 is still colour. */
	@Test
	void aFragmentVaryingTakesNoOutputLocation() {
		String vs = """
			#version 130
			out vec2 uv;
			void main() {
				vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
				gl_Position = vec4(corner * 2.0 - 1.0, 0.5, 1.0);
				uv = corner;
			}
			""";
		String fs = """
			#version 130
			varying vec2 uv;
			out vec4 color;
			void main() {
				color = vec4(uv, 0.25, 1.0);
			}
			""";
		ProgramTranslation t = TranslateSupport.translateOk(request(vs, fs, 1, List.of()));
		byte[] px = render(t, 1, null)[0];
		// uv at pixel (c, r) is the pixel centre over the window: ((c + 0.5) / W, (r + 0.5) / H).
		ByteBuffer bb = ByteBuffer.wrap(px).order(ByteOrder.LITTLE_ENDIAN);
		List<String> bad = new ArrayList<>();
		for (int r = 0; r < H; r++) {
			for (int c = 0; c < W; c++) {
				float[] want = {(c + 0.5f) / W, (r + 0.5f) / H, 0.25f, 1f};
				for (int ch = 0; ch < 4; ch++) {
					float got = bb.getFloat(((r * W) + c) * 16 + ch * 4);
					if (Math.abs(got - want[ch]) > 1e-3f && bad.size() < 5) {
						bad.add(String.format("pixel (%d,%d) component %d: got %.6f, expected %.6f", c, r, ch, got,
							want[ch]));
					}
				}
			}
		}
		assertEquals(List.of(), bad, String.join("\n", bad) + "\n" + t.msl());
	}

	/** Item 8: a local named like a global's member (foo, and the global foo_0) must not shadow it. */
	@Test
	void aLocalNamedLikeAGlobalDoesNotShadowIt() {
		String fs = """
			#version 130
			float foo_0 = 3.0;
			out vec4 color;
			void main() {
				float foo = 2.0;
				color = vec4(foo_0, foo, 0.0, 1.0);
			}
			""";
		ProgramTranslation t = TranslateSupport.translateOk(request(VS_ID, fs, 1, List.of()));
		expectEveryPixel(render(t, 1, null)[0], new float[] {3, 2, 0, 1}, t);
	}

	/**
	 * Item 9: an out argument in an if condition, a while condition and a for condition is written back before the
	 * statement's body runs, and re-evaluated on every iteration of a loop.
	 */
	@Test
	void outArgumentsInConditionsAreWrittenBackBeforeTheBody() {
		String fs = """
			#version 130
			bool bump(out float x) {
				x = x + 1.0;
				return x < 3.0;
			}
			out vec4 color;
			void main() {
				vec2 vv = vec2(0.0, 0.0);
				float total = 0.0;
				while (bump(vv.x)) {
					total += vv.x;
				}
				vec2 ww = vec2(0.0, 0.0);
				float got = -1.0;
				if (bump(ww.x)) {
					got = ww.x;
				} else {
					got = -2.0;
				}
				vec2 uu = vec2(0.0, 0.0);
				float acc = 0.0;
				for (int i = 0; bump(uu.x); i++) {
					acc += uu.x;
				}
				color = vec4(total, got, acc, 1.0);
			}
			""";
		ProgramTranslation t = TranslateSupport.translateOk(request(VS_ID, fs, 1, List.of()));
		// while: v = 1 and 2 are in the body (total 3); if: w = 1; for: u = 1 and 2 (acc 3).
		expectEveryPixel(render(t, 1, null)[0], new float[] {3, 1, 3, 1}, t);
	}

	/** Item 10: a rectangle sampler reads texel coordinates (coord::pixel), not normalized ones. */
	@Test
	void aRectangleSamplerReadsPixelCoordinates() {
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
		ProgramTranslation t = TranslateSupport.translateOk(request(VS_ID, fs, 1,
			List.of(new TextureBinding("tex", 0, TextureKind.COLOUR, -1, pixel, -1))));
		byte[] texels = new byte[4 * 4 * 4];
		for (int y = 0; y < 4; y++) {
			for (int x = 0; x < 4; x++) {
				int o = (y * 4 + x) * 4;
				texels[o] = (byte) (x * 60);
				texels[o + 1] = (byte) (y * 60);
				texels[o + 2] = (byte) 128;
				texels[o + 3] = (byte) 255;
			}
		}
		// Texel (1, 2) is (60, 120, 128, 255); a normalized lookup at (1.5, 2.5) would read texel (3, 3).
		expectEveryPixel(render(t, 1, new InputTexture[] {InputTexture.tex2d(HeadlessGpu.RGBA8_UNORM, 4, 4, texels)})[0],
			new float[] {60 / 255f, 120 / 255f, 128 / 255f, 1f}, t);
	}

	/** Item 11: gl_FragColor is written to every colour target (GLSL: it goes to all draw buffers). */
	@Test
	void gl_FragColorBroadcastsToEveryColourTarget() {
		String fs = """
			#version 120
			void main() {
				gl_FragColor = vec4(0.25, 0.5, 0.75, 1.0);
			}
			""";
		ProgramTranslation t = TranslateSupport.translateOk(request(VS_ID, fs, 2, List.of()));
		byte[][] px = render(t, 2, null);
		expectEveryPixel(px[0], new float[] {0.25f, 0.5f, 0.75f, 1f}, t);
		expectEveryPixel(px[1], new float[] {0.25f, 0.5f, 0.75f, 1f}, t);
	}
}
