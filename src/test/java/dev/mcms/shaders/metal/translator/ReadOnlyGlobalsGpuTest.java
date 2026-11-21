// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal.translator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
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
 * Plain globals with a constant initializer (E9): one no statement writes lives once at program scope in the constant
 * address space, like a const; one a statement writes stays per invocation. A table in the per-invocation context that
 * a dynamic index reads (Complementary's jitterOffsets[int(framemod8)]) left the whole context in memory and made
 * Complementary's terrain 35 times slower (results/2026-11-17-e9-diag-taa).
 */
@Tag("gpu")
final class ReadOnlyGlobalsGpuTest {

	private static final int W = 8, H = 8;

	private static final String VS = """
		#version 130
		out vec2 uv;
		void main() {
			vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
			gl_Position = vec4(corner * 2.0 - 1.0, 0.5, 1.0);
			uv = corner;
		}
		""";

	private static ProgramTranslation translate(String fs) {
		return TranslateSupport.translateOk(new ProgramRequest("globals", ProgramRequest.Family.FULL_SCREEN,
			List.of(TranslateSupport.stage(Stage.VERTEX, VS), TranslateSupport.stage(Stage.FRAGMENT, fs)),
			ProgramRequest.VertexLayout.fullScreenQuad(), List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(),
			List.of(), UniformSchema.none(), null, false));
	}

	private static ByteBuffer render(ProgramTranslation t) {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			byte[] px = gpu.renderFullscreenCleared(t.msl(), t.vertexEntry(), t.fragmentEntry(),
				new int[] {HeadlessGpu.RGBA32_FLOAT}, W, H, null, new byte[16], null, new float[] {-1, -1, -1, -1})[0];
			return ByteBuffer.wrap(px).order(ByteOrder.LITTLE_ENDIAN);
		}
	}

	private interface Want {
		float at(int column, int channel);
	}

	private static void expect(ByteBuffer px, Want want, ProgramTranslation t) {
		List<String> bad = new ArrayList<>();
		for (int r = 0; r < H; r++) {
			for (int c = 0; c < W; c++) {
				for (int ch = 0; ch < 4; ch++) {
					float got = px.getFloat(((r * W) + c) * 16 + ch * 4);
					float w = want.at(c, ch);
					if (!(Math.abs(got - w) <= 1e-4f) && bad.size() < 5) {
						bad.add(String.format("pixel (%d,%d) component %d: got %.6f, expected %.6f", c, r, ch, got, w));
					}
				}
			}
		}
		assertEquals(List.of(), bad, String.join("\n", bad) + "\n" + t.msl());
	}

	@Test
	void aTableNoStatementWritesIsAProgramConstantReadByADynamicIndex() {
		String fs = """
			#version 130
			in vec2 uv;
			out vec4 color;
			vec2 table[4] = vec2[4](vec2(0.125, 0.25), vec2(0.5, 0.75), vec2(1.0, 2.0), vec2(3.0, 4.0));
			float scale = 2.0;
			void main() {
				int i = int(uv.x * 4.0);
				color = vec4(table[i] * scale, float(i), 1.0);
			}
			""";
		ProgramTranslation t = translate(fs);
		assertTrue(t.msl().contains("constant array<float2, 4> qs_k_fragment_table"), t.msl());
		assertTrue(t.msl().contains("constant float qs_k_fragment_scale"), t.msl());
		float[][] table = {{0.125f, 0.25f}, {0.5f, 0.75f}, {1f, 2f}, {3f, 4f}};
		// Column c's centre is uv.x = (c + 0.5) / 8, so i = c / 2.
		expect(render(t), (c, ch) -> ch < 2 ? table[c / 2][ch] * 2f : ch == 2 ? c / 2 : 1f, t);
	}

	@Test
	void globalsAStatementWritesStayPerInvocation() {
		String fs = """
			#version 130
			in vec2 uv;
			out vec4 color;
			float counts[2] = float[2](1.0, 2.0);
			float bumped = 10.0;
			float viaInout = 3.0;
			void bump(inout float x) { x += 0.5; }
			void main() {
				counts[1] = counts[1] + uv.x;
				bumped++;
				bump(viaInout);
				color = vec4(counts[1] - uv.x, bumped, viaInout, counts[0]);
			}
			""";
		ProgramTranslation t = translate(fs);
		expect(render(t), (c, ch) -> new float[] {2f, 11f, 3.5f, 1f}[ch], t);
	}

	/**
	 * Program constants of every kind a pack global can have, read every way: a matrix, a struct and an array picked by a
	 * ternary against a per-invocation value, an array passed by value and copied to a local, its length, int and bool
	 * vectors, and an int vector initializer converted to a float vector (MSL converts no vector implicitly).
	 */
	@Test
	void constantsOfEveryKindReadRight() {
		String fs = """
			#version 130
			in vec2 uv;
			out vec4 color;
			struct Pair { float a; vec2 b; };
			mat3 m = mat3(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0);
			Pair pr = Pair(0.5, vec2(1.5, 2.5));
			ivec2 iv = ivec2(3, 4);
			bvec2 bv = bvec2(true, false);
			vec2 conv = ivec2(1, 2);
			float tbl[3] = float[3](0.25, 0.5, 0.75);
			float sum3(float t[3]) { return t[0] + t[1] + t[2]; }
			void main() {
				mat3 local = mat3(uv.x);
				mat3 pick = uv.x < 2.0 ? m : local;
				Pair lp = Pair(uv.y, vec2(0.0));
				Pair pp = uv.x < 2.0 ? pr : lp;
				float copied[3] = tbl;
				float lt[3] = float[3](uv.x, 0.0, 0.0);
				float picked[3] = uv.x < 2.0 ? tbl : lt;
				color = vec4(pick[1][2], pp.b.y, sum3(tbl) + copied[2] + picked[0],
					float(iv.y) + conv.y + float(tbl.length()) + (bv.x && !bv.y ? 1.0 : 0.0));
			}
			""";
		ProgramTranslation t = translate(fs);
		// m's column 1 is (4, 5, 6); 1.5 + 0.75 + 0.25; 4 + 2 + 3 + 1.
		expect(render(t), (c, ch) -> new float[] {6f, 2.5f, 2.5f, 10f}[ch], t);
	}
}
