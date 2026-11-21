package dev.mcms.shaders.standardroad;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.StageSource;
import dev.mcms.glsl.msl.ProgramRequest.VertexLayout;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The window convention of the engine (ADR 0006 section 2), measured on the GPU against analytic OpenGL answers; neither road
 * is the reference. A target holds OpenGL's orientation in memory: row r is window row y = r (y up, row 0 the bottom row),
 * and texcoord is (0,0) at the bottom left of the full-screen quad. For a pixel of column c and row r the GL answers are:
 * gl_FragCoord = (c + 0.5, r + 0.5, depth) with depth = (z + 1) / 2 for clip z = 0.5 and w = 1, texcoord = ((c + 0.5) / W,
 * (r + 0.5) / H), dFdx(texcoord) = (1 / W, 0), dFdy(texcoord) = (0, 1 / H) (GLSL defines dFdy as the difference toward
 * increasing window y), and gl_FrontFacing true for a counter-clockwise triangle in window coordinates (glFrontFace's default)
 * and false for a clockwise one.
 *
 * <p>Each road renders the same two programs: a full-screen triangle (every pixel) and two small triangles, one
 * counter-clockwise on the left and one clockwise on the right. The triangle checks read a 3x3 block inside each triangle.
 */
@Tag("gpu")
final class WindowConventionGpuTest {

	static final int W = 64, H = 48;
	/** The absolute tolerance of every component: the GL answers are exact up to single-precision rounding. */
	static final double TOL = 1e-4;
	private static final int RGBA32 = DifferentialGpu.RGBA32_FLOAT;

	/** The full-screen triangle from the vertex ids: the corner (0,0) is the bottom left, texcoord = (NDC + 1) / 2. */
	private static final String FULL_VS = """
		#version 130
		out vec2 uv;
		void main() {
			vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
			gl_Position = vec4(corner * 2.0 - 1.0, 0.5, 1.0);
			uv = corner;
		}
		""";

	/** Two triangles: vertices 0-2 counter-clockwise on the left, vertices 3-5 clockwise on the right (window order). */
	private static final String TRIANGLE_VS = """
		#version 130
		out vec2 uv;
		void main() {
			vec2 p;
			if (gl_VertexID == 0) p = vec2(-0.9, -0.5);
			else if (gl_VertexID == 1) p = vec2(-0.1, -0.5);
			else if (gl_VertexID == 2) p = vec2(-0.5, 0.5);
			else if (gl_VertexID == 3) p = vec2(0.1, -0.5);
			else if (gl_VertexID == 4) p = vec2(0.5, 0.5);
			else p = vec2(0.9, -0.5);
			gl_Position = vec4(p, 0.5, 1.0);
			uv = p * 0.5 + 0.5;
		}
		""";

	private static final String FS = """
		#version 130
		in vec2 uv;
		void main() {
			gl_FragData[0] = vec4(gl_FragCoord.xy, uv);
			gl_FragData[1] = vec4(dFdx(uv), dFdy(uv));
			gl_FragData[2] = vec4(gl_FrontFacing ? 1.0 : 0.0, gl_FragCoord.z, 0.0, 1.0);
		}
		""";

	/**
	 * Writes texcoord from gl_MultiTexCoord0, as a pass of the documented full-screen quad reads it. The position is
	 * ftransform(), the quad's projection (gl_Vertex alone is the corner in 0..1, which covers only the upper right quarter).
	 */
	private static final String PROBE_VS = """
		#version 120
		varying vec2 uv;
		void main() {
			gl_Position = ftransform();
			uv = gl_MultiTexCoord0.xy;
		}
		""";

	private static final String PROBE_FS = """
		#version 120
		varying vec2 uv;
		void main() {
			gl_FragData[0] = vec4(uv, 0.0, 1.0);
		}
		""";

	/** One road's libraries for the full-screen draw and for the triangle draw. */
	record Libs(DifferentialGpu.Lib full, DifferentialGpu.Lib triangles) {
	}

	private static List<SourceLine> lines(String text) {
		int n = text.split("\n", -1).length;
		return java.util.stream.IntStream.rangeClosed(1, n).mapToObj(i -> new SourceLine("convention", i)).toList();
	}

	private static StageSource stage(Stage stage, String text) {
		return new StageSource(stage, text, lines(text));
	}

	private static ProgramRequest request(String name, String vs, String fs) {
		return new ProgramRequest(name, ProgramRequest.Family.FULL_SCREEN,
			List.of(stage(Stage.VERTEX, vs), stage(Stage.FRAGMENT, fs)), VertexLayout.fullScreenQuad(),
			List.of(new ColorTarget(0, 0, 4), new ColorTarget(1, 1, 4), new ColorTarget(2, 2, 4)), List.of(), List.of(),
			List.of(), ProgramRequest.UniformSchema.none(), null, false);
	}

	/** The GL answer for one component of a target: 0 gl_FragCoord and texcoord, 1 the derivatives, 2 facing and depth. */
	static double expected(int target, int c, int r, int ch, double facing) {
		return switch (target) {
			case 0 -> switch (ch) {
				case 0 -> c + 0.5;
				case 1 -> r + 0.5;
				case 2 -> (c + 0.5) / W;
				default -> (r + 0.5) / H;
			};
			case 1 -> switch (ch) {
				case 0 -> 1.0 / W;
				case 1, 2 -> 0.0;
				default -> 1.0 / H;
			};
			default -> switch (ch) {
				case 0 -> facing;
				case 1 -> 0.75;
				case 3 -> 1.0;
				default -> 0.0;
			};
		};
	}

	/** The name of the GL quantity a component holds. */
	static String labelOf(int target, int ch) {
		return switch (target) {
			case 0 -> ch < 2 ? "gl_FragCoord.xy" : "texcoord";
			case 1 -> ch < 2 ? "dFdx(texcoord)" : "dFdy(texcoord)";
			default -> ch == 0 ? "gl_FrontFacing" : ch == 1 ? "gl_FragCoord.z" : "constant";
		};
	}

	private static final class Stat {
		int bad;
		double worst;
		String first;
	}

	/**
	 * The mismatches of one target over the points (column, row), one line per GL quantity with a mismatch. Empty when
	 * every component is within the tolerance.
	 */
	static List<String> compare(String what, byte[] pixels, List<int[]> points, int target, double facing) {
		ByteBuffer bb = ByteBuffer.wrap(pixels).order(ByteOrder.LITTLE_ENDIAN);
		Map<String, Stat> stats = new LinkedHashMap<>();
		for (int[] p : points) {
			for (int ch = 0; ch < 4; ch++) {
				float got = bb.getFloat(((p[1] * W) + p[0]) * 16 + ch * 4);
				double want = expected(target, p[0], p[1], ch, facing);
				double d = Math.abs(got - want);
				if (!(d <= TOL)) {
					Stat s = stats.computeIfAbsent(labelOf(target, ch), k -> new Stat());
					s.bad++;
					s.worst = Math.max(s.worst, d);
					if (s.first == null) {
						s.first = String.format("pixel (%d,%d) got %.6f expected %.6f", p[0], p[1], got, want);
					}
				}
			}
		}
		List<String> out = new ArrayList<>();
		stats.forEach((label, s) -> out.add(String.format("%s, target %d, %s: %d of %d components off, worst %.6f; first %s",
			what, target, label, s.bad, points.size() * 2, s.worst, s.first)));
		return out;
	}

	/** The pixels (column, row) of a square block of the given radius around a centre. */
	static List<int[]> block(int cx, int cy, int radius) {
		List<int[]> out = new ArrayList<>();
		for (int r = cy - radius; r <= cy + radius; r++) {
			for (int c = cx - radius; c <= cx + radius; c++) {
				out.add(new int[] {c, r});
			}
		}
		return out;
	}

	/** Every pixel of the target, row by row. */
	static List<int[]> allPixels() {
		return rows();
	}

	private static List<int[]> rows() {
		List<int[]> out = new ArrayList<>();
		for (int r = 0; r < H; r++) {
			for (int c = 0; c < W; c++) {
				out.add(new int[] {c, r});
			}
		}
		return out;
	}

	/**
	 * Renders the full-screen and triangle programs of one road and returns every mismatch with the GL answer, one line per
	 * GL quantity; empty when the road follows the convention.
	 */
	static List<String> problems(DifferentialGpu gpu, Libs libs) {
		int[] formats = {RGBA32, RGBA32, RGBA32};
		byte[][] full = gpu.renderPrimitives(libs.full(), 3, formats, W, H, Map.of(), Map.of());
		byte[][] tri = gpu.renderPrimitives(libs.triangles(), 6, formats, W, H, Map.of(), Map.of());
		List<int[]> left = block(W / 4, H / 2, 1), right = block(3 * W / 4, H / 2, 1);
		List<String> out = new ArrayList<>();
		for (int target = 0; target < 3; target++) {
			out.addAll(compare("full-screen", full[target], allPixels(), target, 1.0));
			out.addAll(compare("triangle counter-clockwise", tri[target], left, target, 1.0));
			out.addAll(compare("triangle clockwise", tri[target], right, target, 0.0));
		}
		return out;
	}

	/** The translator's libraries for the two draws. */
	static Libs translatorLibs() {
		ProgramTranslation full = TestSupport.translateOk(request("convention-full", FULL_VS, FS));
		ProgramTranslation tri = TestSupport.translateOk(request("convention-triangles", TRIANGLE_VS, FS));
		return new Libs(new DifferentialGpu.Lib(full.msl(), full.vertexEntry(), full.msl(), full.fragmentEntry()),
			new DifferentialGpu.Lib(tri.msl(), tri.vertexEntry(), tri.msl(), tri.fragmentEntry()));
	}

	/** The standard road's libraries for the two draws. */
	static Libs standardRoadLibs() {
		StandardRoad.Program full = StandardRoad.compile(request("convention-full", FULL_VS, FS));
		StandardRoad.Program tri = StandardRoad.compile(request("convention-triangles", TRIANGLE_VS, FS));
		assertTrue(full.ok() && tri.ok(), "standard road refused: " + full.errors() + " " + tri.errors());
		return new Libs(lib(full), lib(tri));
	}

	private static DifferentialGpu.Lib lib(StandardRoad.Program p) {
		StandardRoad.StageMsl v = p.stage(Stage.VERTEX), f = p.stage(Stage.FRAGMENT);
		return new DifferentialGpu.Lib(v.msl(), v.entry(), f.msl(), f.entry());
	}

	/** The translator alone: gl_FragCoord, texcoord, dFdx, facing and depth against the GL answers. */
	@Test
	void translatorFollowsTheOpenGlConventionExceptDerivativeY() {
		List<String> problems;
		try (DifferentialGpu gpu = new DifferentialGpu()) {
			problems = problems(gpu, translatorLibs());
		}
		List<String> others = problems.stream().filter(p -> !p.contains("dFdy")).toList();
		assertTrue(others.isEmpty(), "translator, window convention:\n" + String.join("\n", others));
	}

	/**
	 * The translator's dFdy against the GL answer: Metal's dfdy with no sign change, +1/H as GL gives (W2-C's table, fixed
	 * in W3-B's review round).
	 */
	@Test
	void translatorDerivativeYFollowsTheOpenGlConvention() {
		List<String> problems;
		try (DifferentialGpu gpu = new DifferentialGpu()) {
			problems = problems(gpu, translatorLibs());
		}
		List<String> dfdy = problems.stream().filter(p -> p.contains("dFdy")).toList();
		assertTrue(dfdy.isEmpty(), "translator, dFdy:\n" + String.join("\n", dfdy));
	}

	/** The standard road against the same GL answers, every quantity. */
	@Test
	void standardRoadFollowsTheOpenGlConvention() {
		List<String> problems;
		try (DifferentialGpu gpu = new DifferentialGpu()) {
			problems = problems(gpu, standardRoadLibs());
		}
		assertTrue(problems.isEmpty(), "standard road, window convention:\n" + String.join("\n", problems));
	}

	/**
	 * The documented full-screen quad carries texcoord in gl_MultiTexCoord0 (plan.md E3: gl_Vertex and gl_MultiTexCoord0
	 * from 0 to 1, W3-B's contract).
	 */
	@Test
	void translatorFullScreenQuadSuppliesMultiTexCoord0() {
		ProgramTranslation t = TestSupport.translateOk(request("convention-probe", PROBE_VS, PROBE_FS));
		DifferentialGpu.Lib lib = new DifferentialGpu.Lib(t.msl(), t.vertexEntry(), t.msl(), t.fragmentEntry());
		List<String> problems = new ArrayList<>();
		try (DifferentialGpu gpu = new DifferentialGpu()) {
			byte[] px = gpu.renderPrimitives(lib, 6, new int[] {RGBA32}, W, H, Map.of(), Map.of())[0];
			ByteBuffer bb = ByteBuffer.wrap(px).order(ByteOrder.LITTLE_ENDIAN);
			for (int[] p : allPixels()) {
				float u = bb.getFloat((p[1] * W + p[0]) * 16), v = bb.getFloat((p[1] * W + p[0]) * 16 + 4);
				if (Math.abs(u - (p[0] + 0.5) / W) > TOL || Math.abs(v - (p[1] + 0.5) / H) > TOL) {
					problems.add(String.format("pixel (%d,%d) texcoord %.6f, %.6f", p[0], p[1], u, v));
					break;
				}
			}
		}
		assertTrue(problems.isEmpty(), "gl_MultiTexCoord0 on the full-screen quad: " + problems);
	}
}
