// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.entities;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.management.ManagementFactory;
import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;

/**
 * The frame path of the per-entity ids allocates nothing once warm (plan.md E4, the allocation rule): the registry
 * tables are arrays, and the ranges keep their arrays between frames. Measured with the thread's allocation counter
 * over repeated work after a warm-up pass.
 */
class EntityAllocationTest {

	private static final int[] DEFAULTS = {EntityIds.UNLISTED, EntityIds.UNLISTED, EntityIds.UNLISTED};
	private static final int[] ENTITY_IDS = {5, 6, 7};
	private static final int[] PARTICLE_IDS = {8, 9, 10};

	@Test
	void tableLookupsReadTheSetValuesAndTheAbsentValueOutsideTheTable() {
		DenseIds table = new DenseIds(4, 65535);
		table.set(2, 7);
		assertEquals(7, table.get(2));
		assertEquals(65535, table.get(0));
		assertEquals(65535, table.get(4));
		assertEquals(65535, table.get(-1));
		table.set(9, 3);
		table.set(-1, 3);
		assertEquals(65535, table.get(9));
		assertEquals(4, table.size());
	}

	@Test
	void repeatedTableLookupsAllocateNothing() {
		ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
		assumeTrue(bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled());
		long thread = Thread.currentThread().threadId();
		DenseIds table = new DenseIds(4096, 65535);
		for (int i = 0; i < 4096; i += 2) {
			table.set(i, i);
		}
		long sum = 0;
		for (int i = 0; i < 4096; i++) {
			sum += table.get(i);
		}
		long before = bean.getThreadAllocatedBytes(thread);
		for (int round = 0; round < 200; round++) {
			for (int i = 0; i < 4096; i++) {
				sum += table.get(i);
			}
		}
		long after = bean.getThreadAllocatedBytes(thread);
		assertTrue(sum > 0);
		assertTrue(after - before < 1024, "allocated " + (after - before) + " bytes over 819200 lookups");
	}

	@Test
	void rangeBookkeepingAllocatesNothingAfterTheWarmUpFrames() {
		ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
		assumeTrue(bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled());
		long thread = Thread.currentThread().threadId();
		Object entities = new Object();
		Object particles = new Object();
		EntityCapture.Ranges ranges = new EntityCapture.Ranges();
		for (int frame = 0; frame < 3; frame++) {
			runFrame(ranges, entities, particles);
		}
		long before = bean.getThreadAllocatedBytes(thread);
		for (int frame = 0; frame < 200; frame++) {
			runFrame(ranges, entities, particles);
		}
		long after = bean.getThreadAllocatedBytes(thread);
		assertArrayEquals(ENTITY_IDS, EntityVertexIds.idAt(ranges.sideBuffer(36).bytes(), 1));
		assertArrayEquals(PARTICLE_IDS, EntityVertexIds.idAt(ranges.sideBuffer(28).bytes(), 3));
		assertTrue(after - before < 1024, "allocated " + (after - before) + " bytes over 200 frames");
	}

	/**
	 * One frame: an entity draw (two vertices of stride 36 from its base vertex 0) then a particle draw (one vertex of
	 * stride 28, packed at base vertex 3, the draw's start rounded up to its own stride).
	 */
	private static void runFrame(EntityCapture.Ranges ranges, Object entities, Object particles) {
		ranges.reset();
		ranges.builderRequested(0, entities, ENTITY_IDS);
		ranges.builderRequested(72, particles, PARTICLE_IDS);
		ranges.lastBuilderFlushed(100);
		ranges.addUploadedDraw(entities, 36, 0);
		ranges.addUploadedDraw(particles, 28, 3);
		ranges.resolve(100, DEFAULTS);
	}
}
