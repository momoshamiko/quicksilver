// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.standardroad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.AlphaTest;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.ImageBinding;
import dev.mcms.glsl.msl.ProgramRequest.SamplerState;
import dev.mcms.glsl.msl.ProgramRequest.Semantic;
import dev.mcms.glsl.msl.ProgramRequest.StageSource;
import dev.mcms.glsl.msl.ProgramRequest.TextureBinding;
import dev.mcms.glsl.msl.ProgramRequest.TextureKind;
import dev.mcms.glsl.msl.ProgramRequest.UniformBlock;
import dev.mcms.glsl.msl.ProgramRequest.UniformField;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.msl.ProgramRequest.VertexAttribute;
import dev.mcms.glsl.msl.ProgramRequest.VertexLayout;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.msl.ProgramTranslation.Reflection.UniformUse;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The E2 differential on small programs of our own (ADR 0006 section 4): each program renders through our translator's
 * MSL and through the standard road, with the same inputs, and the outputs must agree within the E2 tolerance
 * (results/2026-11-13-e2-translator/README.md, the W4-B section).
 */
@Tag("gpu")
final class StandardRoadGpuTest {

	private static final int W = 64, H = 48;

	private static List<SourceLine> lines(String text) {
		int n = text.split("\n", -1).length;
		return java.util.stream.IntStream.rangeClosed(1, n).mapToObj(i -> new SourceLine("test", i)).toList();
	}

