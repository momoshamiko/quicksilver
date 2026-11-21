// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal.translator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.sema.Stage;
import dev.mcms.shaders.metal.HeadlessGpu;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Fragment outputs on the GPU (engine-pack-format section 9): a layout location, an outColor name, a gl_FragData index
 * or an array element maps the output to its listed target; a listed target nothing writes keeps the clear value. The
 * three targets are cleared to (0.25, 0.5, 0.75, 1.0), so an untouched red channel reads 64 and an untouched green 128.
 */
@Tag("gpu")
final class FragmentOutputLocationsGpuTest {

	private static final String VS = """
		#version 120
		void main() {
			gl_Position = ftransform();
		}
		""";

	private static final float[] CLEAR = {0.25f, 0.5f, 0.75f, 1.0f};
	private static final int UNTOUCHED_RED = 64;
	private static final int UNTOUCHED_GREEN = 128;

	private static ProgramTranslation translate(String fs) {
		return TranslateSupport.translateOk(new ProgramRequest("fragment-output-locations",
			ProgramRequest.Family.FULL_SCREEN,
			List.of(TranslateSupport.stage(Stage.VERTEX, VS), TranslateSupport.stage(Stage.FRAGMENT, fs)),
			ProgramRequest.VertexLayout.fullScreenQuad(),
			List.of(new ColorTarget(0, 0, 4), new ColorTarget(1, 1, 4), new ColorTarget(2, 2, 4)), List.of(),
			List.of(), List.of(), ProgramRequest.UniformSchema.none(), null, false));
	}

