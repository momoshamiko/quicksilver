// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.compute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The image names a pack declares (engine-pack-format.md section 14): {@code colorimg0} to {@code colorimg5} are the
 * colour targets' images and {@code shadowcolorimg0} and {@code shadowcolorimg1} the shadow colour buffers'. Kernels,
 * composite passes and the gbuffers binds all read them through these two lookups.
 */
class ComputeKernelImageNamesTest {

	@Test
	void everyColourImageNameIsItsTarget() {
		for (int i = 0; i <= 5; i++) {
			assertEquals(i, ComputeKernel.colourTargetOf("colorimg" + i));
		}
	}

	@Test
	void everyShadowColourImageNameIsItsBuffer() {
		assertEquals(0, ComputeKernel.shadowColourImageOf("shadowcolorimg0"));
		assertEquals(1, ComputeKernel.shadowColourImageOf("shadowcolorimg1"));
	}

	@Test
	void otherNamesAreNoImage() {
		// A target past the six images, a sampler name, a shadow name, a wrong case and a two-digit index.
		for (String name : new String[] {"colorimg6", "colorimg", "colortex0", "Colorimg0", "colorimg00",
			"shadowcolorimg0"}) {
			assertEquals(-1, ComputeKernel.colourTargetOf(name), name);
		}
		for (String name : new String[] {"shadowcolorimg2", "shadowcolorimg", "shadowcolor0", "shadowcolorImg0",
			"colorimg0"}) {
			assertEquals(-1, ComputeKernel.shadowColourImageOf(name), name);
		}
	}

	@Test
	void theLookupsAllocateNothing() {
		// RIL-567: the gbuffers binds call both per sampler of every draw (Engine.EngineBindings.texture). 100000 lookups
		// of every kind of name must not allocate.
		String[] names = {"colorimg0", "colorimg5", "colorimg6", "shadowcolorimg1", "shadowcolorimg2", "colortex3"};
		com.sun.management.ThreadMXBean threads =
			(com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
		int sink = 0;
		for (int i = 0; i < 10_000; i++) {
			String name = names[i % names.length];
			sink += ComputeKernel.colourTargetOf(name) + ComputeKernel.shadowColourImageOf(name);  // warm up
		}
		long before = threads.getCurrentThreadAllocatedBytes();
		for (int i = 0; i < 100_000; i++) {
			String name = names[i % names.length];
			sink += ComputeKernel.colourTargetOf(name) + ComputeKernel.shadowColourImageOf(name);
		}
		long allocated = threads.getCurrentThreadAllocatedBytes() - before;
		assertTrue(allocated < 64 * 1024, "100000 lookups allocated " + allocated + " bytes (sink " + sink + ")");
	}
}
