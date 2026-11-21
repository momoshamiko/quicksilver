// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.pack.ConstValue;
import dev.mcms.shaders.pack.TargetUnion;
import dev.mcms.shaders.runtime.shadow.ShadowTargets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The shadow pass's {@code shadowtex1} copy (E5, {@code ShadowPassOps.copyShadowtex1}): a depth map written with a known
 * value is blitted into the second depth map, and every texel reads back the same value. Runs on the headless device.
 */
@Tag("gpu")
class ShadowBlitGpuTest {

	private static final int SIZE = 64;

	@Test
	void theShadowtex1CopyHoldsEveryTexelOfShadowtex0() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long enc = gpu.encoder();
			TargetUnion union = new TargetUnion(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(
				"shadowMapResolution", new ConstValue("int", List.of((double) SIZE))), List.of());
			ShadowTargets shadow = new ShadowTargets(union, false, true);
			assertTrue(shadow.create(Targets.engineTextures(ctx)), "the device refused the shadow maps");
			try {
				// shadowtex0 holds 0.25 (a depth-only pass with a 0.25 clear), shadowtex1 holds the clear's 1.0 first.
				MetalBridge.renderBegin(enc, new long[0], null, shadow.depth(0), true, 0.25f, SIZE, SIZE);
				MetalBridge.renderBegin(enc, new long[0], null, shadow.depth(1), true, 1.0f, SIZE, SIZE);
				MetalBridge.endEncoders(enc);
				MetalBridge.commitAndWait(enc);
				assertAllDepth(gpu, shadow.depth(1), 1f, "shadowtex1 before the copy");

				MetalBridge.blitTextureToTexture(enc, shadow.depth(0), shadow.depth(1), 0, SIZE, SIZE);
				MetalBridge.endEncoders(enc);
				MetalBridge.commitAndWait(enc);
				assertAllDepth(gpu, shadow.depth(0), 0.25f, "shadowtex0 after the copy");
				assertAllDepth(gpu, shadow.depth(1), 0.25f, "shadowtex1 after the copy");
			} finally {
				shadow.release();
			}
		}
	}

	/** Every texel of a depth map reads back as {@code expected}. */
	private static void assertAllDepth(HeadlessGpu gpu, long texture, float expected, String name) {
		byte[] bytes = gpu.readTexture(texture, 0, 0, SIZE, SIZE, 1, HeadlessGpu.bytesPerPixel(TargetFormats.DEPTH32_FLOAT));
		ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < SIZE * SIZE; i++) {
			assertEquals(expected, buffer.getFloat(4 * i), 1e-6f, name + " texel " + i);
		}
	}
}