	/** Renders a 4x4 frame to three targets; the result is one byte array per target. */
	private static byte[][] render(ProgramTranslation t) {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			return gpu.renderFullscreenCleared(t.msl(), t.vertexEntry(), t.fragmentEntry(),
				new int[] {HeadlessGpu.RGBA8_UNORM, HeadlessGpu.RGBA8_UNORM, HeadlessGpu.RGBA8_UNORM}, 4, 4, null,
				new byte[0], new byte[0][], CLEAR);
		}
	}

	private static int red(byte[] target) {
		return target[0] & 0xff;
	}

	private static int green(byte[] target) {
		return target[1] & 0xff;
	}

	private static int blue(byte[] target) {
		return target[2] & 0xff;
	}

	@Test
	void locationsOutOfDeclarationOrderWriteTheirOwnTargets() {
		ProgramTranslation t = translate("""
			#version 450
			layout(location = 2) out vec4 top;
			layout(location = 0) out vec4 bottom;
			void main() {
				bottom = vec4(1.0, 0.0, 0.0, 1.0);
				top = vec4(0.0, 0.0, 1.0, 1.0);
			}
			""");
		byte[][] px = render(t);
		assertEquals(255, red(px[0]), "attachment 0 takes location 0\n" + t.msl());
		assertEquals(255, blue(px[2]), "attachment 2 takes location 2\n" + t.msl());
		assertEquals(UNTOUCHED_RED, red(px[1]), "attachment 1 is not written\n" + t.msl());
		assertEquals(UNTOUCHED_GREEN, green(px[1]), "attachment 1 is not written\n" + t.msl());
	}

	@Test
	void sparseGlFragDataLeavesTheSkippedTargetUntouched() {
		ProgramTranslation t = translate("""
			#version 120
			void main() {
				gl_FragData[0] = vec4(1.0, 0.0, 0.0, 1.0);
				gl_FragData[2] = vec4(0.0, 0.0, 1.0, 1.0);
			}
			""");
		byte[][] px = render(t);
		assertEquals(255, red(px[0]), "attachment 0\n" + t.msl());
		assertEquals(255, blue(px[2]), "attachment 2\n" + t.msl());
		assertEquals(UNTOUCHED_RED, red(px[1]), "attachment 1 is not written\n" + t.msl());
		assertEquals(UNTOUCHED_GREEN, green(px[1]), "attachment 1 is not written\n" + t.msl());
	}

	@Test
	void outColorNamesWithAGapWriteTheirListedTargets() {
		ProgramTranslation t = translate("""
			#version 450
			out vec4 outColor2;
			out vec4 outColor0;
			void main() {
				outColor0 = vec4(1.0, 0.0, 0.0, 1.0);
				outColor2 = vec4(0.0, 0.0, 1.0, 1.0);
			}
			""");
		byte[][] px = render(t);
		assertEquals(255, red(px[0]), "attachment 0 takes outColor0\n" + t.msl());
		assertEquals(255, blue(px[2]), "attachment 2 takes outColor2\n" + t.msl());
		assertEquals(UNTOUCHED_RED, red(px[1]), "attachment 1 is not written\n" + t.msl());
		assertEquals(UNTOUCHED_GREEN, green(px[1]), "attachment 1 is not written\n" + t.msl());
	}

	@Test
	void denseDeclarationOrderFillsEveryTarget() {
		ProgramTranslation t = translate("""
			#version 450
			out vec4 a;
			out vec4 b;
			void main() {
				a = vec4(1.0, 0.0, 0.0, 1.0);
				b = vec4(0.0, 1.0, 0.0, 1.0);
			}
			""");
		byte[][] px = render(t);
		assertEquals(255, red(px[0]), "attachment 0\n" + t.msl());
		assertEquals(255, green(px[1]), "attachment 1\n" + t.msl());
	}

	@Test
	void anExplicitlyLocatedArrayWritesConsecutiveTargets() {
		ProgramTranslation t = translate("""
			#version 450
			layout(location = 1) out vec4 x[2];
			void main() {
				x[0] = vec4(1.0, 0.0, 0.0, 1.0);
				x[1] = vec4(0.0, 1.0, 0.0, 1.0);
			}
			""");
		byte[][] px = render(t);
		assertEquals(UNTOUCHED_RED, red(px[0]), "attachment 0 is not written\n" + t.msl());
		assertEquals(255, red(px[1]), "x[0] is attachment 1\n" + t.msl());
		assertEquals(255, green(px[2]), "x[1] is attachment 2\n" + t.msl());
	}

	@Test
	void anUnlocatedArrayTakesTheFirstTargets() {
		ProgramTranslation t = translate("""
			#version 450
			out vec4 y[2];
			out vec4 z;
			void main() {
				y[0] = vec4(1.0, 0.0, 0.0, 1.0);
				y[1] = vec4(0.0, 1.0, 0.0, 1.0);
				z = vec4(0.0, 0.0, 1.0, 1.0);
			}
			""");
		byte[][] px = render(t);
		assertEquals(255, red(px[0]), "y[0] is attachment 0\n" + t.msl());
		assertEquals(255, green(px[1]), "y[1] is attachment 1\n" + t.msl());
		assertEquals(255, blue(px[2]), "z is attachment 2\n" + t.msl());
	}

	@Test
	void anOutputWrittenInACalledFunctionReachesItsTarget() {
		ProgramTranslation t = translate("""
			#version 450
			layout(location = 1) out vec4 second;
			void paint(vec4 v) {
				second = v;
			}
			void main() {
				paint(vec4(0.0, 1.0, 0.0, 1.0));
			}
			""");
		byte[][] px = render(t);
		assertEquals(255, green(px[1]), "the function's write\n" + t.msl());
		assertEquals(UNTOUCHED_RED, red(px[0]), "attachment 0 is not written\n" + t.msl());
	}

	@Test
	void anOutputWrittenThroughAnOutParameterReachesItsTarget() {
		ProgramTranslation t = translate("""
			#version 450
			layout(location = 2) out vec4 third;
			void paint(out vec4 o) {
				o = vec4(0.0, 0.0, 1.0, 1.0);
			}
			void main() {
				paint(third);
			}
			""");
		byte[][] px = render(t);
		assertEquals(255, blue(px[2]), "the out parameter's write\n" + t.msl());
	}
}
