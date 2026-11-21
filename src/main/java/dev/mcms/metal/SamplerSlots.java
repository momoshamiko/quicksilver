// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The slot indices of the binding layer. A render or compute stage has {@link #LIMIT} sampler slots (0 to 15) and
 * {@link #TEXTURE_LIMIT} texture indices (0 to 30): Metal refuses a sampler or texture bound past them, and on the driver an
 * abort inside the draw (2026-11-15: Complementary's entity overlay bound its sampler at texture index 27). The binding layer
 * checks each slot it is given, so a wrong slot number from a program cannot reach the driver.
 */
final class SamplerSlots {

	/** Sampler slots one Metal stage has. */
	static final int LIMIT = 16;
	/** Texture indices one Metal stage has (the texture argument table, 0 to 30; BufferIndexRangeGpuTest's sibling). */
	static final int TEXTURE_LIMIT = 31;

	private static final Logger LOG = LoggerFactory.getLogger("Quicksilver");
	private static final AtomicBoolean REPORTED_SAMPLER = new AtomicBoolean();
	private static final AtomicBoolean REPORTED_TEXTURE = new AtomicBoolean();

	private SamplerSlots() {
	}

	/**
	 * The sampler slot to bind: a slot from 0 to 15 is returned as it is, and a negative slot (no sampler in that stage) as -1.
	 * A slot past the limit is dropped, so the texture binds without a sampler there; the first one is logged, and later ones
	 * are not.
	 */
	static int check(int slot, String what) {
		if (slot < 0) {
			return -1;
		}
		if (slot < LIMIT) {
			return slot;
		}
		if (REPORTED_SAMPLER.compareAndSet(false, true)) {
			LOG.warn("[Quicksilver] {} binds sampler slot {}, past the {} a Metal stage has: the sampler is not bound (reported once)",
				what, slot, LIMIT);
		}
		return -1;
	}

	/**
	 * Whether a texture index is one Metal has (0 to 30). An index past it is not bound at all: the first one is logged, and
	 * later ones are not. Negative indices are refused too.
	 */
	static boolean textureIndexOk(int index, String what) {
		if (index >= 0 && index < TEXTURE_LIMIT) {
			return true;
		}
		if (REPORTED_TEXTURE.compareAndSet(false, true)) {
			LOG.warn("[Quicksilver] {} binds texture index {}, outside the 0 to {} Metal has: the texture is not bound (reported once)",
				what, index, TEXTURE_LIMIT - 1);
		}
		return false;
	}
}
