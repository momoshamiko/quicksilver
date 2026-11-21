// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import dev.mcms.metal.BackendPassHarness;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.StagedVertexBuffer.Draw;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The shadow dispatch's staged buffers end their frame each frame ({@link ShadowEntityDispatch#endFrame}, which the game's
 * {@code RenderBuffers.endFrame} calls through {@code EngineShadowBufferEndFrameMixin}). A frame's GPU buffer is returned to its
 * pool only at that end: without it each frame would take a new buffer, and the pool would keep every one (E5 review item 3).
 * The check is the pool's own bookkeeping: after the frame's end, no buffer of the frame is still marked used. It is
 * deterministic (no GPU timing): the pool's recycling waits for GPU fences, the marking does not.
 */
@Tag("gpu")
class ShadowEntityDispatchBufferGpuTest {

	/** POSITION_COLOR: 16 bytes per vertex (DefaultVertexFormat, javap 2026-11-14). */
	private static final int VERTS_PER_QUAD = 4;

	@BeforeAll
	static void device() throws Exception {
		// MetalPipeline's cache dir reads Fabric's game dir, which no test has: the cache is off before any Metal class initialises.
		System.setProperty("mcms.metal.mslCache", "false");
		if (RenderSystem.tryGetDevice() == null) {
			RenderSystem.initRenderThread();
		}
		if (RenderSystem.tryGetDevice() == null) {
			GpuDevice device = BackendPassHarness.newDevice();
			RenderSystem.initRenderer(device);
		}
	}

	/** One pipeline over a real staged buffer; its dispatcher is only a holder here (the frame prepare is not run). */
	private static ShadowEntityDispatch.Pipeline pipeline(String label) {
		FeatureRenderDispatcher dispatcher = new FeatureRenderDispatcher(new RenderBuffers(1), null, null, null, null);
		return new ShadowEntityDispatch.Pipeline(dispatcher, new StagedVertexBuffer(() -> label, 1 << 18));
	}

	/** The pool's used-this-frame list length of a staged buffer (the game's private pool, read for this check only). */
	private static int usedThisFrame(StagedVertexBuffer buffer) throws ReflectiveOperationException {
		Field poolField = StagedVertexBuffer.class.getDeclaredField("vertexBufferPool");
		poolField.setAccessible(true);
		Object pool = poolField.get(buffer);
		Field usedField = pool.getClass().getDeclaredField("usedThisFrame");
		usedField.setAccessible(true);
		return ((List<?>) usedField.get(pool)).size();
	}

	/** Stages one quad into the buffer and uploads it, as the shadow dispatch's prepare does. */
	private static void stageAndUpload(StagedVertexBuffer buffer) {
		Draw draw = buffer.appendDraw(DefaultVertexFormat.POSITION_COLOR, PrimitiveTopology.QUADS);
		VertexConsumer consumer = buffer.getVertexBuilder(draw);
		for (int v = 0; v < VERTS_PER_QUAD; v++) {
			consumer.addVertex(v, 0.0f, 0.0f).setColor(1.0f, 1.0f, 1.0f, 1.0f);
		}
		buffer.upload();
	}

	@Test
	void eachFrameReturnsItsBuffersToThePoolAtItsEnd() throws Exception {
		ShadowEntityDispatch.Pipeline entities = pipeline("test shadow entities");
		ShadowEntityDispatch.Pipeline blocks = pipeline("test shadow block entities");
		ShadowEntityDispatch dispatch = new ShadowEntityDispatch(null, null, entities, blocks);
		try {
			for (int frame = 1; frame <= 6; frame++) {
				stageAndUpload(entities.buffer);
				stageAndUpload(blocks.buffer);
				assertEquals(1, usedThisFrame(entities.buffer), "frame " + frame + ": the entity upload is in use");
				assertEquals(1, usedThisFrame(blocks.buffer), "frame " + frame + ": the block upload is in use");
				dispatch.endFrame();
				assertEquals(0, usedThisFrame(entities.buffer), "frame " + frame + ": the entity buffer returned at the frame end");
				assertEquals(0, usedThisFrame(blocks.buffer), "frame " + frame + ": the block buffer returned at the frame end");
			}
		} finally {
			dispatch.close();
		}
	}
}
