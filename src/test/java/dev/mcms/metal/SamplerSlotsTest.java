// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The binding layer keeps a sampler slot or a texture index only when the Metal stage has it; anything else is never bound. */
final class SamplerSlotsTest {

	@Test
	void slotsOfAStageKeepTheirIndex() {
		for (int slot = 0; slot < SamplerSlots.LIMIT; slot++) {
			assertEquals(slot, SamplerSlots.check(slot, "test"), "slot " + slot + " is one a stage has");
		}
	}

	@Test
	void noSamplerStaysNone() {
		assertEquals(-1, SamplerSlots.check(-1, "test"));
	}

	/** Complementary's entity overlay bound its sampler at texture index 27 (2026-11-15): past the stage's slots, so dropped. */
	@Test
	void aSlotPastTheStageLimitIsDroppedNotBound() {
		assertEquals(-1, SamplerSlots.check(SamplerSlots.LIMIT, "test"));
		assertEquals(-1, SamplerSlots.check(27, "test"));
	}

	@Test
	void textureIndicesZeroToThirtyAreBound() {
		assertTrue(SamplerSlots.textureIndexOk(0, "test"));
		assertTrue(SamplerSlots.textureIndexOk(SamplerSlots.TEXTURE_LIMIT - 1, "test"), "index 30, the CloudFaces slot, binds");
	}

	/** Metal's texture table ends at 30 (TextureIndexRangeGpuTest); a bind at 31 or a negative index is never sent. */
	@Test
	void aTextureIndexPastThirtyOrNegativeIsNeverBound() {
		assertFalse(SamplerSlots.textureIndexOk(SamplerSlots.TEXTURE_LIMIT, "test"));
		assertFalse(SamplerSlots.textureIndexOk(34, "test"), "the shadow rows' index 34 is refused");
		assertFalse(SamplerSlots.textureIndexOk(-1, "test"));
	}
}
