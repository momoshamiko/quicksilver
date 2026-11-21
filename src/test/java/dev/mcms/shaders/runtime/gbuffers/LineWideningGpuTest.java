// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.Semantic;
import dev.mcms.glsl.msl.ProgramRequest.StageSource;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.msl.ProgramRequest.VertexAttribute;
import dev.mcms.glsl.msl.ProgramRequest.VertexLayout;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.runtime.EngineSlots;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

/**
 * The line-widening epilogue's geometry on the GPU (E4 review item 11): a line from (-0.5, 0) to (0.5, 0) in NDC with
 * LINE_WIDTH 0.1 must cover the band |y| <= 0.1 between its ends. The epilogue offsets each vertex to the side its index
 * parity picks, so a quad is two triangles over the vertex list A, A, B, A, B, B (ids 0 and 2 on one side, 1 and 3 on the
 * other). With the identity projection (no frame block) the band is 0.2 NDC tall: 6.4 px of a 64 px target, so 6 or 7
 * rows of the pack's colour, and no colour beyond the ends.
 */
@Tag("gpu")
class LineWideningGpuTest {

	private static final int W = 64, H = 64;
	private static final int RGBA8 = 70;
	private static final int STRIDE = 44;
	private static final float LINE_WIDTH = 0.1f;
	private static final float AX = -0.5f, BX = 0.5f;

	private static final String VS = "#version 330\nvoid main() { gl_Position = gl_Vertex; }\n";
	private static final String FS = "#version 330\nout vec4 c;\nvoid main() { c = vec4(0.0, 1.0, 0.0, 1.0); }\n";

	@Test
	void theLineCoversItsWidthInRowsAndItsLengthInColumns() {
		byte[] pixels = draw();
		int rows = 0;
		for (int y = 0; y < H; y++) {
			if (green(pixels, W / 2, y)) {
				rows++;
			}
		}
		assertTrue(rows == 6 || rows == 7, "a 0.1 wide line covers 6.4 px of 64: " + rows + " rows at the middle column");
		assertFalse(green(pixels, W / 2, 4) || green(pixels, W / 2, H - 5), "nothing beyond the band");
		assertTrue(green(pixels, 20, H / 2), "the line covers its inside");
		assertTrue(green(pixels, 44, H / 2), "the line covers up to its far end");
		assertFalse(green(pixels, 8, H / 2), "no colour before the start");
		assertFalse(green(pixels, 56, H / 2), "no colour after the end");
	}

	private static boolean green(byte[] pixels, int x, int y) {
		int i = (y * W + x) * 4;
		return (pixels[i + 1] & 0xff) > 200 && (pixels[i] & 0xff) < 50;
	}

	/** The line: positions A, A, B, A, B, B, normals (1, 0, 0) (the direction to the end), the width on every vertex. */
	private static byte[] draw() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long enc = gpu.encoder();
			VertexLayout layout = VertexLayout.of(new VertexAttribute(Semantic.POSITION, "float3", 0, 0),
				new VertexAttribute(Semantic.NORMAL, "float3", 0, 28),
				new VertexAttribute(Semantic.LINE_WIDTH, "float1", 0, 40));
			ProgramRequest request = new ProgramRequest("line", ProgramRequest.Family.GBUFFERS,
				List.of(stage(Stage.VERTEX, VS), stage(Stage.FRAGMENT, FS)), layout,
				List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(), UniformSchema.none(), null, false);
			ProgramTranslation program = Translator.translate(request);
			assertTrue(program.ok(), () -> "translation failed: " + program.diagnostics());

			int[] position = {0, 0, MetalBridge.vertexFormat(GpuFormat.RGB32_FLOAT)};
			int[] normal = {1, 28, MetalBridge.vertexFormat(GpuFormat.RGB32_FLOAT)};
			int[] width = {2, 40, MetalBridge.vertexFormat(GpuFormat.R32_FLOAT)};
			int[] vertex = GbufferPlans.vertexWords(STRIDE, List.of(position, normal, width));
			int[] descriptor = GbufferPlans.descriptorWords(vertex, new int[] {0}, new int[] {RGBA8}, Set.of(0),
				target -> null, MetalBridge.topologyClass(PrimitiveTopology.TRIANGLES), false);
			ShaderNative.Compiled library = ShaderNative.libraryNew(ctx, program.msl(), ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true);
			assertTrue(library.ok(), "library failed: " + library.error());
			long pso = MetalBridge.pipelineNew(ctx, library.library(), program.vertexEntry(), library.library(),
				program.fragmentEntry(), descriptor);
			MetalBridge.release(library.library());

			float[][] corners = { { AX, 0, 0 }, { AX, 0, 0 }, { BX, 0, 0 }, { AX, 0, 0 }, { BX, 0, 0 }, { BX, 0, 0 } };
			long buffer = MetalBridge.newBuffer(ctx, 6 * STRIDE);
			long at = MetalBridge.bufferContents(buffer);
			for (int v = 0; v < 6; v++) {
				long base = at + (long) STRIDE * v;
				for (int c = 0; c < 3; c++) {
					MemoryUtil.memPutFloat(base + 4L * c, corners[v][c]);
				}
				MemoryUtil.memPutFloat(base + 28, 1f);  // normal (1, 0, 0): the direction the line runs in
				MemoryUtil.memPutFloat(base + 40, LINE_WIDTH);
			}
			long target = MetalBridge.newTexture(ctx, GpuFormat.RGBA8_UNORM, W, H, false);
			MetalBridge.renderBegin(enc, new long[] {target}, new float[][] {{0, 0, 0, 1}}, 0, false, 0f, W, H);
			MetalBridge.renderPipeline(enc, pso, 0, false, false, 0, 0, MetalBridge.primitive(PrimitiveTopology.TRIANGLES));
			MetalBridge.vertexBuffer(enc, EngineSlots.VERTEX_DATA, buffer, 0);
			MetalBridge.renderDraw(enc, 6, 1, 0, 0);
			MetalBridge.endEncoders(enc);
			MetalBridge.commitAndWait(enc);
			byte[] pixels = gpu.readTexture(target, 0, 0, W, H, 1, 4);
			MetalBridge.release(buffer);
			return pixels;
		}
	}

	private static StageSource stage(Stage stage, String text) {
		return new StageSource(stage, text,
			IntStream.rangeClosed(1, text.split("\n", -1).length).mapToObj(i -> new SourceLine("line", i)).toList());
	}
}
