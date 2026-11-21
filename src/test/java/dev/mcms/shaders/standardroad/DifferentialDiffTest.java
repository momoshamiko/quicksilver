// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.standardroad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The R11F_G11F_B10F one-code-step rule of {@link DifferentialDiff#packedFloatStep}: the 11-bit R and G channels have
 * six fraction bits, the 10-bit B channel five, so a value in [2^e, 2^(e+1)) steps by 2^(e-6) and 2^(e-5) and the
 * subnormal steps are 2^-20 and 2^-19 — the same steps the harness decoder {@link DifferentialGpu#smallFloat} decodes.
 */
final class DifferentialDiffTest {

	private static final double R11_STEP_AT_1 = 0x1p-6, B10_STEP_AT_1 = 0x1p-5;

	@Test
	void stepIsOneUlpOfTheChannel() {
		assertEquals(R11_STEP_AT_1, DifferentialDiff.packedFloatStep(0, 1.0));
		assertEquals(R11_STEP_AT_1, DifferentialDiff.packedFloatStep(1, 1.5));
		assertEquals(B10_STEP_AT_1, DifferentialDiff.packedFloatStep(2, 1.0));
		// a value in [2^e, 2^(e+1)) steps by 2^(e-6) in R and G and 2^(e-5) in B
		assertEquals(0x1p-5, DifferentialDiff.packedFloatStep(0, 3.9));
		assertEquals(0x1p-4, DifferentialDiff.packedFloatStep(1, 4.0));
		assertEquals(0x1p-3, DifferentialDiff.packedFloatStep(2, 7.9));
		// the subnormal steps: the smallest normal is 2^-14
		assertEquals(0x1p-20, DifferentialDiff.packedFloatStep(0, 0.0));
		assertEquals(0x1p-20, DifferentialDiff.packedFloatStep(1, 0x1p-15));
		assertEquals(0x1p-19, DifferentialDiff.packedFloatStep(2, 0x1p-14 / 2));
	}

	@Test
	void stepCoversEveryAdjacentDecodablePair() {
		for (int channel = 0; channel < 3; channel++) {
			int bits = channel < 2 ? 11 : 10;
			int fractionBits = channel < 2 ? 6 : 5;
			for (int code = 0; code + 1 < 1 << bits; code++) {
				float a = DifferentialGpu.smallFloat(code, fractionBits);
				float b = DifferentialGpu.smallFloat(code + 1, fractionBits);
				if (!Float.isFinite(a) || !Float.isFinite(b)) {
					continue;
				}
				double step = DifferentialDiff.packedFloatStep(channel, Math.max(Math.abs((double) a), Math.abs(b)));
				if (a == b) {
					continue; // the denormal-to-normal boundary can repeat a value on float rounding
				}
				assertTrue(Math.abs(b - a) <= step,
					"channel " + channel + " code " + code + ": gap " + (b - a) + " > step " + step);
			}
		}
	}
}
