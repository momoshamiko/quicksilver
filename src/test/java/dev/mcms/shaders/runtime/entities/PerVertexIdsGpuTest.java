// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.entities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.PerVertexIds;
import dev.mcms.glsl.msl.ProgramRequest.StageSource;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.msl.ProgramRequest.VertexAttribute;
import dev.mcms.glsl.msl.ProgramRequest.VertexLayout;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.runtime.EngineSlots;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The per-vertex id input on the real device (plan.md E4, ProgramRequest.PerVertexIds): a translated gbuffers program
 * reads the ids through the side buffer at {@code [[vertex_id]]} with a non-zero base vertex, and two "entities"
 * merged into one draw each get their own ids. Apple's drawIndexedPrimitives doc: vertex_id runs from the draw's base
 * vertex through base vertex + index count - 1, which over the game's shared sequential index buffer is the vertex's
 * own index plus base vertex (engine-entity-draws.md section 1) — the draw below proves it: vertices 0 and 1 are
 * skipped by base vertex 2, and the pixels of both quads read the ids of their own vertices.
 */
@Tag("gpu")
class PerVertexIdsGpuTest {

	private static final String VS = """
		#version 330
		in vec2 vaPosition;
		void main() { gl_Position = vec4(vaPosition, 0.0, 1.0); }
		""";

	private static final String FS = """
		#version 330
		uniform int entityId;
		uniform int blockEntityId;
		uniform int currentRenderedItemId;
		out vec4 c;
		void main() {
			c = vec4(float(entityId), float(blockEntityId), float(currentRenderedItemId), 1.0);
		}
		""";

	@Test
	void mergedDrawReadsEachEntitysOwnIdsWithNonZeroBaseVertex() {
		List<SourceLine> vl = IntStream.rangeClosed(1, VS.split("\n", -1).length).mapToObj(i -> new SourceLine("vs", i))
			.toList();
		List<SourceLine> fl = IntStream.rangeClosed(1, FS.split("\n", -1).length).mapToObj(i -> new SourceLine("fs", i))
			.toList();
		ProgramTranslation t = Translator.translate(new ProgramRequest("gbuffers_entities",
			ProgramRequest.Family.GBUFFERS,
			List.of(new StageSource(Stage.VERTEX, VS, vl), new StageSource(Stage.FRAGMENT, FS, fl)),
			VertexLayout.of(new VertexAttribute(ProgramRequest.Semantic.POSITION, "float2", 0, 0)),
			List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(), UniformSchema.none(), null, false,
			new PerVertexIds(EngineSlots.PER_VERTEX_IDS, null, null)));
		assertTrue(t.ok(), t.diagnostics().toString());

		int width = 16, height = 8;
		// ten vertices: 0 and 1 stay unused (the base vertex skips them), 2-5 quad A (left), 6-9 quad B (right)
		float[][] positions = {{0, 0}, {0, 0},
			{-1, 1}, {0, 1}, {-1, -1}, {0, -1},
			{0, 1}, {1, 1}, {0, -1}, {1, -1}};
		byte[] vertices = new byte[positions.length * 8];
		ByteBuffer.wrap(vertices).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
			.put(toFloats(positions));
		// twelve uint16 indices, one draw: quad A then quad B, sequential within each quad
		short[] indices = {0, 1, 2, 2, 1, 3, 4, 5, 6, 6, 5, 7};
		// one id record per vertex (three ints): entity A on vertices 2-5, entity B on 6-9
		int[] entity = new int[10], blockEntity = new int[10], item = new int[10];
		for (int v = 2; v <= 5; v++) {
			entity[v] = 21;
			blockEntity[v] = 200;
			item[v] = 7;
		}
		for (int v = 6; v <= 9; v++) {
			entity[v] = 42;
			blockEntity[v] = 240;
			item[v] = 9;
		}
		byte[] ids = new byte[10 * 12];
		ByteBuffer idBuf = ByteBuffer.wrap(ids).order(ByteOrder.LITTLE_ENDIAN);
		for (int v = 0; v < 10; v++) {
			idBuf.putInt(entity[v]).putInt(blockEntity[v]).putInt(item[v]);
		}
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			byte[] pixels = gpu.drawIndexed(t.msl(), t.vertexEntry(), t.fragmentEntry(), HeadlessGpu.RGBA32_FLOAT,
				width, height, vertices, 8, ids, EngineSlots.PER_VERTEX_IDS, indices, indices.length, 0, 2);
			float[] rgba = new float[width * height * 4];
			ByteBuffer.wrap(pixels).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(rgba);
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					int i = (y * width + x) * 4;
					boolean left = x < width / 2;
					// without the base vertex the left quad would read the id records of vertices 0-3 and come out 0
					assertEquals(left ? 21 : 42, rgba[i], x + "," + y + " entityId");
					assertEquals(left ? 200 : 240, rgba[i + 1], x + "," + y + " blockEntityId");
					assertEquals(left ? 7 : 9, rgba[i + 2], x + "," + y + " currentRenderedItemId");
					assertEquals(1.0f, rgba[i + 3], x + "," + y + " alpha");
				}
			}
		}
	}

	private static float[] toFloats(float[][] values) {
		float[] out = new float[values.length * 2];
		for (int i = 0; i < values.length; i++) {
			out[i * 2] = values[i][0];
			out[i * 2 + 1] = values[i][1];
		}
		return out;
	}
}
