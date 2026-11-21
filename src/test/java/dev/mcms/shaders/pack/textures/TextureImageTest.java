// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack.textures;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * PNG decoding into RGBA8 (E6-B, review item 5): a grey image's stored values are its texels (Java's grey colour space is
 * linear, so {@code getRGB} would move them), a 16-bit grey image scales to eight bits, a palette image takes its palette's
 * values, and a colour image keeps its channels.
 */
final class TextureImageTest {

	private static byte[] png(BufferedImage image) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		assertTrue(ImageIO.write(image, "png", out));
		return out.toByteArray();
	}

	private static TextureImage decode(BufferedImage image) throws Exception {
		Optional<TextureImage> decoded = TextureImage.decodeImage(png(image));
		assertTrue(decoded.isPresent(), "ImageIO reads the file back");
		return decoded.get();
	}

	@Test
	void notAnImageDecodesToNothing() {
		assertFalse(TextureImage.decodeImage(new byte[] {1, 2, 3}).isPresent());
	}

	@Test
	void aGreyImageKeepsItsStoredValues() throws Exception {
		BufferedImage grey = new BufferedImage(2, 1, BufferedImage.TYPE_BYTE_GRAY);
		grey.getRaster().setSample(0, 0, 0, 64);
		grey.getRaster().setSample(1, 0, 0, 128);
		TextureImage t = decode(grey);
		// getRGB would give 137 and 188 (the linear grey space converted to sRGB); the file's own values are the texels.
		assertArrayEquals(new byte[] {64, 64, 64, (byte) 255, (byte) 128, (byte) 128, (byte) 128, (byte) 255}, t.bytes());
	}

	@Test
	void aGreyImageWithAlphaTakesItsAlphaBand() throws Exception {
		ColorModel cm = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY), true, false, Transparency.TRANSLUCENT,
			DataBuffer.TYPE_BYTE);
		WritableRaster raster = cm.createCompatibleWritableRaster(1, 1);
		raster.setSample(0, 0, 0, 64);
		raster.setSample(0, 0, 1, 128);
		TextureImage t = decode(new BufferedImage(cm, raster, false, null));
		assertArrayEquals(new byte[] {64, 64, 64, (byte) 128}, t.bytes());
	}

	@Test
	void aSixteenBitGreyImageScalesToEightBits() throws Exception {
		BufferedImage grey16 = new BufferedImage(2, 1, BufferedImage.TYPE_USHORT_GRAY);
		grey16.getRaster().setSample(0, 0, 0, 0x4000);  // 16384 of 65535: 63.75 of 255, rounds to 64
		grey16.getRaster().setSample(1, 0, 0, 0xffff);
		TextureImage t = decode(grey16);
		assertArrayEquals(new byte[] {64, 64, 64, (byte) 255, (byte) 255, (byte) 255, (byte) 255, (byte) 255}, t.bytes());
	}

	@Test
	void aPaletteImageTakesThePaletteColours() throws Exception {
		IndexColorModel palette = new IndexColorModel(8, 2, new byte[] {0, (byte) 255}, new byte[] {0, (byte) 128},
			new byte[] {(byte) 255, 0});
		WritableRaster raster = palette.createCompatibleWritableRaster(2, 1);
		raster.setSample(0, 0, 0, 0);
		raster.setSample(1, 0, 0, 1);
		TextureImage t = decode(new BufferedImage(palette, raster, false, null));
		assertArrayEquals(new byte[] {0, 0, (byte) 255, (byte) 255, (byte) 255, (byte) 128, 0, (byte) 255}, t.bytes());
	}

	@Test
	void aColourImageKeepsItsChannels() throws Exception {
		BufferedImage colour = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		colour.setRGB(0, 0, 0x80102030);
		TextureImage t = decode(colour);
		assertEquals(TextureImage.RGBA8, t.format());
		assertArrayEquals(new byte[] {0x10, 0x20, 0x30, (byte) 0x80}, t.bytes());
	}

	@Test
	void theFlatNormalIsTheDocumentedDefault() {
		assertArrayEquals(new byte[] {127, 127, (byte) 255, (byte) 255}, TextureImage.defaultNormal().bytes());
	}
}
