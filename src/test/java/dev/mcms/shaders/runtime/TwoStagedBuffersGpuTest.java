// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.mcms.metal.BackendPassHarness;
import dev.mcms.metal.MetalBridge;
import java.lang.foreign.MemorySegment;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.StagedVertexBuffer.Draw;
import net.minecraft.client.renderer.StagedVertexBuffer.ExecuteInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The block-entity split's two staged buffers against the real game classes (plan.md E4; the 2026-11-14 preview 9
 * regression): the world's entities and the router's block entities stage in two {@code StagedVertexBuffer}s whose
 * uploads interleave in one frame (the router's inside {@code submitFeatures}, the world's at its
 * {@code prepareFeatures}), and every draw then reads its vertices at the buffer its {@code getExecuteInfo} names.
 * The test drives both buffers through the game's public staging path ({@code appendDraw} +
 * {@code getVertexBuilder}) on a real device, uploads in the split's order, and checks each draw's
 * {@code ExecuteInfo}: the vertex buffer object is its own buffer's, and the GPU bytes at {@code baseVertex} are
 * exactly the vertices written for that draw — over three frames, so the pools recycle ({@code endFrame}) and each
 * frame writes different bytes: a recycled, shared or mis-addressed allocation shows as the other buffer's or the
 * earlier frame's data.
 */
@Tag("gpu")
class TwoStagedBuffersGpuTest {

	/** POSITION_COLOR: 12 bytes of position then 4 of colour (DefaultVertexFormat's static init, javap 2026-11-14). */
	private static final int STRIDE = 16;
	private static final int VERTS_PER_QUAD = 4;

	/** The world's writes tag every vertex's x; the router's are offset so bytes cannot coincide. */
	private static final float WORLD_TAG = 0.0f;
	private static final float ROUTER_TAG = 1000.0f;

	@BeforeAll
	static void device() throws Exception {
		// MetalPipeline's cache dir reads Fabric's game dir, which no test has: the cache is off before any Metal
		// class initialises (the property is read once, in its static init).
		System.setProperty("mcms.metal.mslCache", "false");
		if (RenderSystem.tryGetDevice() == null) {  // the staged buffers assert they run on the render thread
			RenderSystem.initRenderThread();  // once per JVM: another GPU test class may have initialised it with the device
		}
		if (RenderSystem.tryGetDevice() == null) {
			GpuDevice device = BackendPassHarness.newDevice();
			RenderSystem.initRenderer(device);
		}
	}

	/** Writes one quad whose vertices carry {@code tag} on x, the draw's index on y and the vertex index on z. */
	private static void quad(StagedVertexBuffer buffer, Draw draw, float tag, int drawIndex) {
		VertexConsumer consumer = buffer.getVertexBuilder(draw);
		for (int v = 0; v < VERTS_PER_QUAD; v++) {
			consumer.addVertex(tag + drawIndex, drawIndex, v).setColor(1.0f, v / 4.0f, 0.25f, 1.0f);
		}
	}

	/** The GPU bytes at {@code byteOffset} of {@code buffer} (Apple silicon shared memory: the CPU reads what the GPU reads). */
	private static float readFloat(GpuBuffer buffer, long byteOffset) {
		return MemorySegment.ofAddress(MetalBridge.bufferAddress(buffer) + byteOffset).reinterpret(Float.BYTES)
			.get(java.lang.foreign.ValueLayout.JAVA_FLOAT, 0);
	}