	private static StageSource stage(Stage stage, String text) {
		return new StageSource(stage, text, lines(text));
	}

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
		uniform float alphaTestRef;
		varying vec2 uv;
		void main() {
			vec4 c = texture2D(tex, uv) * scale;
			gl_FragData[0] = c;
			gl_FragData[1] = vec4(uv, 0.5, 1.0);
		}
		""";

	private static final String ORIENTATION_VS = """
		#version 130
		out vec2 uv;
		void main() {
			vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
			gl_Position = vec4(p * 2.0 - 1.0, 0.25, 1.0);
			uv = p * 0.5;
		}
		""";

	private static final String ORIENTATION_FS = """
		#version 130
		in vec2 uv;
		uniform float scale;
		out vec4 outColor;
		void main() {
			outColor = vec4(gl_FragCoord.xy / vec2(64.0, 48.0), gl_FragCoord.z * scale, 1.0) * 0.5 + vec4(uv * 0.5, 0.0, 0.0);
		}
		""";

	private static final String COMPUTE = """
		#version 450
		layout(local_size_x = 8, local_size_y = 8) in;
		layout(rgba16f) uniform image2D img;
		uniform float scale;
		void main() {
			ivec2 p = ivec2(gl_GlobalInvocationID.xy);
			float k = float(p.x + p.y) * (1.0 / 96.0);
			imageStore(img, p, vec4(k * scale, sin(k), 0.25, 1.0));
		}
		""";

	/** The E2 tolerance on every target: the README's rule, per format. */
	private static void assertAgrees(byte[] ours, byte[] theirs, int format, String what) {
		DifferentialDiff d = DifferentialDiff.of(format, ours, theirs);
		assertTrue(d.passing() >= d.requiredPassing(), what + ": " + d);
	}

	private static ProgramRequest fullScreen() {
		return new ProgramRequest("small-fullscreen", ProgramRequest.Family.FULL_SCREEN,
			List.of(stage(Stage.VERTEX, VS), stage(Stage.FRAGMENT, FS)), VertexLayout.fullScreenQuad(),
			List.of(new ColorTarget(0, 0, 4), new ColorTarget(1, 1, 4)),
			List.of(TextureBinding.constexpr("tex", 0, TextureKind.COLOUR, SamplerState.linear())),
			List.of(), List.of(), new UniformSchema(List.of(new UniformBlock("frame", 3, List.of(
				new UniformField("scale", "float", 0), new UniformField("alphaTestRef", "float", 4))))),
			new AlphaTest("GREATER", "alphaTestRef"), false);
	}

	private static DifferentialGpu.Lib libOf(ProgramTranslation t) {
		return new DifferentialGpu.Lib(t.msl(), t.vertexEntry(), t.msl(), t.fragmentEntry());
	}

	private static DifferentialGpu.Lib libOf(StandardRoad.Program p) {
		StandardRoad.StageMsl v = p.stage(Stage.VERTEX), f = p.stage(Stage.FRAGMENT);
		return new DifferentialGpu.Lib(v.msl(), v.entry(), f.msl(), f.entry());
	}

	@Test
	void fullScreenProgramAgreesWithTheTranslatorOnEveryTarget() {
		ProgramRequest r = fullScreen();
		ProgramTranslation ours = TestSupport.translateOk(r);
		StandardRoad.Program std = StandardRoad.compile(r);
		assertTrue(std.ok(), String.valueOf(std.errors()));
		Map<Integer, byte[]> blocks = Map.of(3, DifferentialGpu.uniformBytes(r.uniforms(), 3, 7));
		Map<Integer, DifferentialGpu.Tex> textures = Map.of(0,
			DifferentialGpu.randomTexture(DifferentialGpu.RGBA16_FLOAT, 2, W, H, 1, 11));
		int[] formats = {DifferentialGpu.RGBA16_FLOAT, DifferentialGpu.RGBA16_FLOAT};
		try (DifferentialGpu gpu = new DifferentialGpu()) {
			byte[][] a = gpu.renderFullScreen(libOf(ours), formats, W, H, textures, blocks);
			byte[][] b = gpu.renderFullScreen(libOf(std), formats, W, H, textures, blocks);
			for (int t = 0; t < formats.length; t++) {
				assertAgrees(a[t], b[t], formats[t], "target " + t);
			}
		}
	}

	@Test
	void clipSpaceAndWindowOrientationAgree() {
		ProgramRequest r = new ProgramRequest("orientation", ProgramRequest.Family.FULL_SCREEN,
			List.of(stage(Stage.VERTEX, ORIENTATION_VS), stage(Stage.FRAGMENT, ORIENTATION_FS)),
			VertexLayout.fullScreenQuad(),
			List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(),
			new UniformSchema(List.of(new UniformBlock("frame", 3, List.of(new UniformField("scale", "float", 0))))),
			null, false);
		ProgramTranslation ours = TestSupport.translateOk(r);
		StandardRoad.Program std = StandardRoad.compile(r);
		assertTrue(std.ok(), String.valueOf(std.errors()));
		Map<Integer, byte[]> blocks = Map.of(3, DifferentialGpu.uniformBytes(r.uniforms(), 3, 5));
		int[] formats = {DifferentialGpu.RGBA16_FLOAT};
		try (DifferentialGpu gpu = new DifferentialGpu()) {
			byte[][] a = gpu.renderFullScreen(libOf(ours), formats, W, H, Map.of(), blocks);
			byte[][] b = gpu.renderFullScreen(libOf(std), formats, W, H, Map.of(), blocks);
			assertAgrees(a[0], b[0], formats[0], "orientation");
		}
	}

	@Test
	void computeKernelAgreesWithTheTranslator() {
		ProgramRequest r = new ProgramRequest("small-compute", ProgramRequest.Family.COMPUTE,
			List.of(stage(Stage.COMPUTE, COMPUTE)), null, List.of(), List.of(),
			List.of(new ImageBinding("img", 0)), List.of(),
			new UniformSchema(List.of(new UniformBlock("frame", 0, List.of(new UniformField("scale", "float", 0))))),
			null, false);
		ProgramTranslation ours = TestSupport.translateOk(r);
		StandardRoad.Program std = StandardRoad.compile(r);
		assertTrue(std.ok(), String.valueOf(std.errors()));
		Map<Integer, byte[]> blocks = Map.of(0, DifferentialGpu.uniformBytes(r.uniforms(), 0, 3));
		Map<Integer, DifferentialGpu.Tex> images = Map.of(0,
			DifferentialGpu.randomTexture(DifferentialGpu.RGBA16_FLOAT, 2, W, H, 1, 1));
		try (DifferentialGpu gpu = new DifferentialGpu()) {
			byte[][] a = gpu.runCompute(ours.msl(), ours.computeEntry(), W / 8, H / 8, 8, 8, images, blocks);
			StandardRoad.StageMsl c = std.stage(Stage.COMPUTE);
			byte[][] b = gpu.runCompute(c.msl(), c.entry(), W / 8, H / 8, 8, 8, images, blocks);
			assertEquals(W * H * 8, a[0].length);
			assertAgrees(a[0], b[0], DifferentialGpu.RGBA16_FLOAT, "image");
		}
	}

	/**
	 * Plan E3: a pack uniform no block serves reads zero, and the translation's reflection lists it as unknown, so the
	 * runtime logs it once per program. The standard road prints the same rewritten program, so it renders the zero too.
	 */
	@Test
	void aPackUniformNoBlockServesReadsZeroAndIsReportedUnknown() {
		String fs = """
			#version 120
			uniform float frameTimeSmooth;
			void main() {
				gl_FragData[0] = vec4(frameTimeSmooth);
			}
			""";
		ProgramRequest r = new ProgramRequest("unserved-uniform", ProgramRequest.Family.FULL_SCREEN,
			List.of(stage(Stage.VERTEX, VS), stage(Stage.FRAGMENT, fs)), VertexLayout.fullScreenQuad(),
			List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(), ProgramRequest.UniformSchema.none(),
			null, false);
		ProgramTranslation ours = TestSupport.translateOk(r);
		assertTrue(ours.reflection().uniforms().stream().anyMatch(
			u -> u.name().equals("frameTimeSmooth") && u.servedBy().equals(UniformUse.UNKNOWN)),
			"the reflection must list an unserved uniform as unknown (the runtime logs it once per program); it lists "
				+ ours.reflection().uniforms());
		StandardRoad.Program std = StandardRoad.compile(r);
		assertTrue(std.ok(), String.valueOf(std.errors()));
		int[] formats = {DifferentialGpu.RGBA16_FLOAT};
		try (DifferentialGpu gpu = new DifferentialGpu()) {
			byte[][] a = gpu.renderFullScreen(libOf(ours), formats, W, H, Map.of(), Map.of());
			byte[][] b = gpu.renderFullScreen(libOf(std), formats, W, H, Map.of(), Map.of());
			int nonZero = 0;
			for (byte x : a[0]) {
				nonZero += x == 0 ? 0 : 1;
			}
			assertEquals(0, nonZero, "bytes of target 0 that do not read zero");
			assertAgrees(a[0], b[0], formats[0], "unserved uniform");
		}
	}
}
