// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.runtime.gbuffers.DrawLog;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Picture dumps of one frame (2026-11-14, E4 debugging: where the terrain leaves colortex0). Only the first frame of each
 * draw-log block is dumped ({@link DrawLog#dumping()}). Each point writes PNG files into
 * {@code <game>/logs/mcms-dumps/<scene>/}, named {@code <point>-<texture>-0x<pointer>.png}, and a {@code DUMP} line into the
 * draw log.
 *
 * <p>Colour textures of every colour format ({@link DumpTexels}) become 8-bit RGBA. Each channel is clamped to 0..1 (the
 * float and normalised formats are not tone-mapped). A pixel with a NaN channel is painted pure magenta, and a pixel with an
 * infinite channel (and no NaN) pure cyan. The DUMP line counts both as pixels: {@code nan=N inf=M}. The depth texture is
 * 16-bit grayscale: value = depth x 65535, depth 0..1 (the engine's convention: cleared to 1.0).
 *
 * <p>The readback ({@link MetalBridge#pictureReadback}) commits the frame's command buffer in the middle of the frame and
 * waits for the GPU; an open render pass is reopened on its attachments. Diagnostic only.
 */
public final class DrawDumps {

	private static final Logger LOG = LoggerFactory.getLogger("Quicksilver");
	/** Opaque pure magenta (NaN) and pure cyan (Inf), as ARGB. */
	private static final int MAGENTA = 0xffff00ff, CYAN = 0xff00ffff;

	private DrawDumps() {
	}

	/** A written dump: its file, its NaN and Inf pixel counts (0 for depth), and the DUMP line that reports it. */
	record Dumped(Path file, int nan, int inf, String line) {
	}

	/** A colour texture as an image, with its NaN and Inf pixel counts. */
	record Rendered(BufferedImage image, int nan, int inf) {
	}

	/**
	 * One dump point: colortex0's read and write textures and the engine's depth 0, plus the read and write textures of the
	 * targets {@value #TARGETS_PROPERTY} lists ({@code 4+6}), and one {@code TEXEL} line per texel {@value #TEXELS_PROPERTY}
	 * lists ({@code 4:10:37+6:720:3}: target, then x and y as {@code texelFetch} counts them, from the bottom), each with
	 * its four channels exactly in both textures (2026-11-16, E7: Bliss's exposure texels). Both lists hold no comma or
	 * semicolon: the bench writes them as JVM arguments into Prism's INI file, where either one cuts the value.
	 * {@code encoder} is the backend encoder whose open pass a readback restores (its pipeline and state, on the reopened
	 * encoder): null when no backend pass is open, or when the open encoder is the engine's own pass (a deferred, composite or
	 * final pass; the game's pass is suspended then, and its state on the engine's attachments fails Metal's validation).
	 * {@code enc} the native encoder.
	 */
	public static void point(@Nullable Object encoder, long enc, Targets targets, String point) {
		picture(encoder, enc, targets.read(0), point + "-colortex0-read", false);
		picture(encoder, enc, targets.write(0), point + "-colortex0-write", false);
		picture(encoder, enc, targets.depth(0), point + "-depth0", true);
		for (String t : System.getProperty(TARGETS_PROPERTY, "").split("\\+")) {
			int target = parse(t);
			if (target > 0 && target < Targets.COLOR_TARGETS) {
				picture(encoder, enc, targets.read(target), point + "-colortex" + target + "-read", false);
				picture(encoder, enc, targets.write(target), point + "-colortex" + target + "-write", false);
			}
		}
		for (String spec : System.getProperty(TEXELS_PROPERTY, "").split("\\+")) {
			String[] parts = spec.split(":");
			if (parts.length == 3) {
				int target = parse(parts[0]), x = parse(parts[1]), y = parse(parts[2]);
				if (target >= 0 && target < Targets.COLOR_TARGETS && x >= 0 && y >= 0) {
					texel(encoder, enc, targets.read(target), point + " colortex" + target + "-read", x, y);
					texel(encoder, enc, targets.write(target), point + " colortex" + target + "-write", x, y);
				}
			}
		}
	}

	/** The system property naming the extra colour targets each dump point writes ({@code 4+6}). */
	public static final String TARGETS_PROPERTY = "mcms.shaders.dumpTargets";
	/** The system property naming the texels each dump point logs exactly ({@code 4:10:37+6:720:3}). */
	public static final String TEXELS_PROPERTY = "mcms.shaders.dumpTexels";

	private static int parse(String s) {
		try {
			return Integer.parseInt(s.strip());
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/**
	 * One texel of a colour texture, exactly: a {@code TEXEL} line with its four decoded channels. Storage row y is the row
	 * {@code texelFetch} names y (the engine renders with the Y flip, plan.md E3), so x and y are the pack's coordinates.
	 */
	static void texel(@Nullable Object encoder, long enc, long texture, String name, int x, int y) {
		if (texture == 0) {
			return;
		}
		int width = MetalBridge.textureInfo(texture, 0);
		int height = MetalBridge.textureInfo(texture, 1);
		int format = MetalBridge.textureInfo(texture, 2);
		int bytesPerPixel = DumpTexels.bytesPerTexel(format);
		if (x >= width || y >= height || bytesPerPixel == 0) {
			DrawLog.emit("TEXEL f=" + DrawLog.frameNumber() + " " + name + " (" + x + "," + y + ") skipped: " + width + "x" + height
				+ " format " + format);
			return;
		}
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment texels = arena.allocate((long) width * height * bytesPerPixel);
			if (MetalBridge.pictureReadback(encoder, enc, texture, width, height, bytesPerPixel, false, texels.address()) < 0) {
				DrawLog.emit("TEXEL f=" + DrawLog.frameNumber() + " " + name + " failed: the GPU did not finish the readback");
				return;
			}
			ByteBuffer bytes = texels.asByteBuffer().order(ByteOrder.nativeOrder());
			float[] rgba = new float[4];
			DumpTexels.decode(bytes, (y * width + x) * bytesPerPixel, format, rgba);
			DrawLog.emit("TEXEL f=" + DrawLog.frameNumber() + " " + name + " 0x" + Long.toHexString(texture) + " (" + x + "," + y
				+ ") = " + rgba[0] + ", " + rgba[1] + ", " + rgba[2] + ", " + rgba[3]);
		}
	}

	/** One texture as a PNG in the block's folder (a texture of 0 is skipped). */
	public static void picture(@Nullable Object encoder, long enc, long texture, String name, boolean depth) {
		if (texture == 0) {
			return;
		}
		try {
			Dumped dumped = dumpTo(DrawLog.dumpDirectory(), encoder, enc, texture, name, depth);
			if (dumped != null) {
				DrawLog.emit(dumped.line());
			}
		} catch (IOException | RuntimeException e) {
			LOG.error("[mcms-shaders] draw dump {} failed: {}", name, e.toString());
		}
	}

	/**
	 * Reads one texture back, renders it and writes its PNG into {@code dir}. Returns what was written. Returns null when the
	 * texture is skipped (no size, or no colour layout) or the readback failed; the DUMP line that says so is emitted here.
	 */
	static @Nullable Dumped dumpTo(Path dir, @Nullable Object encoder, long enc, long texture, String name, boolean depth)
			throws IOException {
		int width = MetalBridge.textureInfo(texture, 0);
		int height = MetalBridge.textureInfo(texture, 1);
		int format = MetalBridge.textureInfo(texture, 2);
		int bytesPerPixel = depth ? 4 : DumpTexels.bytesPerTexel(format);
		String id = name + " 0x" + Long.toHexString(texture);
		if (width <= 0 || height <= 0 || bytesPerPixel == 0) {
			DrawLog.emit("DUMP " + id + " skipped: format " + format);
			return null;
		}
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment texels = arena.allocate((long) width * height * bytesPerPixel);
			if (MetalBridge.pictureReadback(encoder, enc, texture, width, height, bytesPerPixel, depth, texels.address()) < 0) {
				DrawLog.emit("DUMP " + id + " failed: the GPU did not finish the readback");
				return null;
			}
			ByteBuffer bytes = texels.asByteBuffer().order(ByteOrder.nativeOrder());
			BufferedImage image;
			int nan = 0, inf = 0;
			String kind;
			if (depth) {
				image = gray(bytes, width, height);
				kind = "16-bit gray, value = depth x 65535 (0..1)";
			} else {
				Rendered rendered = colour(bytes, width, height, format);
				image = rendered.image();
				nan = rendered.nan();
				inf = rendered.inf();
				kind = "8-bit RGBA, channels clamped 0..1 (NaN pixels magenta, Inf pixels cyan), nan=" + nan + " inf=" + inf
					+ " pixels";
			}
			Path file = dir.resolve(name + "-0x" + Long.toHexString(texture) + ".png");
			ImageIO.write(image, "png", file.toFile());
			String line = "DUMP f=" + DrawLog.frameNumber() + " " + id + " " + width + "x" + height + " format=" + format + " "
				+ kind + " file=" + file;
			return new Dumped(file, nan, inf, line);
		}
	}

	/**
	 * A colour texture as an ARGB image. Each channel is clamped to 0..1; a pixel with a NaN channel is magenta, and a pixel
	 * with an infinite channel (and no NaN) is cyan. The NaN and Inf counts are pixel counts. Allocates one row-major array
	 * for the image and nothing per pixel.
	 */
	static Rendered colour(ByteBuffer bytes, int width, int height, int format) {
		int size = DumpTexels.bytesPerTexel(format);
		float[] rgba = new float[4];
		int[] argb = new int[width * height];
		int nan = 0, inf = 0;
		for (int i = 0; i < argb.length; i++) {
			DumpTexels.decode(bytes, i * size, format, rgba);
			boolean hasNan = false, hasInf = false;
			for (float v : rgba) {
				hasNan |= Float.isNaN(v);
				hasInf |= Float.isInfinite(v);
			}
			if (hasNan) {
				argb[i] = MAGENTA;
				nan++;
			} else if (hasInf) {
				argb[i] = CYAN;
				inf++;
			} else {
				argb[i] = (to8(rgba[3]) << 24) | (to8(rgba[0]) << 16) | (to8(rgba[1]) << 8) | to8(rgba[2]);
			}
		}
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, width, height, argb, 0, width);
		return new Rendered(image, nan, inf);
	}

	/** The depth texture as a 16-bit gray image: value = depth x 65535 (depth clamped to 0..1). */
	private static BufferedImage gray(ByteBuffer bytes, int width, int height) {
		BufferedImage gray = new BufferedImage(width, height, BufferedImage.TYPE_USHORT_GRAY);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				float value = bytes.getFloat(4 * (y * width + x));
				gray.getRaster().setSample(x, y, 0, Math.round(clamp01(value) * 65535f));
			}
		}
		return gray;
	}

	private static int to8(float value) {
		return Math.round(clamp01(value) * 255f);
	}

	private static float clamp01(float value) {
		return Float.isNaN(value) ? 0f : Math.max(0f, Math.min(1f, value));
	}
}
