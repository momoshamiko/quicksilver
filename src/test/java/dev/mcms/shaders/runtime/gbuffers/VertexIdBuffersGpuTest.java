// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.mcms.metal.BackendPassHarness;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.runtime.EngineSlots;
import dev.mcms.shaders.runtime.entities.EntityCapture;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.StagedVertexBuffer.Draw;
import net.minecraft.client.renderer.StagedVertexBuffer.ExecuteInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The per-vertex id side buffers' binds on the device (E4 review items 4 and 8). A draw no upload covers binds the
 * defaults record from its first bind on (no draw is dropped for lack of an upload); a vertex buffer the game has closed
 * serves no records, so a later buffer at the same address cannot read them.
 */
@Tag("gpu")
class VertexIdBuffersGpuTest {

	private static final int[] IDS = {21, 200, 7}, DEFAULTS = {3, 4, 5};
	private static final VertexFormat FORMAT = DefaultVertexFormat.POSITION_COLOR;
	private static final int STRIDE = 16, W = 8, H = 8;

	/** Starts the device once per JVM, as the staged-buffer tests do. */
	private static void initDevice() throws Exception {
		// MetalPipeline's cache dir reads the game dir, which no test has: the cache is off before any Metal class initialises.
		System.setProperty("mcms.metal.mslCache", "false");
		if (RenderSystem.tryGetDevice() == null) {
			RenderSystem.initRenderThread();  // the staged buffers assert they run on the render thread
			RenderSystem.initRenderer(BackendPassHarness.newDevice());
		}
	}

	@Test
	void aDrawNoUploadCoversBindsTheDefaultsFromItsFirstBind() throws Exception {
		initDevice();
		long ctx = MetalBridge.currentContext();
		long target = MetalBridge.newTexture(ctx, GpuFormat.RGBA8_UNORM, W, H, false);
		long enc = MetalBridge.createHeadlessEncoder(ctx);
		VertexIdBuffers ids = new VertexIdBuffers();
		try {
			MetalBridge.renderBegin(enc, new long[] {target}, new float[][] {{0, 0, 0, 1}}, 0, false, 0.0f, W, H);
			assertFalse(ids.bind(enc, EngineSlots.PER_VERTEX_IDS, 0L, STRIDE, DEFAULTS),
				"no upload published: the draw binds the defaults record and is not dropped");
		} finally {
			ids.close();
			MetalBridge.endEncoders(enc);
			MetalBridge.commitAndWait(enc);
			MetalBridge.release(target);
		}
	}

	@Test
	void aClosedVertexBufferServesNoRecords() throws Exception {
		initDevice();
		StagedVertexBuffer staged = new StagedVertexBuffer(() -> "closed vertex buffer", 1 << 18);
		long ctx = MetalBridge.currentContext();
		long target = MetalBridge.newTexture(ctx, GpuFormat.RGBA8_UNORM, W, H, false);
		long enc = MetalBridge.createHeadlessEncoder(ctx);
		VertexIdBuffers ids = new VertexIdBuffers();
		try {
			Draw draw = staged.appendDraw(FORMAT, PrimitiveTopology.TRIANGLES);
			EntityCapture.Ranges ranges = new EntityCapture.Ranges();
			ranges.builderRequested(0, draw, IDS);
			VertexConsumer consumer = staged.getVertexBuilder(draw);
			for (int v = 0; v < 3; v++) {
				consumer.addVertex(v == 0 ? 0f : 1f, v == 2 ? 1f : 0f, 0f).setColor(1f, 1f, 1f, 1f);
			}
			ranges.lastBuilderFlushed(3L * STRIDE);
			staged.upload();
			ExecuteInfo info = staged.getExecuteInfo(draw);
			ranges.addUploadedDraw(draw, STRIDE, info.baseVertex());
			ranges.resolve(3L * STRIDE, DEFAULTS);
			GpuBuffer vertex = info.vertexBuffer();
			long handle = MetalBridge.bufferHandle(vertex);
			ids.publish(vertex, ranges);

			MetalBridge.renderBegin(enc, new long[] {target}, new float[][] {{0, 0, 0, 1}}, 0, false, 0.0f, W, H);
			assertTrue(ids.bind(enc, EngineSlots.PER_VERTEX_IDS, handle, STRIDE, DEFAULTS),
				"a published, open vertex buffer binds its own records");
			vertex.close();
			assertFalse(ids.bind(enc, EngineSlots.PER_VERTEX_IDS, handle, STRIDE, DEFAULTS),
				"a closed vertex buffer binds the defaults, never its records");
		} finally {
			ids.close();
			MetalBridge.endEncoders(enc);
			MetalBridge.commitAndWait(enc);
			MetalBridge.release(target);
			staged.close();
		}
	}
}
