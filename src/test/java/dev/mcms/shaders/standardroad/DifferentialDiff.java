// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.standardroad;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

/**
 * The per-pixel comparison of two renders of one target (the E2 tolerance, results/2026-11-13-e2-translator/README.md,
 * W4-B section). A pixel passes when every channel is within the tolerance of its format. A normalized channel (UNORM8,
 * SNORM8, UNORM16, the 10-bit colour and 2-bit alpha of RGB10_A2) passes within one code value, since the target rounds and
 * a last-bit difference is one code value. A float channel passes when its absolute difference is at most 2^-10 times the
 * larger magnitude plus 2^-24 (one half-float ulp at the value, and a floor for zero). R11F_G11F_B10F has its own rule
 * with no 99.9% allowance: every pixel must sit within one code step of the channel, which for a value in
 * [2^e, 2^(e+1)) is 2^(e-6) in R and G (11-bit floats, five exponent bits leave six fraction bits) and 2^(e-5) in B
 * (10-bit, five fraction bits); the subnormal steps are 2^-20 and 2^-19. NaN matches NaN and an infinity matches the
 * same infinity; anything else in a non-finite channel fails.
 *
 * @param pixels the pixels compared
 * @param okPixels the pixels that pass
 * @param maxDiff the largest absolute difference of any channel of any pixel
 * @param nonFinite the channels that were NaN or infinite in either render
 */
record DifferentialDiff(int format, long pixels, long okPixels, double maxDiff, long nonFinite) {

	static final double FLOAT_REL = 0x1p-10, FLOAT_FLOOR = 0x1p-24;

	/** The passing fraction the tolerance demands: every pixel for R11F_G11F_B10F, else the README's 99.9% allowance. */
	double requiredPassing() {
		return format == DifferentialGpu.RG11B10_FLOAT ? 1.0 : 0.999;
	}

	/** The fraction of pixels that pass. */
	double passing() {
		return pixels == 0 ? 1.0 : (double) okPixels / pixels;
	}

	@Override
	public String toString() {
		return String.format("%s: %d pixels, %.4f%% pass, max diff %.6g, non-finite %d", DifferentialGpu.formatName(format),
			pixels, 100.0 * passing(), maxDiff, nonFinite);
	}

	/** One code value of a normalized channel, or zero for a float channel. */
	static double codeValue(int format, int channel) {
		return switch (format) {
			case DifferentialGpu.R8_UNORM, DifferentialGpu.RG8_UNORM, DifferentialGpu.RGBA8_UNORM -> 1.0 / 255.0;
			case DifferentialGpu.RGBA8_SNORM -> 1.0 / 127.0;
			case DifferentialGpu.RG16_UNORM, DifferentialGpu.RGBA16_UNORM -> 1.0 / 65535.0;
			case DifferentialGpu.RGB10A2_UNORM -> channel < 3 ? 1.0 / 1023.0 : 1.0 / 3.0;
			default -> 0.0;
		};
	}

	/**
	 * R11F_G11F_B10F's one code step at a magnitude: R and G are 11-bit floats (five exponent bits leave six fraction
	 * bits, a value in [2^e, 2^(e+1)) steps by 2^(e-6), subnormals step by 2^-20), B is 10-bit (five fraction bits,
	 * 2^(e-5), subnormals 2^-19). This agrees with {@link DifferentialGpu#smallFloat}, the harness decoder.
	 */
	static double packedFloatStep(int channel, double magnitude) {
		int fractionBits = channel < 2 ? 6 : 5;
		if (magnitude < Math.scalb(1.0, -14)) {
			return Math.scalb(1.0, -14 - fractionBits);
		}
		return Math.scalb(1.0, Math.getExponent(magnitude) - fractionBits);
	}

	/** The number of distinct pixel values (whole-pixel byte patterns) of one render of a target. */
	static long distinctValues(int format, byte[] pixels) {
		int bpp = DifferentialGpu.bytesPerPixel(format);
		Set<ByteBuffer> distinct = new HashSet<>();
		for (int o = 0; o + bpp <= pixels.length; o += bpp) {
			distinct.add(ByteBuffer.wrap(pixels, o, bpp));
		}
		return distinct.size();
	}

	static DifferentialDiff of(int format, byte[] ours, byte[] theirs) {
		int comps = DifferentialGpu.channels(format);
		int bpp = DifferentialGpu.bytesPerPixel(format);
		long pixels = ours.length / bpp;
		long ok = 0;
		double max = 0;
		long nonFinite = 0;
		for (long p = 0; p < pixels; p++) {
			boolean pass = true;
			for (int c = 0; c < comps; c++) {
				int index = (int) (p * comps + c);
				float a = DifferentialGpu.component(format, ours, index);
				float b = DifferentialGpu.component(format, theirs, index);
				if (!Float.isFinite(a) || !Float.isFinite(b)) {
					nonFinite++;
					pass &= Float.compare(a, b) == 0;
					continue;
				}
				double diff = Math.abs((double) a - b);
				max = Math.max(max, diff);
				double tolerance;
				if (format == DifferentialGpu.RG11B10_FLOAT) {
					tolerance = packedFloatStep(c, Math.max(Math.abs((double) a), Math.abs((double) b)));
				} else {
					double step = codeValue(format, c);
					tolerance = step > 0 ? step + 1e-9
						: FLOAT_REL * Math.max(Math.abs(a), Math.abs(b)) + FLOAT_FLOOR;
				}
				pass &= diff <= tolerance;
			}
			if (pass) {
				ok++;
			}
		}
		return new DifferentialDiff(format, pixels, ok, max, nonFinite);
	}
}
