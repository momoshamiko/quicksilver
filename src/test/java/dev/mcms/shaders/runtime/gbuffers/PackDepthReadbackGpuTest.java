// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

/**
 * The depth a pack program writes, read back from the device (E4 coords depth check). A full-screen pack program at
 * depth 0.3 draws into the engine's depth target (cleared to 1.0) with the flipped game compare; a farther draw at 0.6
 * is rejected; the target holds 0.3 everywhere. The composite samples this texture as {@code depthtex0}.
 */
@Tag("gpu")
class PackDepthReadbackGpuTest {

	private static final int W = 8, H = 8;
	private static final int RGBA8 = 70;
	private static final int DEPTH32F = 252;
	private static final int[] POSITION_FLOAT3 = {0, 0, MetalBridge.vertexFormat(GpuFormat.RGB32_FLOAT)};

	private static List<SourceLine> lines(String text) {
		List<SourceLine> out = new ArrayList<>();
		int count = (int) text.lines().count();
		for (int i = 1; i <= count; i++) {
			out.add(new SourceLine("depth.glsl", i));
		}
		return out;
	}

	private static ProgramTranslation translate(String name, String vs, String fs) {
		ProgramRequest request = new ProgramRequest(name, ProgramRequest.Family.GBUFFERS,
			List.of(new StageSource(Stage.VERTEX, vs, lines(vs)), new StageSource(Stage.FRAGMENT, fs, lines(fs))),
			VertexLayout.of(new VertexAttribute(Semantic.POSITION, "float3", 0, 0)),
			List.of(new ColorTarget(0, 0, 4)), List.of(), List.of(), List.of(), UniformSchema.none(), null, false);
		ProgramTranslation translation = Translator.translate(request);
		assertTrue(translation.ok(), "translation of " + name + " failed");
		return translation;
	}

	/** A pipeline with a Depth32Float attachment and one RGBA8 target, built the way the plans build a pack pipeline. */
	private static long pipeline(long ctx, ProgramTranslation translation) {
		int[] descriptor = GbufferPlans.descriptorWords(GbufferPlans.vertexWords(12, List.of(POSITION_FLOAT3)),
			new int[] {0}, new int[] {RGBA8}, Set.of(0), target -> null,
			MetalBridge.topologyClass(PrimitiveTopology.TRIANGLES), true);
		ShaderNative.Compiled library = ShaderNative.libraryNew(ctx, translation.msl(), ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true);
		assertTrue(library.ok(), "library failed: " + library.error());
		try {
			return MetalBridge.pipelineNew(ctx, library.library(), translation.vertexEntry(), library.library(),
				translation.fragmentEntry(), descriptor);
		} finally {
			MetalBridge.release(library.library());
		}
	}

	/** A full-screen triangle at depth z (the pass's own vertex data, released by the caller after the commit). */
	private static void fullScreenTriangle(long ctx, long enc, long pso, long depth, float z, List<Long> buffers) {
		long buffer = MetalBridge.newBuffer(ctx, 36);
		buffers.add(buffer);
		long at = MetalBridge.bufferContents(buffer);
		float[] v = {-1, -1, z, 3, -1, z, -1, 3, z};
		for (int i = 0; i < v.length; i++) {
			MemoryUtil.memPutFloat(at + 4L * i, v[i]);
		}
		MetalBridge.renderPipeline(enc, pso, depth, false, false, 0, 0, MetalBridge.primitive(PrimitiveTopology.TRIANGLES));
		MetalBridge.vertexBuffer(enc, EngineSlots.VERTEX_DATA, buffer, 0);
		MetalBridge.renderDraw(enc, 3, 1, 0, 0);
	}

	/** The engine's pack state: the flipped game depth (GREATER_THAN_OR_EQUAL written, LESS_THAN_OR_EQUAL now). */
	private static long flippedDepth(long ctx) {
		DepthStencilState flipped = GameDepth.flipped(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true));
		return MetalBridge.depthStateNew(ctx, MetalBridge.compareWord(flipped.depthTest()), flipped.writeDepth());
	}

	@Test
	void aPackProgramsDepthWriteReachesTheDepthTextureAndTheFarDrawIsRejected() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long enc = gpu.encoder();
			String vs = "#version 120\nvoid main() { gl_Position = gl_Vertex; }\n";
			String fs = "#version 120\nvoid main() { gl_FragData[0] = vec4(0.0, 1.0, 0.0, 1.0); }\n";
			long nearPso = pipeline(ctx, translate("near", vs, fs));
			long farPso = pipeline(ctx, translate("far", vs, "#version 120\nvoid main() { gl_FragData[0] = vec4(1.0, 0.0, 0.0, 1.0); }\n"));
			long depth = flippedDepth(ctx);
			long colour = MetalBridge.newTexture(ctx, GpuFormat.RGBA8_UNORM, W, H, false);
			long depthTarget = MetalBridge.newTexture(ctx, GpuFormat.D32_FLOAT, W, H, false);
			float[] clear = {0, 0, 0, 1};
			List<Long> buffers = new ArrayList<>();
			MetalBridge.renderBegin(enc, new long[] {colour}, new float[][] {clear}, depthTarget, true, 1.0f, W, H);
			fullScreenTriangle(ctx, enc, nearPso, depth, 0.3f, buffers);
			fullScreenTriangle(ctx, enc, farPso, depth, 0.6f, buffers);
			MetalBridge.endEncoders(enc);
			MetalBridge.commitAndWait(enc);
			byte[] pixels = gpu.readTexture(colour, 0, 0, W, H, 1, 4);
			byte[] depths = gpu.readTexture(depthTarget, 0, 0, W, H, 1, HeadlessGpu.bytesPerPixel(DEPTH32F));
			ByteBuffer buffer = ByteBuffer.wrap(depths).order(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < W * H; i++) {
				// The vertex epilogue maps the GL clip depth z (0.3) to Metal's range: 0.5 * (z + 1). The composite inverts it.
				assertEquals(0.5f * (0.3f + 1f), buffer.getFloat(4 * i), 0.001f, "the near pack draw's depth at texel " + i);
				assertEquals(0, pixels[4 * i] & 0xff, "the far draw is rejected at texel " + i);
			}
			buffers.forEach(MetalBridge::release);
		}
	}
}
