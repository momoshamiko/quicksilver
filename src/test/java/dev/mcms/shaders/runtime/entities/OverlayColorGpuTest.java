// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.entities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.OverlayColor;
import dev.mcms.glsl.msl.ProgramRequest.StageSource;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.msl.ProgramRequest.VertexAttribute;
import dev.mcms.glsl.msl.ProgramRequest.VertexLayout;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import dev.mcms.shaders.metal.HeadlessGpu;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The entity overlay input on the real device (plan.md E4, ProgramRequest.OverlayColor): a translated gbuffers
 * program fetches the overlay texel the UV1 attribute selects (clamped to the texture) and passes {@code (rgb, 1 - a)} to
 * the fragment stage flat as {@code entityColor} while the texel blends ({@code a < 1}), else {@code (0, 0, 0, 0)}: the Iris
 * rendering-uniforms page makes rgb the tint and a the blend factor, and Iris reads 0 for an entity with no flash (the
 * vanilla overlay texel there is opaque white; mcms-entity, 2026-11-16).
 */
@Tag("gpu")
class OverlayColorGpuTest {

	private static final String VS = """
		#version 330
		in vec2 vaPosition;
		void main() { gl_Position = vec4(vaPosition, 0.0, 1.0); }
		""";

	private static final String FS = """
		#version 330
		uniform vec4 entityColor;
		out vec4 c;
		void main() { c = entityColor; }
		""";

	@Test
	void eachQuadsUv1SelectsItsOwnOverlayTexel() {
		List<SourceLine> vl = IntStream.rangeClosed(1, VS.split("\n", -1).length).mapToObj(i -> new SourceLine("vs", i))
			.toList();
		List<SourceLine> fl = IntStream.rangeClosed(1, FS.split("\n", -1).length).mapToObj(i -> new SourceLine("fs", i))
			.toList();
		ProgramTranslation t = Translator.translate(new ProgramRequest("gbuffers_entities",
			ProgramRequest.Family.GBUFFERS,
			List.of(new StageSource(Stage.VERTEX, VS, vl), new StageSource(Stage.FRAGMENT, FS, fl)),
			VertexLayout.of(new VertexAttribute(ProgramRequest.Semantic.POSITION, "float2", 0, 0),
				new VertexAttribute(ProgramRequest.Semantic.OVERLAY, "short2", 8, 0)),
			List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(), UniformSchema.none(), null, false,
			null, new OverlayColor(3)));
		assertTrue(t.ok(), t.diagnostics().toString());

		int width = 16, height = 8;
		// quad A (left) selects overlay texel (5, 0), past the 4-texel row: it reads the last texel; quad B (right) texel (2, 0)
		float[][] positions = {{-1, 1}, {0, 1}, {-1, -1}, {0, -1}, {0, 1}, {1, 1}, {0, -1}, {1, -1}};
		short[][] overlays = {{5, 0}, {5, 0}, {5, 0}, {5, 0}, {2, 0}, {2, 0}, {2, 0}, {2, 0}};
		byte[] vertices = new byte[positions.length * 12];
		ByteBuffer buffer = ByteBuffer.wrap(vertices).order(ByteOrder.LITTLE_ENDIAN);
		for (int v = 0; v < positions.length; v++) {
			buffer.putFloat(positions[v][0]).putFloat(positions[v][1]);
			buffer.putShort(overlays[v][0]).putShort(overlays[v][1]);
		}
		short[] indices = {0, 1, 2, 2, 1, 3, 4, 5, 6, 6, 5, 7};
		// the synthetic overlay texture, 4x1 RGBA8: texel 3 is red with alpha 0.25 (a flash), texel 2 opaque green (none)
		byte[] texels = new byte[4 * 4];
		texels[12] = (byte) 255;
		texels[15] = 64;
		texels[9] = (byte) 255;
		texels[11] = (byte) 255;
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			byte[] pixels = gpu.drawIndexedTextured(t.msl(), t.vertexEntry(), t.fragmentEntry(),
				HeadlessGpu.RGBA32_FLOAT, width, height, vertices, 12, 8,
				new HeadlessGpu.InputTexture(HeadlessGpu.RGBA8_UNORM, 2, 4, 1, 1, texels), 3, indices,
				indices.length, 0, 0);
			float[] rgba = new float[width * height * 4];
			ByteBuffer.wrap(pixels).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(rgba);
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					int i = (y * width + x) * 4;
					boolean left = x < width / 2;
					// (overlay.rgb, 1 - overlay.a) for the quarter-transparent red; zero for the opaque green
					assertEquals(left ? 1.0f : 0.0f, rgba[i], x + "," + y + " red");
					assertEquals(0.0f, rgba[i + 1], x + "," + y + " green");
					assertEquals(0.0f, rgba[i + 2], x + "," + y + " blue");
					// the unorm texel reads back as exact multiples of 1/255
					assertEquals(left ? 1.0f - 64f / 255f : 0.0f, rgba[i + 3], x + "," + y + " alpha");
				}
			}
		}
	}
}
