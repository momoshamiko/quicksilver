// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/**
 * The picture dumps' colour image (2026-11-14, E4 debugging: float stages were skipped or read as gray): pure magenta for a
 * NaN pixel, pure cyan for an infinite one, clamped channels otherwise, and the NaN and Inf pixel counts. Runs without a GPU.
 */
class DrawDumpsColourTest {

	@Test
	void nonFinitePixelsArePaintedAndCountedAndTheRestIsClamped() {
		// A 2x2 R32_FLOAT texture: NaN, +Inf, -Inf, and 2.0 (which clamps to full red).
		ByteBuffer bytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
		bytes.putFloat(Float.NaN).putFloat(Float.POSITIVE_INFINITY).putFloat(Float.NEGATIVE_INFINITY).putFloat(2f);
		DrawDumps.Rendered rendered = DrawDumps.colour(bytes, 2, 2, DumpTexels.R32_FLOAT);
		assertEquals(1, rendered.nan(), "NaN pixels");
		assertEquals(2, rendered.inf(), "Inf pixels");
		assertEquals(0xffff00ff, rendered.image().getRGB(0, 0), "NaN is pure magenta");
		assertEquals(0xff00ffff, rendered.image().getRGB(1, 0), "+Inf is pure cyan");
		assertEquals(0xff00ffff, rendered.image().getRGB(0, 1), "-Inf is pure cyan");
		assertEquals(0xffff0000, rendered.image().getRGB(1, 1), "2.0 clamps to 1.0 red");
	}

	@Test
	void aNanChannelWinsOverAnInfiniteOne() {
		// One RGBA16F texel with both NaN (red) and +Inf (green): magenta, counted once, as NaN.
		ByteBuffer bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		bytes.putShort(Float.floatToFloat16(Float.NaN)).putShort(Float.floatToFloat16(Float.POSITIVE_INFINITY));
		bytes.putShort(Float.floatToFloat16(0f)).putShort(Float.floatToFloat16(1f));
		DrawDumps.Rendered rendered = DrawDumps.colour(bytes, 1, 1, DumpTexels.RGBA16_FLOAT);
		assertEquals(1, rendered.nan());
		assertEquals(0, rendered.inf(), "a pixel counts once, as NaN");
		assertEquals(0xffff00ff, rendered.image().getRGB(0, 0));
	}
}
