// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The size fields of an {@code image.<name>} definition (shaders.properties, custom images): a relative size is a factor
 * that may be fractional, an absolute size is whole pixels (plan.md E6, finding 11).
 */
final class ImageSizeParseTest {

	@Test
	void aFractionalRelativeSizeParses() {
		ShaderProperties props = ShaderProperties.from(PropertiesText.parse(
			"image.half=colortex0 rgba16f rgba16f float true true 0.5 0.5\n"));
		assertTrue(props.problems().isEmpty(), props.problems().toString());
		assertEquals(1, props.images().size());
		assertEquals(0.5, props.images().get(0).x());
		assertEquals(0.5, props.images().get(0).y());
		assertTrue(props.images().get(0).relative());
	}

	@Test
	void aFractionalAbsoluteSizeIsRefused() {
		ShaderProperties props = ShaderProperties.from(PropertiesText.parse(
			"image.bad=sampler rgba8 rgba8 unsigned_byte false false 0.5 16\n"));
		assertTrue(props.images().isEmpty());
		assertFalse(props.problems().isEmpty(), "a fractional pixel count is a problem");
	}
}
