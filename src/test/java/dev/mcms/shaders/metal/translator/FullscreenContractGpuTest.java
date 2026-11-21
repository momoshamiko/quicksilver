// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal.translator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.AlphaTest;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.UniformBlock;
import dev.mcms.glsl.msl.ProgramRequest.UniformField;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.sema.Stage;
import dev.mcms.shaders.metal.HeadlessGpu;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The full-screen quad contract (plan.md E3, ADR 0006 section 2), measured on the GPU against analytic answers. The
 * quad covers the window: gl_Vertex and gl_MultiTexCoord0 run from 0 to 1 over the screen with (0, 0) at the bottom left,
 * gl_ProjectionMatrix maps [0,1]^2 to clip space and gl_ModelViewMatrix is the identity. A target holds OpenGL's
 * orientation in memory: row r is window row y = r (y up, row 0 the bottom row). For a pixel of column c and row r the
 * answers are gl_FragCoord = (c + 0.5, r + 0.5) and texcoord = ((c + 0.5) / W, (r + 0.5) / H), dFdx(texcoord) =
 * (1 / W, 0) and dFdy(texcoord) = (0, 1 / H) (GLSL: the difference toward increasing window y).
 */
@Tag("gpu")
final class FullscreenContractGpuTest {

	private static final int W = 64, H = 48;
	private static final double TOL = 1e-4;
	private static final int RGBA32 = HeadlessGpu.RGBA32_FLOAT;

	/** The pass-through vertex stage of the full-screen quad: the position and texcoord the contract defines. */
	private static final String VS_FTRANSFORM = """
		#version 120
		varying vec2 uv;
		void main() {
			gl_Position = ftransform();
			uv = gl_MultiTexCoord0.xy;
		}
		""";

	private static final String FS_FRAGCOORD_TEXCOORD = """
		#version 120
		varying vec2 uv;
		void main() {
			gl_FragData[0] = vec4(gl_FragCoord.xy, uv);
		}
		""";

	private static final String FS_DERIVATIVES = """
		#version 120
		varying vec2 uv;
		void main() {
			gl_FragData[0] = vec4(dFdx(uv), dFdy(uv));
		}
		""";

	/** The 1.17+ names: vaPosition (vec3), vaUV0 (vec2) and the pack-declared projectionMatrix and modelViewMatrix. */
	private static final String VS_17 = """
		#version 130
		in vec3 vaPosition;
		in vec2 vaUV0;
		out vec2 uv;
		uniform mat4 projectionMatrix;
		uniform mat4 modelViewMatrix;
		void main() {
			gl_Position = projectionMatrix * modelViewMatrix * vec4(vaPosition, 1.0);
			uv = vaUV0;
		}
		""";

	private static final String FS_17 = """
		#version 130
		in vec2 uv;
		void main() {
			gl_FragData[0] = vec4(gl_FragCoord.xy, uv);
		}
		""";

	/** A counter-clockwise triangle in clip space (vertices by gl_VertexID), and the same triangle clockwise. */
	private static final String VS_CCW = """
		#version 130
		void main() {
			vec2 p;
			if (gl_VertexID == 0) p = vec2(-0.9, -0.5);
			else if (gl_VertexID == 1) p = vec2(-0.1, -0.5);
			else p = vec2(-0.5, 0.5);
			gl_Position = vec4(p, 0.5, 1.0);
		}
		""";

	private static final String VS_CW = """
		#version 130
		void main() {
			vec2 p;
			if (gl_VertexID == 0) p = vec2(-0.9, -0.5);
			else if (gl_VertexID == 1) p = vec2(-0.5, 0.5);
			else p = vec2(-0.1, -0.5);
			gl_Position = vec4(p, 0.5, 1.0);
		}
		""";

	private static final String FS_FACING = """
		#version 130
		void main() {
			gl_FragData[0] = vec4(gl_FrontFacing ? 1.0 : 0.0, 0.0, 0.0, 1.0);
		}
		""";

