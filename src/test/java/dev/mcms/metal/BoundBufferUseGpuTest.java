// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import dev.mcms.shaders.metal.HeadlessGpu;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A buffer the engine binds through {@link MetalBridge#renderBuffer(long, int, GpuBuffer, long)} is used by the submit
 * being recorded (E4 review item 3). A whole-buffer write after the bind must not overwrite the bound memory in place,
 * because the GPU may still read the bound bytes: the write moves the buffer to fresh memory instead. Before the fix the
 * bind left the buffer unused, the write copied on the CPU into the memory the draw reads, and the handle stayed put.
 */
@Tag("gpu")
class BoundBufferUseGpuTest {

	private static final int SIZE = 64, W = 8, H = 8;

	@Test
	void aWholeWriteAfterTheBindMovesTheBufferOffItsMemory() throws Exception {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			MetalEncoder owner = (MetalEncoder) MetalBridge.bufferOwner(ctx);
			GpuBuffer buffer = MetalBridge.newGpuBuffer(owner, ctx, SIZE);
			long target = MetalBridge.newTexture(ctx, GpuFormat.RGBA8_UNORM, W, H, false);
			long enc = MetalBridge.createHeadlessEncoder(ctx);
			try {
				MetalBridge.renderBegin(enc, new long[] {target}, new float[][] {{0, 0, 0, 1}}, 0, false, 0.0f, W, H);
				long before = MetalBridge.bufferHandle(buffer);
				MetalBridge.renderBuffer(enc, 3, buffer, 0);
				owner.writeToBuffer(buffer.slice(0, SIZE), ByteBuffer.allocateDirect(SIZE));
				assertNotEquals(before, MetalBridge.bufferHandle(buffer),
					"a write to a buffer the pass bound must not overwrite the memory the GPU may read");
			} finally {
				MetalBridge.endEncoders(enc);
				MetalBridge.commitAndWait(enc);
				MetalBridge.release(target);
			}
		}
	}
}
