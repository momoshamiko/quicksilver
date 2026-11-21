// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two frame rules on the headless device (plan.md E3): {@code scale.<program>} draws the sub-rectangle its scale and
 * offsets name (the buffer keeps its size; the viewport changes), and a pack without a final program, or with a final
 * that failed, shows colortex0 on the main colour (the engine copies it there; engine-pack-format.md section 7).
 */
@Tag("gpu")
class EngineScaleAndFinalGpuTest {

	private static final int W = 64, H = 64;
	private static final int RGBA8 = 70;
	private static final int RT_READ = ShaderNative.USAGE_SHADER_READ | ShaderNative.USAGE_RENDER_TARGET;
	private static final String VSH = """
		#version 130
		varying vec2 texcoord;
		void main() {
			texcoord = gl_MultiTexCoord0.xy;
			gl_Position = ftransform();
		}
		""";
	/** The composite writes green into colortex0 (its clear is the documented fog colour, black). */
	private static final String COMPOSITE_GREEN = """
		#version 130
		/* DRAWBUFFERS:0 */
		void main() {
			gl_FragColor = vec4(0.0, 1.0, 0.0, 1.0);
		}
		""";
	/** The composite writes blue into colortex0, so a red main colour shows the copy. */
	private static final String COMPOSITE_BLUE = COMPOSITE_GREEN.replace("vec4(0.0, 1.0, 0.0, 1.0)",
		"vec4(0.0, 0.0, 1.0, 1.0)");
	private static final String FINAL_COPY = """
		#version 130
		uniform sampler2D colortex0;
		varying vec2 texcoord;
		void main() {
			gl_FragColor = texture2D(colortex0, texcoord);
		}
		""";

	/** A pack folder: the composite program, the final program when given, and shaders.properties when given. */
	private static PackModel writePack(Path dir, String composite, String finalSource, String properties) throws Exception {
		Path shaders = Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(shaders.resolve("composite.vsh"), VSH);
		Files.writeString(shaders.resolve("composite.fsh"), composite);
		if (finalSource != null) {
			Files.writeString(shaders.resolve("final.vsh"), VSH);
			Files.writeString(shaders.resolve("final.fsh"), finalSource);
		}
		if (properties != null) {
			Files.writeString(shaders.resolve("shaders.properties"), properties);
		}
		try (PackSource source = PackSource.open(dir)) {
			return PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
	}

	/** Runs one frame of a pack's set on the device; returns the main colour's bytes (memory row order). */
	private static byte[] runFrame(HeadlessGpu gpu, PackModel model, byte[] red, Map<Integer, Integer> sizeOut,
			int absentPrograms) throws Exception {
		return runFrame(gpu, model, red, sizeOut, absentPrograms, null);
	}

	/**
	 * As above; {@code standInClear} non-null makes the main colour the render-size stand-in (ADR 0008) with that clear colour,
	 * the way {@code Engine.afterLevel} sets it at a render scale above 1.
	 */
	private static byte[] runFrame(HeadlessGpu gpu, PackModel model, byte[] red, Map<Integer, Integer> sizeOut,
			int absentPrograms, float[] standInClear) throws Exception {
		long ctx = gpu.context();
		long enc = gpu.encoder();
		List<Long> toRelease = new java.util.ArrayList<>();
		try {
			DimensionSet set = DimensionSet.load(ctx, "scale-final", model, model.rootFolder(),
				CustomUniforms.of(model), RGBA8, model.rootFolder().union().colorFormats(), Engine.gamePipelines(),
				Engine.COPIES_NOT_COMPILED);
			assertEquals(absentPrograms, set.absent(), "absent programs (the test's final is or is not present): " + set.notes());
			Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(), set.usedTargets(),
				false, false, W, H, Targets.engineTextures(ctx), null);
			assertTrue(targets.create());
			sizeOut.put(0, targets.width(0));
			sizeOut.put(1, targets.height(0));
			long noise = Engine.makeNoise(ctx, set.noiseResolution());
			long copy = Engine.copyPipeline(ctx, targets.format(0));
			long finalCopy = Engine.copyPipeline(ctx, RGBA8);
			long main = ShaderNative.textureNew(ctx, RGBA8, ShaderNative.TYPE_2D, W, H, 1, 1, RT_READ,
				ShaderNative.STORAGE_PRIVATE);
			toRelease.add(main);
			assertTrue(ShaderNative.textureUpload(ctx, main, red, (long) W * 4, 0, 0, 0, 0, 0, 0, W, H, 1));
			long block = MetalBridge.newBuffer(ctx, 256);
			toRelease.add(block);
			EngineFrame.PassBlocks blocks = new EngineFrame.PassBlocks() {
				@Override
				public long[] frameBlock(String program) {
					return null;
				}

				@Override
				public long[] customBlock() {
					return null;
				}
			};
			EngineFrame frame = new EngineFrame(set, targets, null, noise, copy, finalCopy, (bytes, out) -> {
				// The E3 crash rule: every block the frame uploads is a direct buffer (its native address is copied).
				assertTrue(bytes.isDirect(), "the frame uploads only direct buffers");
				out[0] = block;
				out[1] = 0;
			}, model.properties().flips(), model.properties().scales(), new EngineFrame.Gbuffers(new int[0],
				new int[0], new int[0], false, phase -> {}, null));
			EngineFrame.Main mainColour = new EngineFrame.Main();
			mainColour.set(main, RGBA8, W, H, null);
			if (standInClear != null) {
				mainColour.standInClear(standInClear);
			}
			frame.clearHook(enc, null, targets, blocks);
			frame.compositeHook(enc, null, targets, mainColour, blocks);
			MetalBridge.commitAndWait(enc);
			byte[] picture = gpu.readTexture(main, 0, 0, W, H, 1, 4);
			frame.close();
			set.passes().forEach(pass -> toRelease.add(pass.pipeline()));
			targets.release();
			toRelease.add(noise);
			toRelease.add(copy);
			toRelease.add(finalCopy);
			return picture;
		} finally {
			toRelease.forEach(handle -> {
				if (handle != null && handle != 0) {
					MetalBridge.release(handle);
				}
			});
		}
	}

