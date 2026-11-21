// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.pack.PropertiesText;
import dev.mcms.shaders.pack.ShaderProperties;
import dev.mcms.shaders.runtime.uniforms.FrameUniforms;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Unit and GPU tests for Stage 2 runtime fixes (alphaTestRef, BufferSlots comments, CustomImages clear-once). */
final class RuntimeStage2Test {

	@Test
	void setAlphaTestRefWritesImmediatelyToFrameUniformBuffer() {
		FrameUniforms uniforms = new FrameUniforms();
		int offset = FrameUniforms.schema(0).fields().stream()
			.filter(f -> f.name().equals("alphaTestRef"))
			.findFirst().orElseThrow().offset();
		ByteBuffer buf = ByteBuffer.allocateDirect(FrameUniforms.size()).order(ByteOrder.LITTLE_ENDIAN);

		uniforms.setAlphaTestRef(0.5f);
		buf.clear();
		uniforms.write(buf);
		assertEquals(0.5f, buf.getFloat(offset), 1e-6f);

		uniforms.setAlphaTestRef(0.1f);
		buf.clear();
		uniforms.write(buf);
		assertEquals(0.1f, buf.getFloat(offset), 1e-6f);
	}

	@Test
	void bufferSlotsIgnoresCommentedOutBlocks() {
		String src = """
			// layout(std430, binding = 0) buffer CommentedLine { int a; };
			/*
			layout(std430, binding = 0) buffer CommentedBlock { int b; };
			*/
			layout(std430, binding = 0) buffer RealBlock { int c; };
			""";
		BufferSlots.Assignment assigned = BufferSlots.assign(src, 20);
		assertNull(assigned.error(), "commented duplicate binding must not fail: " + assigned.error());
		assertEquals(1, assigned.buffers().size());
		assertEquals("RealBlock", assigned.buffers().get(0).name());
		assertEquals(0, assigned.buffers().get(0).slot());
	}

	@Test
	@Tag("gpu")
	void persistentCustomImageIsZeroInitializedOnceThenPreserved() {
		List<ShaderProperties.ImageBinding> defs = ShaderProperties.from(PropertiesText.parse(
			"image.hist=hist_s RGBA RGBA8 UNSIGNED_BYTE false false 8 8\n")).images();
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long enc = gpu.encoder();
			CustomImages images = CustomImages.create(ctx, defs, 64, 64);
			try {
				long tex = images.texture(0);
				assertNotEquals(0L, tex);
				// Seed non-zero bytes to simulate uninitialized GPU memory before frame 1.
				byte[] dirty = new byte[8 * 8 * 4];
				Arrays.fill(dirty, (byte) 0x77);
				assertTrue(ShaderNative.textureUpload(ctx, tex, dirty, 8 * 4, dirty.length, 0, 0, 0, 0, 0, 8, 8, 1));

				// Frame 1: clearInitial(enc) zero-initializes the persistent image once before setup/begin.
				images.clearInitial(enc);
				images.clearEachFrame(enc);
				MetalBridge.commitAndWait(enc);
				byte[] afterFirst = gpu.readTexture(tex, 0, 0, 8, 8, 1, 4);
				for (byte b : afterFirst) {
					assertEquals(0, b, "persistent image must be zero-initialized on its first frame");
				}

				// Simulate shader writing 0x55 during frame 1, then run frame 2's clearInitial + clearEachFrame.
				byte[] written = new byte[8 * 8 * 4];
				Arrays.fill(written, (byte) 0x55);
				assertTrue(ShaderNative.textureUpload(ctx, tex, written, 8 * 4, written.length, 0, 0, 0, 0, 0, 8, 8, 1));

				images.clearInitial(enc);
				images.clearEachFrame(enc);
				MetalBridge.commitAndWait(enc);
				byte[] afterSecond = gpu.readTexture(tex, 0, 0, 8, 8, 1, 4);
				for (byte b : afterSecond) {
					assertEquals((byte) 0x55, b, "persistent image must keep its data on frame 2+");
				}
			} finally {
				images.release();
			}
		}
	}
}