	/** Texture matrices: gl_TextureMatrix[0] is the schema's matrix, [1] the lightmap matrix, [2] identity. */
	private static final String FS_TEXTURE_MATRIX = """
		#version 120
		void main() {
			gl_FragData[0] = gl_TextureMatrix[0] * vec4(1.0, 0.0, 0.0, 0.0);
			gl_FragData[1] = gl_TextureMatrixInverse[0] * vec4(0.0, 1.0, 0.0, 0.0);
			gl_FragData[2] = gl_TextureMatrix[1] * vec4(256.0, 0.0, 0.0, 1.0);
			gl_FragData[3] = gl_TextureMatrix[2] * vec4(0.25, 0.5, 0.75, 1.0);
			gl_FragData[4] = gl_TextureMatrixTranspose[0] * vec4(1.0, 0.0, 0.0, 0.0);
		}
		""";

	/** The alpha test's output is the pixel's alpha: (c + 0.5) / W. The test is the compatibility profile's, not the pack's. */
	private static final String FS_ALPHA = """
		#version 120
		void main() {
			gl_FragData[0] = vec4(0.0, 0.0, 0.0, gl_FragCoord.x / 64.0);
		}
		""";

	private static List<dev.mcms.glsl.sema.SourceLine> lines(String text) {
		int n = text.split("\n", -1).length;
		return java.util.stream.IntStream.rangeClosed(1, n).mapToObj(i -> new dev.mcms.glsl.sema.SourceLine("contract", i))
			.toList();
	}

	private static ProgramRequest.StageSource stage(Stage stage, String text) {
		return new ProgramRequest.StageSource(stage, text, lines(text));
	}

	private static ProgramRequest request(String vs, String fs, int targets, UniformSchema schema, AlphaTest alpha) {
		List<ColorTarget> outs = new ArrayList<>();
		for (int i = 0; i < targets; i++) {
			outs.add(new ColorTarget(i, i, 4));
		}
		return new ProgramRequest("contract", ProgramRequest.Family.FULL_SCREEN,
			List.of(stage(Stage.VERTEX, vs), stage(Stage.FRAGMENT, fs)), ProgramRequest.VertexLayout.fullScreenQuad(),
			outs, List.of(), List.of(), List.of(), schema, alpha, false);
	}

	private static ProgramTranslation translate(ProgramRequest r) {
		return TranslateSupport.translateOk(r);
	}

