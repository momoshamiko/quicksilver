// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.pack.TargetUnion;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The frame's depth clear of the Distant Horizons depth targets (plan.md E7): {@code dhDepthTex0} and {@code dhDepthTex1} are
 * cleared to the far value 1.0 with the engine's own depth, read back from the device. Both start at 0.25 here, so only the
 * clear can bring them to 1.0.
 */
@Tag("gpu")
class DistantDepthClearGpuTest {

	private static final int W = 8, H = 6;

	@Test
	void theFrameClearSetsBothDistantHorizonsDepthTargetsToTheFarValue() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long enc = gpu.encoder();
			TargetUnion union = new TargetUnion(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of());
			Targets targets = new Targets(union, Map.of(), Set.of(0), false, false, W, H, Targets.engineTextures(ctx), null);
			targets.wantDistantDepth(true);
			assertTrue(targets.create());
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment label = arena.allocateFrom("qs distant test", StandardCharsets.UTF_8);
				for (int d = 0; d < Targets.DISTANT_DEPTH_TARGETS; d++) {
					MetalBridge.renderBegin(enc, new long[0], null, targets.distantDepth(d), true, 0.25f, W, H);
				}
				EngineFrame.clearDepths(enc, targets, label);
				MetalBridge.endEncoders(enc);
				MetalBridge.commitAndWait(enc);
			}
			for (int d = 0; d < Targets.DISTANT_DEPTH_TARGETS; d++) {
				byte[] depths = gpu.readTexture(targets.distantDepth(d), 0, 0, W, H, 1,
					HeadlessGpu.bytesPerPixel(TargetFormats.DEPTH32_FLOAT));
				ByteBuffer buffer = ByteBuffer.wrap(depths).order(ByteOrder.LITTLE_ENDIAN);
				for (int i = 0; i < W * H; i++) {
					assertEquals(1.0f, buffer.getFloat(4 * i), 0f, "distant depth " + d + " at texel " + i);
				}
			}
			targets.release();
		}
	}
}
