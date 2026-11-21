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

/** Full-screen passes run on the GPU: discard, and a matrix varying that the stages pass per column. */
@Tag("gpu")
final class FragmentSemanticsGpuTest {

	private static final String VS_QUAD = """
		#version 120
		void main() {
			gl_Position = ftransform();
		}
		""";

	private static ProgramTranslation translate(String vs, String fs) {
		return TranslateSupport.translateOk(new ProgramRequest("fragment-semantics", ProgramRequest.Family.FULL_SCREEN,
			List.of(TranslateSupport.stage(Stage.VERTEX, vs), TranslateSupport.stage(Stage.FRAGMENT, fs)),
			ProgramRequest.VertexLayout.fullScreenQuad(), List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(),
			List.of(), ProgramRequest.UniformSchema.none(), null, false));
	}

	@Test
	void discardKeepsOnlyTheFragmentsThatDoNotDiscard() {
		String fs = """
			#version 120
			void main() {
				if (gl_FragCoord.x < 2.0) discard;
				gl_FragData[0] = vec4(1.0);
			}
			""";
		ProgramTranslation t = translate(VS_QUAD, fs);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			byte[] out = gpu.renderFullscreen(t.msl(), t.vertexEntry(), t.fragmentEntry(),
				new int[] {HeadlessGpu.RGBA8_UNORM}, 4, 4, null, new byte[0], new byte[0][])[0];
			for (int y = 0; y < 4; y++) {
				for (int x = 0; x < 4; x++) {
					int red = out[(y * 4 + x) * 4] & 0xff;
					assertEquals(x < 2 ? 0 : 255, red, "pixel " + x + "," + y + "\n" + t.msl());
				}
			}
		}
	}

	@Test
	void matrixVaryingArrivesColumnByColumn() {
		String vs = """
			#version 120
			varying mat2 m;
			void main() {
				gl_Position = ftransform();
				m = mat2(0.0, 1.0, 1.0, 0.0);
			}
			""";
		String fs = """
			#version 120
			varying mat2 m;
			void main() {
				gl_FragData[0] = vec4(m[0][0], m[0][1], m[1][0], m[1][1]);
			}
			""";
		ProgramTranslation t = translate(vs, fs);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			byte[] out = gpu.renderFullscreen(t.msl(), t.vertexEntry(), t.fragmentEntry(),
				new int[] {HeadlessGpu.RGBA8_UNORM}, 4, 4, null, new byte[0], new byte[0][])[0];
			assertEquals(0, out[0] & 0xff, "m[0][0]\n" + t.msl());
			assertEquals(255, out[1] & 0xff, "m[0][1]\n" + t.msl());
			assertEquals(255, out[2] & 0xff, "m[1][0]\n" + t.msl());
			assertEquals(0, out[3] & 0xff, "m[1][1]\n" + t.msl());
		}
	}

	@Test
	void compoundAssignmentToASwizzleComputesTheRowVectorProduct() {
		String fs = """
			#version 120
			void main() {
				vec3 v = vec3(1.0, 0.0, 0.0);
				v.xy *= mat2(0.0, 1.0, 1.0, 0.0);
				gl_FragData[0] = vec4(v, 1.0);
			}
			""";
		ProgramTranslation t = translate(VS_QUAD, fs);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			byte[] out = gpu.renderFullscreen(t.msl(), t.vertexEntry(), t.fragmentEntry(),
				new int[] {HeadlessGpu.RGBA8_UNORM}, 4, 4, null, new byte[0], new byte[0][])[0];
			assertEquals(0, out[0] & 0xff, "red\n" + t.msl());
			assertEquals(255, out[1] & 0xff, "green\n" + t.msl());
		}
	}
}
