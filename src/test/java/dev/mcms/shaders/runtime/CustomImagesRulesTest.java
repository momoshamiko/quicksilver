// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.shaders.pack.buffers.StorageBufferTable;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The rules of the custom images that need no device: the texture kind of a format, the size a definition gives at a
 * render size (fractional factors, at least one pixel), and the clear kernel each kind uses (plan.md E6, finding 11); and
 * the refusal of a storage block with no bufferObject (finding 7).
 */
final class CustomImagesRulesTest {

	@Test
	void theKindOfAnInternalFormatIsFloatSignedOrUnsigned() {
		assertEquals(0, CustomImages.kindOf("rgba16f"));
		assertEquals(0, CustomImages.kindOf("r32f"));
		assertEquals(1, CustomImages.kindOf("rgba32i"));
		assertEquals(2, CustomImages.kindOf("r8ui"));
		assertEquals(2, CustomImages.kindOf("RG32UI"));
	}

	@Test
	void theClearKernelSlotSeparates2DAndVolumeKinds() {
		assertEquals(0, CustomImages.kernelSlot(0));
		assertEquals(1, CustomImages.kernelSlot(1));
		assertEquals(2, CustomImages.kernelSlot(2));
		assertEquals(3, CustomImages.kernelSlot(256));      // a 3D float image (the volume bit is 1 << 8)
		assertEquals(5, CustomImages.kernelSlot(256 | 2));  // a 3D unsigned image
	}

	@Test
	void aRelativeSizeMultipliesByAFractionAndKeepsAtLeastOnePixel() {
		assertEquals(540, CustomImages.size(true, 1080, 0.5));
		assertEquals(1920, CustomImages.size(true, 1920, 1.0));
		assertEquals(1, CustomImages.size(true, 3, 0.1));
		assertEquals(64, CustomImages.size(false, 1080, 64));
	}

	@Test
	void aStorageBlockWithNoBufferObjectIsRefusedAndAnUnboundBlockIsNot() {
		StorageBufferTable none = StorageBufferTable.empty();
		// The Metal slot (3) and the bufferObject index (1) differ: the rule must look the pack's bufferObject up, not the slot.
		String refusal = DimensionSet.storageRefusal(List.of(
			new ProgramTranslation.Reflection.ResourceUse("scan", 3, "scanStruct", "read_write", true, 1)), none);
		assertEquals("storage block scan binds 1 and the pack declares no bufferObject.1", refusal);
		assertNull(DimensionSet.storageRefusal(List.of(
			new ProgramTranslation.Reflection.ResourceUse("frame", 24, "QsFrame", "read", false, -1)), none));
	}
}