	@Test
	void eachDrawReadsItsOwnStagedBufferWithItsOwnVertices() throws Exception {
		VertexFormat format = DefaultVertexFormat.POSITION_COLOR;
		StagedVertexBuffer world = new StagedVertexBuffer(() -> "test world", 1 << 18);
		StagedVertexBuffer router = new StagedVertexBuffer(() -> "test router", 1 << 18);
		try {
			for (int frame = 1; frame <= 3; frame++) {
				// The split's frame: the router's draw (the block entities) and the world's two (an entity model and
				// a second one sharing the buffer), submitted before either upload.
				Draw routerDraw = router.appendDraw(format, PrimitiveTopology.QUADS);
				Draw worldDraw = world.appendDraw(format, PrimitiveTopology.QUADS);
				Draw worldSecond = world.appendDraw(format, PrimitiveTopology.QUADS);
				quad(router, routerDraw, ROUTER_TAG + frame, 0);
				quad(world, worldDraw, WORLD_TAG + frame, 0);
				quad(world, worldSecond, WORLD_TAG + frame, 1);
				// The split's upload order: the router's prepare runs inside submitFeatures, the world's after it.
				router.upload();
				world.upload();

				// The draws' order at the phase hook: the world's executeSolid first, then the router's frame.
				ExecuteInfo worldInfo = world.getExecuteInfo(worldDraw);
				ExecuteInfo worldSecondInfo = world.getExecuteInfo(worldSecond);
				ExecuteInfo routerInfo = router.getExecuteInfo(routerDraw);
				System.out.printf("DBG frame %d: w1 base=%d first=%d idx=%d; w2 base=%d idx=%d; r base=%d idx=%d; wbuf=%x rbuf=%x%n",
					frame, worldInfo.baseVertex(), worldInfo.firstIndex(), worldInfo.indexCount(),
					worldSecondInfo.baseVertex(), worldSecondInfo.indexCount(),
					routerInfo.baseVertex(), routerInfo.indexCount(),
					System.identityHashCode(worldInfo.vertexBuffer()), System.identityHashCode(routerInfo.vertexBuffer()));
				StringBuilder w = new StringBuilder("DBG world:");
				for (int i = 0; i < 12; i++) {
					w.append(' ').append(readFloat(worldInfo.vertexBuffer(), 4L * i));
				}
				StringBuilder r = new StringBuilder("DBG router:");
				for (int i = 0; i < 8; i++) {
					r.append(' ').append(readFloat(routerInfo.vertexBuffer(), 4L * i));
				}
				System.out.println(w);
				System.out.println(r);

				assertNotSame(worldInfo.vertexBuffer(), routerInfo.vertexBuffer(),
					"frame " + frame + ": the world's draw and the router's must name different vertex buffers");
				assertEquals(6, worldInfo.indexCount(), "one quad is six indices");

				// Every draw's vertices: its tag on x, its draw index on y, the vertex index on z, at its own base.
				StringBuilder bad = new StringBuilder();
				checkQuad(worldInfo.vertexBuffer(), worldInfo.baseVertex(), WORLD_TAG + frame, 0, frame, bad, "world#1");
				checkQuad(worldSecondInfo.vertexBuffer(), worldSecondInfo.baseVertex(), WORLD_TAG + frame, 1, frame, bad,
					"world#2");
				checkQuad(routerInfo.vertexBuffer(), routerInfo.baseVertex(), ROUTER_TAG + frame, 0, frame, bad, "router");
				assertTrue(bad.isEmpty(), bad.toString());

				// The shared sequential index buffer both buffers' draws index with: quad k reads 4k..4k+3 in the
				// quad pattern, whatever staged buffer the draw belongs to (the garbage-triangle hunt's index half).
				checkQuadIndices(worldInfo, frame, bad, "world#1");
				checkQuadIndices(worldSecondInfo, frame, bad, "world#2");
				checkQuadIndices(routerInfo, frame, bad, "router");
				assertTrue(bad.isEmpty(), bad.toString());

				// The second draw follows the first with no gap (the packing the draw log shows).
				assertEquals(worldInfo.baseVertex() + VERTS_PER_QUAD, worldSecondInfo.baseVertex(),
					"frame " + frame + ": the world's second draw follows the first");

				world.endFrame();
				router.endFrame();
			}
		} finally {
			world.close();
			router.close();
		}
	}

	/** Checks the quad at {@code baseVertex} of {@code buffer}: its tag on x, its draw index on y, the vertex index on z.
	 * Each component is read twice — a second, later read of the same bytes catching memory the GPU is still writing. */
	private static void checkQuad(GpuBuffer buffer, int baseVertex, float tag, int drawIndex, int frame,
			StringBuilder bad, String which) {
		for (int v = 0; v < VERTS_PER_QUAD; v++) {
			long at = (long) (baseVertex + v) * STRIDE;
			for (int pass = 0; pass < 2; pass++) {
				float x = readFloat(buffer, at);
				float y = readFloat(buffer, at + 4);
				float z = readFloat(buffer, at + 8);
				if (Math.abs(x - (tag + drawIndex)) > 1e-4f || Math.abs(y - drawIndex) > 1e-4f
						|| Math.abs(z - v) > 1e-4f) {
					bad.append(String.format("%s f%d v%d pass%d base=%d at=%d: x=%f y=%f z=%f (want %.1f %d %d)%n",
						which, frame, v, pass, baseVertex, at, x, y, z, tag, drawIndex, v));
				}
			}
		}
	}

	/**
	 * Checks the sequential quad indices one draw reads ({@code firstIndex}, six of them): the pattern
	 * {@code 4k, 4k+1, 4k+2, 4k+2, 4k+3, 4k} with {@code k = firstIndex / 6}, read from the index buffer the draw's
	 * {@code ExecuteInfo} names — the buffer both staged buffers' draws share.
	 */
	private static void checkQuadIndices(ExecuteInfo info, int frame, StringBuilder bad, String which) {
		GpuBuffer indices = info.indexBuffer();
		int quad = info.firstIndex() / 6;
		int[] want = {4 * quad, 4 * quad + 1, 4 * quad + 2, 4 * quad + 2, 4 * quad + 3, 4 * quad};
		boolean wide = info.indexType() == com.mojang.renderpearl.api.pipeline.IndexType.INT;
		MemorySegment segment = MemorySegment.ofAddress(MetalBridge.bufferAddress(indices))
			.reinterpret((long) (info.firstIndex() + 6) * (wide ? 4 : 2));
		for (int i = 0; i < 6; i++) {
			int at = (info.firstIndex() + i) * (wide ? 4 : 2);
			int got = wide ? segment.get(java.lang.foreign.ValueLayout.JAVA_INT, at)
				: Short.toUnsignedInt(segment.get(java.lang.foreign.ValueLayout.JAVA_SHORT, at));
			if (got != want[i]) {
				bad.append(String.format("%s f%d index %d: %d (want %d)%n", which, frame, i, got, want[i]));
			}
		}
	}
}