	private static byte[] redPicture() {
		byte[] texels = new byte[W * H * 4];
		for (int at = 0; at < texels.length; at += 4) {
			texels[at] = (byte) 0xff;
			texels[at + 3] = (byte) 0xff;
		}
		return texels;
	}

	@Test
	void aScaledCompositeDrawsItsViewportOnly(@TempDir Path dir) throws Exception {
		PackModel model = writePack(dir, COMPOSITE_GREEN, FINAL_COPY, "scale.composite=0.5 0.5 0.5\n");
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			Map<Integer, Integer> size = new java.util.HashMap<>();
			byte[] picture = runFrame(gpu, model, redPicture(), size, 0);
			assertEquals(W, size.get(0), "scale.<program> leaves colortex0's width alone");
			assertEquals(H, size.get(1), "scale.<program> leaves colortex0's height alone");
			// The viewport is the quarter the scale names: half the width and height, at the half offset (memory rows
			// H/2 to H-1, the rows the engine's targets hold for the window's top half; the test pins the same rule).
			int green = 0;
			for (int y = 0; y < H; y++) {
				for (int x = 0; x < W; x++) {
					int at = (y * W + x) * 4;
					boolean inQuarter = x >= W / 2 && y >= H / 2;
					boolean isGreen = (picture[at] & 0xff) == 0 && (picture[at + 1] & 0xff) == 255
						&& (picture[at + 2] & 0xff) == 0;
					boolean isBlack = (picture[at] & 0xff) == 0 && (picture[at + 1] & 0xff) == 0
						&& (picture[at + 2] & 0xff) == 0;
					if (isGreen) {
						green++;
					}
					assertTrue(inQuarter ? isGreen : isBlack,
						"pixel " + x + "," + y + " should be " + (inQuarter ? "green" : "black") + " (scaled viewport)");
				}
			}
			assertEquals(W * H / 4, green, "the green area is the scaled quarter");
		}
	}

	@Test
	void aPackWithoutFinalShowsColortex0(@TempDir Path dir) throws Exception {
		PackModel model = writePack(dir, COMPOSITE_BLUE, null, null);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			byte[] picture = runFrame(gpu, model, redPicture(), new java.util.HashMap<>(), 0);
			assertAllBlue(picture, "a pack without a final shows colortex0 on the main colour");
		}
	}

	@Test
	void aFailedFinalShowsColortex0(@TempDir Path dir) throws Exception {
		// A final that does not parse: the program is absent (no pipeline), so the frame copies colortex0 instead.
		PackModel model = writePack(dir, COMPOSITE_BLUE, "#version 130\nthis is not glsl at all {\n", null);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			byte[] picture = runFrame(gpu, model, redPicture(), new java.util.HashMap<>(), 1);
			assertAllBlue(picture, "a failed final leaves colortex0 on the main colour");
		}
	}

	/**
	 * ADR 0008 (full-branch review of E9): at a render scale the final pass draws into the render-size stand-in, which still
	 * holds the frame before (red here). A final that leaves texels unwritten ({@code scale.final}) clears the stand-in to the
	 * game's clear colour (black) around its viewport, as the window-size path writes the game's clear first; before the fix
	 * the old red frame showed there.
	 */
	@Test
	void aScaledFinalOnTheStandInClearsItsSurroundToTheGameClearColour(@TempDir Path dir) throws Exception {
		PackModel model = writePack(dir, COMPOSITE_BLUE, FINAL_COPY, "scale.final=0.5 0.5 0.5\n");
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			byte[] picture = runFrame(gpu, model, redPicture(), new java.util.HashMap<>(), 0, new float[] {0f, 0f, 0f, 1f});
			int blue = 0;
			for (int y = 0; y < H; y++) {
				for (int x = 0; x < W; x++) {
					int at = (y * W + x) * 4;
					boolean inQuarter = x >= W / 2 && y >= H / 2;
					int r = picture[at] & 0xff, g = picture[at + 1] & 0xff, b = picture[at + 2] & 0xff;
					if (inQuarter) {
						assertTrue(r == 0 && g == 0 && b == 255, "pixel " + x + "," + y + " should be the final's blue");
						blue++;
					} else {
						assertTrue(r == 0 && g == 0 && b == 0,
							"pixel " + x + "," + y + " should be the game's clear colour, not the stand-in's old frame (" + r + ","
								+ g + "," + b + ")");
					}
				}
			}
			assertEquals(W * H / 4, blue, "the blue area is the final's scaled quarter");
		}
	}

	private static void assertAllBlue(byte[] picture, String what) {
		for (int y = 0; y < H; y++) {
			for (int x = 0; x < W; x++) {
				int at = (y * W + x) * 4;
				assertEquals(0, picture[at] & 0xff, what + " red at " + x + "," + y);
				assertEquals(0, picture[at + 1] & 0xff, what + " green at " + x + "," + y);
				assertEquals(255, picture[at + 2] & 0xff, what + " blue at " + x + "," + y);
			}
		}
	}
}
