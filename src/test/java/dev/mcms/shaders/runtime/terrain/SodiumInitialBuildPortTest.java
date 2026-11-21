// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.UploadResourceBudget;
import org.junit.jupiter.api.Test;

/**
 * The light's upload budget for one build (E6 review item 7): half of the camera's budget in duration and in size. The
 * budget only exposes whether it still has room ({@code isAvailable}) after a consumption, so each test spends the budget
 * up to a boundary and checks which side of it the budget is on.
 */
class SodiumInitialBuildPortTest {

	@Test
	void theLightsDurationIsHalfTheCamerasWhenTheFrameIsLong() {
		// 1 s average frame: the camera's duration is 0.3 s, so the light's is 150 ms.
		UploadResourceBudget budget = SodiumInitialBuildPort.firstBuildBudget(1_000_000_000L, 1_000L);

		budget.consume(149_999_999L, 0L);
		assertTrue(budget.isAvailable(), "1 ns of the light's 150 ms duration is left");
		budget.consume(1L, 0L);
		assertFalse(budget.isAvailable(), "the light's duration ends at 150 ms, half the camera's 300 ms");
	}

	@Test
	void theLightsSizeIsHalfTheStagingLimit() {
		UploadResourceBudget budget = SodiumInitialBuildPort.firstBuildBudget(1_000_000_000L, 1_000L);

		budget.consume(0L, 499L);
		assertTrue(budget.isAvailable(), "1 byte of the light's 500-byte upload size is left");
		budget.consume(0L, 1L);
		assertFalse(budget.isAvailable(), "the light's upload size ends at 500 bytes, half the staging limit");
	}

	@Test
	void theLightsDurationIsHalfTheCamerasFloorWhenTheFrameIsShort() {
		// 16 ms average frame: 0.3 of it is under the camera's 10 ms floor, so the camera has 10 ms and the light 5 ms.
		UploadResourceBudget budget = SodiumInitialBuildPort.firstBuildBudget(16_000_000L, 1_000L);

		budget.consume(4_999_999L, 0L);
		assertTrue(budget.isAvailable(), "1 ns of the light's 5 ms duration is left");
		budget.consume(1L, 0L);
		assertFalse(budget.isAvailable(), "the light's duration ends at 5 ms, half the camera's 10 ms floor");
	}
}
