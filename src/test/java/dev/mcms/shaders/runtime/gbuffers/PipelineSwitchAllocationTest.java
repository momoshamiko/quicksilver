// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import dev.mcms.metal.BackendPassHarness;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A pipeline switch allocates nothing once the delegate has resolved the pipeline (E4 review item 4): the plan of each
 * backend pipeline is kept by its object, and the drops log their reasons once, so the switches build no string and no
 * key. Covers the three outcomes a switch can have: a planned pipeline the phase does not draw (silent), a pipeline with
 * no plan under its strides (INFO once) and one no plan names (WARN once).
 */
class PipelineSwitchAllocationTest {

	private static final int WARM_UP = 20_000, SWITCHES = 300_000;
	/** Well under one allocation per switch: the old lookup built a key, a string and a copy of the strides per switch. */
	private static final long LIMIT_BYTES = 64 * 1024;

	/**
	 * MetalPipeline reads its MSL cache switch once, when its class loads, and the default ("true") asks the Fabric game
	 * directory for the cache, which a plain test does not have. The detached pipelines here need no cache: the switch is
	 * off for the test's run, and restored after it.
	 */
	@BeforeAll
	static void withoutTheMslCache() {
		System.setProperty("mcms.metal.mslCache", "false");
	}

	@AfterAll
	static void restoreTheMslCacheSwitch() {
		System.clearProperty("mcms.metal.mslCache");
	}

	@Test
	void manySwitchesAllocateNothingOnceEachPipelineIsResolved() {
		GbufferPlans.Plan drawnElsewhere = GbufferStrideLookupTest.emptyPlan("solid_terrain");
		GbufferPlans plans = GbufferPlans.forTestLayouts(Map.of(
			"minecraft:pipeline/solid_terrain", Map.of("", drawnElsewhere),
			"minecraft:pipeline/item_cutout", Map.of("44", GbufferStrideLookupTest.emptyPlan("item_cutout"))));
		GbufferDelegate delegate = new GbufferDelegate(plans, null);
		delegate.phase(GbufferPhase.TRANSLUCENT);
		Object[] pipelines = {
			BackendPassHarness.detachedPipeline("minecraft:pipeline/solid_terrain", List.of()),
			BackendPassHarness.detachedPipeline("minecraft:pipeline/item_cutout", List.of()),
			BackendPassHarness.detachedPipeline("minecraft:pipeline/gui", List.of()),
		};
		ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
		assertTrue(threads.isThreadAllocatedMemoryEnabled(), "the JVM must count thread allocations");
		long thread = Thread.currentThread().threadId();
		for (int i = 0; i < WARM_UP; i++) {
			delegate.setPipeline(0L, pipelines[i % pipelines.length], List.of());
		}
		long before = threads.getThreadAllocatedBytes(thread);
		for (int i = 0; i < SWITCHES; i++) {
			delegate.setPipeline(0L, pipelines[i % pipelines.length], List.of());
		}
		long allocated = threads.getThreadAllocatedBytes(thread) - before;
		assertTrue(allocated <= LIMIT_BYTES, "pipeline switches allocated " + allocated + " bytes over " + SWITCHES
			+ " switches (limit " + LIMIT_BYTES + ")");
		assertEquals(1, delegate.warnings().size(),
			"only the pipeline no plan names warns, once: the unplanned strides log at INFO, the restore is silent");
	}
}
