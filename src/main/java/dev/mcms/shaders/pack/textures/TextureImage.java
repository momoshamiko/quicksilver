// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack.textures;

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;
import javax.imageio.ImageIO;

/**
 * One custom texture's pixels, ready to upload: a raw MTLPixelFormat, the Metal texture type, the size, the bytes one texel
 * takes in that format, and the texels (row-major; z-major slices for a volume). The record holds the array it is given:
 * the caller hands over its bytes and must not change them afterwards.
 *
 * @param format        the MTLPixelFormat (the runtime's TexelCodec names them)
 * @param type          the MTLTextureType: {@link #TYPE_2D} or {@link #TYPE_3D}
 * @param bytesPerTexel bytes one texel of {@code format} takes
 */
public record TextureImage(int format, int type, int width, int height, int depth, int bytesPerTexel, byte[] bytes) {

	/** MTLPixelFormatRGBA8Unorm. */
	public static final int RGBA8 = 70;
	/** MTLTextureType2D. */
	public static final int TYPE_2D = 2;
	/** MTLTextureType3D. */
	public static final int TYPE_3D = 7;

	/**
	 * The documented default of {@code normals} with no PBR pack (the shaders.properties "Texture Atlases" page, as
	 * engine-pack-format.md section 10 records it): RGBA (127, 127, 255, 255). The engine's default normal texture and
	 * {@link #defaultNormal()} both take it from here. Read it, never write it.
	 */
	public static final int[] DEFAULT_NORMAL = {127, 127, 255, 255};

	/** The documented default of {@code specular} with no PBR pack: RGBA (0, 0, 0, 0), zero specular. */
	public static final int[] DEFAULT_SPECULAR = {0, 0, 0, 0};

	/** Bytes per row of the texels. */
	public long bytesPerRow() {
		return (long) this.width * this.bytesPerTexel;
	}

	/** Bytes per slice of the texels. */
	public long bytesPerImage() {
		return bytesPerRow() * this.height;
	}

	/** A 1x1 RGBA8 texel of the given channel values. */
	public static TextureImage texel(int r, int g, int b, int a) {
		return new TextureImage(RGBA8, TYPE_2D, 1, 1, 1, 4, new byte[] {(byte) r, (byte) g, (byte) b, (byte) a});
	}

	/**
	 * The default normal map: one texel of {@link #DEFAULT_NORMAL}. A 1x1 texture serves every atlas coordinate with the same
	 * texel, which is the default for the normal atlases of packs without PBR resources.
	 */
	public static TextureImage defaultNormal() {
		return texel(DEFAULT_NORMAL[0], DEFAULT_NORMAL[1], DEFAULT_NORMAL[2], DEFAULT_NORMAL[3]);
	}

	/** The default specular map: one texel of {@link #DEFAULT_SPECULAR} (no specular reflection). */
	public static TextureImage defaultSpecular() {
		return texel(DEFAULT_SPECULAR[0], DEFAULT_SPECULAR[1], DEFAULT_SPECULAR[2], DEFAULT_SPECULAR[3]);
	}

	/**
	 * Decodes a PNG (or any file ImageIO reads) into RGBA8, row-major, top row first. Empty when the bytes are not an image.
	 *
	 * <p>A grey image (one or two bands, with or without alpha, 1 to 16 bits) is read from its raster: Java's grey colour
	 * space is linear, so {@code getRGB} would convert the stored values to sRGB, which the file never asked for. Its samples
	 * scale to eight bits by the sample maximum of the colour model. Palette and colour images take their sRGB values from
	 * {@code getRGB}, one call for the whole image.
	 */
	public static Optional<TextureImage> decodeImage(byte[] file) {
		try {
			BufferedImage image = ImageIO.read(new ByteArrayInputStream(file));
			if (image == null) {
				return Optional.empty();
			}
			int w = image.getWidth(), h = image.getHeight();
			if (w <= 0 || h <= 0 || (long) w * h > 16384L * 16384L) {
				return Optional.empty();
			}
			byte[] out = new byte[w * h * 4];
			ColorModel cm = image.getColorModel();
			if (cm instanceof IndexColorModel || cm.getColorSpace().getType() != ColorSpace.TYPE_GRAY) {
				int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
				for (int i = 0, at = 0; i < argb.length; i++) {
					int v = argb[i];
					out[at++] = (byte) (v >>> 16);
					out[at++] = (byte) (v >>> 8);
					out[at++] = (byte) v;
					out[at++] = (byte) (v >>> 24);
				}
			} else {
				greyTexels(image, out);
			}
			return Optional.of(new TextureImage(RGBA8, TYPE_2D, w, h, 1, 4, out));
		} catch (IOException | RuntimeException e) {
			return Optional.empty();
		}
	}

	/** Writes a grey image's texels: the grey band to R, G and B, the alpha band (or full opacity) to A, scaled to 8 bits. */
	private static void greyTexels(BufferedImage image, byte[] out) {
		ColorModel cm = image.getColorModel();
		int w = image.getWidth(), h = image.getHeight();
		int bands = image.getRaster().getNumBands();
		boolean alpha = cm.hasAlpha();
		int grey = cm.getComponentSize(0), alphaBits = alpha ? cm.getComponentSize(bands - 1) : 0;
		int[] samples = image.getRaster().getPixels(0, 0, w, h, (int[]) null);
		long greyMax = (1L << grey) - 1, alphaMax = (1L << alphaBits) - 1;
		for (int p = 0, at = 0; p < w * h; p++, at += 4) {
			int g = scale(samples[p * bands], greyMax);
			out[at] = (byte) g;
			out[at + 1] = (byte) g;
			out[at + 2] = (byte) g;
			out[at + 3] = (byte) (alpha ? scale(samples[p * bands + bands - 1], alphaMax) : 255);
		}
	}

	/** A sample of {@code max} (2^bits - 1) scaled to 0..255, rounded. */
	private static int scale(int sample, long max) {
		return (int) ((sample * 255L + max / 2) / max);
	}
}