	/** Renders one full-screen draw of the translation into {@code targets} RGBA32F targets, cleared to clear. */
	private static byte[][] render(ProgramTranslation t, int targets, byte[] uniforms, float[] clear) {
		int[] formats = new int[targets];
		java.util.Arrays.fill(formats, RGBA32);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			return gpu.renderFullscreenCleared(t.msl(), t.vertexEntry(), t.fragmentEntry(), formats, W, H, null,
				uniforms, null, clear);
		}
	}

	private static float at(byte[] pixels, int c, int r, int ch) {
		return ByteBuffer.wrap(pixels).order(ByteOrder.LITTLE_ENDIAN).getFloat(((r * W) + c) * 16 + ch * 4);
	}

	/** Every pixel of the target whose four components are not the expected values (first few named). */
	private static List<String> mismatches(String what, byte[] pixels, int[] cols, int[] rows, ValueAt want) {
		List<String> out = new ArrayList<>();
		for (int r = rows[0]; r <= rows[1]; r++) {
			for (int c = cols[0]; c <= cols[1]; c++) {
				for (int ch = 0; ch < 4; ch++) {
					double expected = want.at(c, r, ch);
					double got = at(pixels, c, r, ch);
					if (!(Math.abs(got - expected) <= TOL) && out.size() < 6) {
						out.add(String.format("%s pixel (%d,%d) component %d: got %.6f, expected %.6f", what, c, r, ch,
							got, expected));
					}
				}
			}
		}
		return out;
	}

	private interface ValueAt {
		double at(int c, int r, int ch);
	}

	private static final int[] WHOLE_COLS = {0, W - 1}, WHOLE_ROWS = {0, H - 1};

	private static double fragCoordAndTexcoord(int c, int r, int ch) {
		return switch (ch) {
			case 0 -> c + 0.5;
			case 1 -> r + 0.5;
			case 2 -> (c + 0.5) / W;
			default -> (r + 0.5) / H;
		};
	}

	/** The quad's pixels hold gl_FragCoord and texcoord from the contract; gl_Position is ftransform(). */
	@Test
	void fullScreenQuadCoversTheWindowWithTexcoordAndFragCoordFromTheContract() {
		ProgramTranslation t = translate(request(VS_FTRANSFORM, FS_FRAGCOORD_TEXCOORD, 1, UniformSchema.none(), null));
		List<String> problems = mismatches("ftransform quad", render(t, 1, new byte[16], null)[0], WHOLE_COLS,
			WHOLE_ROWS, contractValues());
		assertTrue(problems.isEmpty(), String.join("\n", problems) + "\n" + t.msl());
	}

	/** The 1.17+ names (vaPosition, vaUV0, pack-declared projectionMatrix and modelViewMatrix) follow the same contract. */
	@Test
	void seventeenNamesSeeTheSameFullScreenQuad() {
		ProgramTranslation t = translate(request(VS_17, FS_17, 1, UniformSchema.none(), null));
		List<String> problems = mismatches("1.17 names", render(t, 1, new byte[16], null)[0], WHOLE_COLS, WHOLE_ROWS,
			contractValues());
		assertTrue(problems.isEmpty(), String.join("\n", problems) + "\n" + t.msl());
	}

	private static ValueAt contractValues() {
		return FullscreenContractGpuTest::fragCoordAndTexcoord;
	}

	/** dFdx and dFdy of texcoord: (1/W, 0) and (0, 1/H), with no sign flip in dFdy (GLSL: toward increasing window y). */
	@Test
	void derivativeOfTexcoordFollowsTheOpenGlConventionInBothAxes() {
		ProgramTranslation t = translate(request(VS_FTRANSFORM, FS_DERIVATIVES, 1, UniformSchema.none(), null));
		List<String> problems = mismatches("derivatives", render(t, 1, new byte[16], null)[0], WHOLE_COLS, WHOLE_ROWS,
			(c, r, ch) -> switch (ch) {
				case 0 -> 1.0 / W;
				case 1 -> 0.0;
				case 2 -> 0.0;
				default -> 1.0 / H;
			});
		assertTrue(problems.isEmpty(), String.join("\n", problems) + "\n" + t.msl());
	}

	/** A counter-clockwise triangle (clip space) is front facing; the same triangle clockwise is back facing. */
	@Test
	void counterClockwiseIsFrontFacingAndClockwiseIsNot() {
		// The triangle's centre pixels: column 16, row 20 (window), inside both triangles.
		int[] cols = {15, 17}, rows = {19, 21};
		ProgramTranslation ccw = translate(request(VS_CCW, FS_FACING, 1, UniformSchema.none(), null));
		List<String> front = mismatches("counter-clockwise", render(ccw, 1, new byte[16], new float[] {-1, -1, -1, -1})[0],
			cols, rows, (c, r, ch) -> ch == 0 ? 1.0 : ch == 3 ? 1.0 : 0.0);
		ProgramTranslation cw = translate(request(VS_CW, FS_FACING, 1, UniformSchema.none(), null));
		List<String> back = mismatches("clockwise", render(cw, 1, new byte[16], new float[] {-1, -1, -1, -1})[0], cols,
			rows, (c, r, ch) -> ch == 0 ? 0.0 : ch == 3 ? 1.0 : 0.0);
		assertTrue(front.isEmpty(), String.join("\n", front));
		assertTrue(back.isEmpty(), String.join("\n", back));
	}

	/** gl_TextureMatrix[0] from the schema, [1] the lightmap matrix (1/256 scale, 1/32 offset), [2] identity, Inverse and Transpose. */
	@Test
	void textureMatricesAreServedFromTheSchemaAndTheLightmapMatrix() {
		UniformBlock draw = new UniformBlock("QsDraw", 0, List.of(new UniformField("modelViewMatrix", "mat4", 0),
			new UniformField("projectionMatrix", "mat4", 64), new UniformField("textureMatrix", "mat4", 128)));
		ProgramTranslation t = translate(request(VS_FTRANSFORM, FS_TEXTURE_MATRIX, 5, new UniformSchema(List.of(draw)),
			null));
		// Column-major: T[1][0] = 2 (column 1, row 0), so T * (1,0,0,0) = (1,0,0,0) and Transpose(T) * (1,0,0,0) = (1,2,0,0).
		float[] tm = {1, 0, 0, 0, 2, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
		byte[] uniforms = new byte[192];
		ByteBuffer bb = ByteBuffer.wrap(uniforms).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < tm.length; i++) {
			bb.putFloat(128 + 4 * i, tm[i]);
		}
		byte[][] px = render(t, 5, uniforms, null);
		float[][] want = {{1, 0, 0, 0}, {-2, 1, 0, 0}, {1 + 1f / 32, 1f / 32, 1f / 32, 1}, {0.25f, 0.5f, 0.75f, 1}, {1, 2, 0, 0}};
		List<String> problems = new ArrayList<>();
		for (int target = 0; target < 5; target++) {
			for (int ch = 0; ch < 4; ch++) {
				double got = at(px[target], 0, 0, ch);
				if (Math.abs(got - want[target][ch]) > TOL) {
					problems.add(String.format("target %d component %d: got %.6f, expected %.6f", target, ch, got,
						want[target][ch]));
				}
			}
		}
		assertTrue(problems.isEmpty(), String.join("\n", problems) + "\n" + t.msl());
	}

	/**
	 * The compatibility alpha test: the reference comes from the engine's schema (alphaTestRef) though the program never
	 * declares it, and a failing fragment is discarded (its pixel keeps the clear value).
	 */
	@Test
	void alphaTestTakesItsReferenceFromTheSchemaAndDiscardsTheFailingPixels() {
		UniformBlock frame = new UniformBlock("QsFrame", 0, List.of(new UniformField("alphaTestRef", "float", 0)));
		ProgramRequest r = request(VS_FTRANSFORM, FS_ALPHA, 1, new UniformSchema(List.of(frame)),
			new AlphaTest("GREATER", "alphaTestRef"));
		ProgramTranslation t = translate(r);
		assertTrue(t.reflection().flags().discards(), "the alpha test discards: the flag must say so");
		byte[] uniforms = new byte[16];
		ByteBuffer.wrap(uniforms).order(ByteOrder.LITTLE_ENDIAN).putFloat(0, 0.5f);
		byte[] px = render(t, 1, uniforms, new float[] {-1, -1, -1, -1})[0];
		List<String> problems = new ArrayList<>();
		for (int r0 = 0; r0 < H; r0++) {
			for (int c = 0; c < W; c++) {
				boolean kept = (c + 0.5) / 64.0 > 0.5;
				for (int ch = 0; ch < 4; ch++) {
					double want = kept ? (ch == 3 ? (c + 0.5) / 64.0 : 0.0) : -1.0;
					double got = at(px, c, r0, ch);
					if (Math.abs(got - want) > TOL && problems.size() < 6) {
						problems.add(String.format("pixel (%d,%d) component %d: got %.6f, expected %.6f", c, r0, ch, got,
							want));
					}
				}
			}
		}
		assertEquals(0, problems.size(), String.join("\n", problems) + "\n" + t.msl());
	}
}
