// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.pack.PropertiesText;
import dev.mcms.shaders.pack.ShaderProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Custom images across a resize on the device (E6, finding 5b): a fixed-size image keeps its texture and its contents, a
 * relative one is rebuilt at the new size, releasing the old object after a resize frees what the new one does not own (a
 * second release is a no-op), and a texture the device refuses is absent while the other images are built.
 */
@Tag("gpu")
final class CustomImagesResizeGpuTest {

	private static final String PROPERTIES = """
		image.fixed=fixed_s RGBA RGBA8 UNSIGNED_BYTE false false 8 8
		image.half=half_s RGBA RGBA8 UNSIGNED_BYTE false true 0.5 0.5
		image.quarter=quarter_s RGBA RGBA8 UNSIGNED_BYTE false true 0.25 0.25
		""";

	/** The refusal case: a fixed image (kept), a float clear image, a relative image the device refuses, an integer clear image. */
	private static final String REFUSAL_PROPERTIES = """
		image.fixed=fixed_s RGBA RGBA8 UNSIGNED_BYTE false false 8 8
		image.clearFloat=clear_f RGBA RGBA8 UNSIGNED_BYTE true true 1 1
		image.half=half_s RGBA RGBA8 UNSIGNED_BYTE false true 1 1
		image.clearInt=clear_i RGBA_INTEGER RGBA32I INT true true 1 1
		""";

	private static final int VIEW = 64, FIXED = 8, MARK = 0x5A;

	private static List<ShaderProperties.ImageBinding> definitions() {
		return ShaderProperties.from(PropertiesText.parse(PROPERTIES)).images();
	}

	/** The refusal case's images: a fixed image, a float clear image, a relative image, and an integer clear image. */
	private static List<ShaderProperties.ImageBinding> refusalDefinitions() {
		return ShaderProperties.from(PropertiesText.parse(REFUSAL_PROPERTIES)).images();
	}

	/** Writes the marker into every texel of a texture (a private texture is written through a texture upload). */
	private static void fill(long ctx, long texture, int width, int height) {
		byte[] bytes = new byte[width * height * 4];
		Arrays.fill(bytes, (byte) MARK);
		assertEquals(true, ShaderNative.textureUpload(ctx, texture, bytes, width * 4, width * height * 4, 0, 0, 0, 0, 0,
			width, height, 1), "the marker upload");
	}

	/** Every byte of a texture read back equals the marker. */
	private static void assertFilled(HeadlessGpu gpu, long texture, int width, int height) {
		byte[] bytes = gpu.readTexture(texture, 0, 0, width, height, 1, 4);
		byte[] want = new byte[width * height * 4];
		Arrays.fill(want, (byte) MARK);
		assertArrayEquals(want, bytes, "the texture of " + width + "x" + height + " keeps the marker");
	}

	@Test
	void aFixedImageKeepsItsTextureAndContentsAndARelativeOneIsRebuiltAtTheNewSize() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			CustomImages before = CustomImages.create(ctx, definitions(), VIEW, VIEW);
			int fixed = before.indexOfImage("fixed"), half = before.indexOfImage("half");
			fill(ctx, before.texture(fixed), FIXED, FIXED);
			long fixedTexture = before.texture(fixed), halfTexture = before.texture(half);

			CustomImages after = before.resize(ctx, VIEW * 2, VIEW * 2);
			assertEquals(fixedTexture, after.texture(fixed), "a fixed image keeps its texture");
			assertFilled(gpu, after.texture(fixed), FIXED, FIXED);
			assertNotEquals(halfTexture, after.texture(half), "a relative image is a new texture");
			assertEquals(halfTexture, before.texture(half), "the old object still owns its relative texture until it is released");
			byte[] halfBytes = gpu.readTexture(after.texture(half), 0, 0, VIEW, VIEW, 1, 4);
			assertEquals(VIEW * VIEW * 4, halfBytes.length, "the relative image is half of the 128-pixel view: 64 by 64");

			before.release();  // frees what the old object still owns: its relative texture and its kernels, not the fixed one
			before.release();  // a second release is a no-op
			assertFilled(gpu, after.texture(fixed), FIXED, FIXED);
			after.release();
		}
	}

	/**
	 * A texture the device refuses is absent, and the build goes on (Metal returns 0 for a texture it cannot make and does not
	 * throw; a resize that refused would be a failed build, which is not what Metal does). The fixed image keeps its texture
	 * and contents, the other images are built, and the kernels the previous object already has are kept, not compiled again.
	 */
	@Test
	void aTextureTheDeviceRefusesIsAbsentAndTheOtherImagesAreBuilt() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			CustomImages before = CustomImages.create(ctx, refusalDefinitions(), VIEW, VIEW);
			int fixed = before.indexOfImage("fixed"), half = before.indexOfImage("half");
			fill(ctx, before.texture(fixed), FIXED, FIXED);
			long fixedTexture = before.texture(fixed);
			List<String> calls = new ArrayList<>();
			CustomImages.Device refusesSecond = new CustomImages.Device() {
				private int textures;

				@Override
				public long texture(long ctx, int format, int type, int width, int height, int depth) {
					if (++this.textures == 2) {
						calls.add("texture:refused");
						return 0L;  // Metal's refusal: 0, no throw
					}
					calls.add("texture");
					return CustomImages.METAL.texture(ctx, format, type, width, height, depth);
				}

				@Override
				public long clearPipeline(long ctx, int kind) {
					calls.add("kernel");
					return CustomImages.METAL.clearPipeline(ctx, kind);
				}
			};
			CustomImages after = before.resizeOn(refusesSecond, ctx, VIEW * 2, VIEW * 2);
			assertEquals(List.of("texture", "texture:refused", "texture"), calls,
				"the build asks for the three relative textures in order and compiles no kernel");
			assertEquals(0L, after.texture(half), "the image the device refused is absent");
			assertNotEquals(0L, after.texture(after.indexOfImage("clearFloat")), "a granted clear image is built");
			assertNotEquals(0L, after.texture(after.indexOfImage("clearInt")), "a granted clear image is built");
			assertEquals(fixedTexture, after.texture(fixed), "a fixed image keeps its texture");
			assertFilled(gpu, after.texture(fixed), FIXED, FIXED);
			after.release();
			before.release();
		}
	}
}
