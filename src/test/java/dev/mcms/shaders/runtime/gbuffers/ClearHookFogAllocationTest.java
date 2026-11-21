// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import dev.mcms.shaders.pack.TargetUnion;
import dev.mcms.shaders.runtime.Targets;
import dev.mcms.shaders.runtime.uniforms.FrameUniforms;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

/**
 * The per-frame refresh of colortex0's fog default and the colour copy the clears make allocate nothing (plan.md E3's
 * frame rule). Plain (no device): it runs in every build. The GPU test {@code ClearHookFogGpuTest} checks the values.
 */
class ClearHookFogAllocationTest {

	private static final int W = 16, H = 16;

	@Test
	void theRefreshAndTheColourCopyAllocateNothing() {
		TargetUnion union = new TargetUnion(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of());
		Targets targets = new Targets(union, Map.of(), Set.of(0, 1), false, false, W, H,
			(format, width, height, mips) -> 0L, null);
		FrameUniforms uniforms = new FrameUniforms();
		uniforms.update(GbufferFixtures.inputs(W, H, new Matrix4f(), 90f, 256f, 0.2f, 0.4f, 0.6f));
		float[] out = new float[4];
		ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
		long thread = Thread.currentThread().threadId();
		for (int i = 0; i < 1_000; i++) {  // warm-up: the first calls load and compile what they touch
			targets.refreshFogClear(uniforms);
			targets.clearColorInto(0, out);
		}
		long before = threads.getThreadAllocatedBytes(thread);
		for (int i = 0; i < 100_000; i++) {
			targets.refreshFogClear(uniforms);
			targets.clearColorInto(0, out);
		}
		long allocated = threads.getThreadAllocatedBytes(thread) - before;
		assertTrue(allocated < 16 * 1024, "the refresh and the colour copy allocated " + allocated + " bytes");
		assertEquals(0.2f, out[0], 1e-6f, "the copy holds the frame's fog");
		assertEquals(1f, out[3], 1e-6f, "alpha stays 1");
	}
}
