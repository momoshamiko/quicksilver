// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.standardroad;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.StageSource;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.msl.ProgramRequest.VertexLayout;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The E2 differential on fragment outputs: each program renders through the translator's MSL and through the standard
 * road, and every listed target must agree. Each program writes its outputs from a function, an out parameter, an
 * array or a sparse set, the forms the location rule has to get right on both roads.
 */
@Tag("gpu")
final class FragmentOutputsGpuTest {

	private static final int W = 64, H = 48;
	private static final int TARGETS = 3;

	private static final String VS = """
		#version 120
		void main() {
			gl_Position = ftransform();
		}
		""";

	private static List<SourceLine> lines(String text) {
		return IntStream.rangeClosed(1, text.split("\n", -1).length).mapToObj(i -> new SourceLine("test", i)).toList();
	}

	private static ProgramRequest request(String fs) {
		return new ProgramRequest("fragment-outputs", ProgramRequest.Family.FULL_SCREEN,
			List.of(new StageSource(Stage.VERTEX, VS, lines(VS)), new StageSource(Stage.FRAGMENT, fs, lines(fs))),
			VertexLayout.fullScreenQuad(),
			List.of(new ColorTarget(0, 0, 4), new ColorTarget(1, 1, 4), new ColorTarget(2, 2, 4)), List.of(), List.of(),
			List.of(), UniformSchema.none(), null, false);
	}

	private static DifferentialGpu.Lib libOf(ProgramTranslation t) {
		return new DifferentialGpu.Lib(t.msl(), t.vertexEntry(), t.msl(), t.fragmentEntry());
	}

	private static DifferentialGpu.Lib libOf(StandardRoad.Program p) {
		StandardRoad.StageMsl v = p.stage(Stage.VERTEX), f = p.stage(Stage.FRAGMENT);
		return new DifferentialGpu.Lib(v.msl(), v.entry(), f.msl(), f.entry());
	}

	/** Both roads render the program; every listed target must agree within the E2 tolerance. */
	private static void agrees(String name, String fs) {
		ProgramRequest r = request(fs);
		ProgramTranslation ours = TestSupport.translateOk(r);
		StandardRoad.Program std = StandardRoad.compile(r);
		assertTrue(std.ok(), name + ": " + std.errors());
		int[] formats = {DifferentialGpu.RGBA16_FLOAT, DifferentialGpu.RGBA16_FLOAT, DifferentialGpu.RGBA16_FLOAT};
		try (DifferentialGpu gpu = new DifferentialGpu()) {
			byte[][] a = gpu.renderFullScreen(libOf(ours), formats, W, H, Map.of(), Map.<Integer, byte[]>of());
			byte[][] b = gpu.renderFullScreen(libOf(std), formats, W, H, Map.of(), Map.<Integer, byte[]>of());
			for (int t = 0; t < TARGETS; t++) {
				DifferentialDiff d = DifferentialDiff.of(formats[t], a[t], b[t]);
				assertTrue(d.passing() >= d.requiredPassing(), name + " target " + t + ": " + d);
			}
		}
	}

	@Test
	void aSparseFragDataSetAgreesOnEveryTarget() {
		agrees("sparse", """
			#version 120
			void main() {
				gl_FragData[0] = vec4(1.0, 0.0, 0.0, 1.0);
				gl_FragData[2] = vec4(0.0, 0.0, 1.0, 1.0);
			}
			""");
	}

	@Test
	void outColorNamesWithAGapAgreeOnEveryTarget() {
		agrees("outColor gap", """
			#version 450
			out vec4 outColor2;
			out vec4 outColor0;
			void main() {
				outColor0 = vec4(1.0, 0.0, 0.0, 1.0);
				outColor2 = vec4(0.0, 0.0, 1.0, 1.0);
			}
			""");
	}

	@Test
	void anExplicitlyLocatedArrayAgreesOnEveryTarget() {
		agrees("explicit array", """
			#version 450
			layout(location = 1) out vec4 x[2];
			void main() {
				x[0] = vec4(1.0, 0.0, 0.0, 1.0);
				x[1] = vec4(0.0, 1.0, 0.0, 1.0);
			}
			""");
	}

	@Test
	void anUnlocatedArrayAgreesOnEveryTarget() {
		agrees("unlocated array", """
			#version 450
			out vec4 y[2];
			out vec4 z;
			void main() {
				y[0] = vec4(1.0, 0.0, 0.0, 1.0);
				y[1] = vec4(0.0, 1.0, 0.0, 1.0);
				z = vec4(0.0, 0.0, 1.0, 1.0);
			}
			""");
	}

	@Test
	void anOutputWrittenInACalledFunctionAgreesOnEveryTarget() {
		agrees("function", """
			#version 450
			layout(location = 1) out vec4 second;
			void paint(vec4 v) {
				second = v;
			}
			void main() {
				paint(vec4(0.0, 1.0, 0.0, 1.0));
			}
			""");
	}

	@Test
	void anOutputWrittenThroughAnOutParameterAgreesOnEveryTarget() {
		agrees("out parameter", """
			#version 450
			layout(location = 2) out vec4 third;
			void paint(out vec4 o) {
				o = vec4(0.0, 0.0, 1.0, 1.0);
			}
			void main() {
				paint(third);
			}
			""");
	}
}
