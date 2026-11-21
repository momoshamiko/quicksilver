// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mcms.shaders.pack.ShaderProperties;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The gbuffers size rule (GbufferPlans.sizedApart, 2026-11-15): a gbuffers pass attaches one size, so a program that writes
 * a colour target {@code size.buffer} sizes apart from the render is not run. A target at the render size is not sized
 * apart; a fixed size is, because it is not the render's size as the pack writes it.
 */
class GbufferSizeGuardTest {

	private static final ShaderProperties.BufferSize HALF = new ShaderProperties.BufferSize(0.5, 0.5, true);
	private static final ShaderProperties.BufferSize FULL = new ShaderProperties.BufferSize(1.0, 1.0, true);
	private static final ShaderProperties.BufferSize FIXED = new ShaderProperties.BufferSize(192, 108, false);

	@Test
	void aHalfSizedTargetIsSizedApart() {
		assertEquals(Optional.of(1), GbufferPlans.sizedApart(Set.of(0, 1), Map.of("colortex1", HALF)));
	}

	@Test
	void theLowestSizedApartTargetIsNamed() {
		assertEquals(Optional.of(4), GbufferPlans.sizedApart(Set.of(0, 4, 6),
			Map.of("colortex6", HALF, "colortex4", FIXED)));
	}

	@Test
	void anAliasSizesItsTarget() {
		assertEquals(Optional.of(4), GbufferPlans.sizedApart(Set.of(0, 4), Map.of("gaux1", HALF)));
	}

	@Test
	void aTargetAtTheRenderSizeIsNotSizedApart() {
		assertEquals(Optional.empty(), GbufferPlans.sizedApart(Set.of(0, 3), Map.of("colortex1", HALF)));
		assertEquals(Optional.empty(), GbufferPlans.sizedApart(Set.of(0, 1), Map.of("colortex1", FULL)));
	}

	@Test
	void colortex0AtAnotherSizeIsSizedApartToo() {
		assertEquals(Optional.of(0), GbufferPlans.sizedApart(Set.of(0), Map.of("colortex0", HALF)));
	}

	@Test
	void aPackWithNoSizesSizesNothingApart() {
		assertEquals(Optional.empty(), GbufferPlans.sizedApart(Set.of(0, 1, 2, 3, 4, 5, 6, 7), Map.of()));
	}
}
