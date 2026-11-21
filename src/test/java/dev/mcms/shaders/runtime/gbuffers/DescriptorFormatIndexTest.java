// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mcms.shaders.runtime.TargetFormats;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The pixel format a pipeline descriptor gives an attachment (E5 carry-over). The legacy {@code colortex1} rule upgrades
 * RGBA to RGBA32F only for the colour targets; a shadow target never takes it, because {@code ShadowTargets} builds its
 * textures with index -1. Before the fix the shadow descriptor passed the target index, so {@code shadowcolor1 = RGBA}
 * gave RGBA32F in the pipeline and RGBA8 in the texture.
 */
final class DescriptorFormatIndexTest {

	/** MTLPixelFormatRGBA32Float, the upgrade the colour target 1 takes (TargetFormats.of). */
	private static final int RGBA32F = 125;
	/** MTLPixelFormatRGBA8Unorm, the format of RGBA in every other place. */
	private static final int RGBA8 = 70;

	@Test
	void shadowcolor1RgbaDescribesRgba8NotTheColourTargetUpgrade() {
		Map<Integer, String> shadowFormats = Map.of(1, "RGBA");
		assertEquals(RGBA8, GbufferPlans.descriptorFormat(shadowFormats, 1, true));
	}

	@Test
	void colortex1RgbaStillDescribesRgba32F() {
		Map<Integer, String> colorFormats = Map.of(1, "RGBA");
		assertEquals(RGBA32F, GbufferPlans.descriptorFormat(colorFormats, 1, false));
	}

	@Test
	void unnamedShadowTargetDescribesRgba8() {
		assertEquals(RGBA8, GbufferPlans.descriptorFormat(Map.of(), 1, true));
	}

	/**
	 * Each shadow attachment's descriptor word equals the format of the texture the shadow pass binds at that index: the
	 * texture comes from {@code TargetFormats.of(word, -1)} (ShadowTargets). A call site that passes the colour index
	 * instead gives a different word for every index that takes the colortex1 upgrade.
	 */
	@Test
	void everyShadowAttachmentDescribesTheFormatOfItsTexture() {
		for (int index = 0; index < 8; index++) {
			Map<Integer, String> shadowFormats = Map.of(index, "RGBA");
			assertEquals(TargetFormats.of("RGBA", -1).format(), GbufferPlans.descriptorFormat(shadowFormats, index, true),
				"shadowcolor" + index);
		}
	}
}
