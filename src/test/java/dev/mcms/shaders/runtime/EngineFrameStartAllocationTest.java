// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

/**
 * The frame-start hook allocates nothing per frame on the paths a headless JVM can run (the D2 review). With the
 * engine off (the game's default, and every frame of the title screen) {@code frameStart} returns at its gate; the
 * active-state path (the per-frame surface, the dimension name) needs the game's render device and a level, so the
 * game runs cover it, not this test.
 */
class EngineFrameStartAllocationTest {

	private static final int CALLS = 2_000_000;

	@Test
	void theEngineOffFrameStartAllocatesNothing() {
		// Other tests in this JVM set the switches on (EngineGateTest). With them on and no device, the gate's backend
		// lookup costs about 2 KB a call (inference: an exception per call, from the 4.2 GB measured), so the off state is set here.
		System.setProperty("mcms.shaders", "off");  // an explicit off overrides any static setting left by other tests
		System.clearProperty("mcms.shaders.pack");
		Engine engine = new Engine();
		ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
		long thread = Thread.currentThread().threadId();
		for (int i = 0; i < 1_000; i++) {
			engine.frameStart(null, null, 0f);  // warm-up: the gate's first calls load and link everything they touch
		}
		long before = threads.getThreadAllocatedBytes(thread);
		for (int i = 0; i < CALLS; i++) {
			engine.frameStart(null, null, 0f);
		}
		long allocated = threads.getThreadAllocatedBytes(thread) - before;
		// ThreadMXBean counts whole thread-buffer refills: the measured total stays near 0.6 MB at 200,000 and at 2,000,000
		// calls, a fixed one-off cost (not per call). A per-call allocation of 8 bytes would be 16 MB over these calls.
		assertTrue(allocated <= 1024 * 1024, "the off-state frame start allocated " + allocated + " bytes over " + CALLS + " calls");
		assertTrue(engine.state() == Engine.State.OFF);
	}
}
