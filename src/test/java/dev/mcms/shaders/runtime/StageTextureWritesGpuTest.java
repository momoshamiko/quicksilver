// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.textures.CustomTextureSet;
import dev.mcms.shaders.runtime.textures.FakeGameViews;
import java.awt.image.BufferedImage;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The frame side of two E7 rules, on the device. The pack binds a red image to colortex5 in the composite stage; composite,
 * scaled to the lower-left quarter, writes green to colortex5; composite1 copies what it reads of colortex5 to colortex0, and
 * final shows colortex0. After the write, composite1 must read the target (green in the quarter, the clear outside it), not
 * the image (Iris 1.11.7, mcms-texover), and the scaled pass must cover its whole quarter (a quad, as Iris draws; one
 * triangle would leave the quarter's upper-right half uncovered).
 */
@Tag("gpu")
final class StageTextureWritesGpuTest {

	private static final int W = 64, H = 64, RGBA8 = 70;
	private static final int RT_READ = ShaderNative.USAGE_SHADER_READ | ShaderNative.USAGE_RENDER_TARGET;

	private static final String PROPERTIES = """
		texture.composite.colortex5=tex/red.png
		scale.composite=0.5
		""";

	private static final String VERTEX = """
		#version 120
		void main() {
			gl_Position = ftransform();
		}
		""";

	private static final String WRITER = """
		#version 120
		/* DRAWBUFFERS:5 */
		void main() {
			gl_FragData[0] = vec4(0.0, 1.0, 0.0, 1.0);
		}
		""";

	private static final String READER = """
		#version 120
		uniform sampler2D colortex5;
		/* DRAWBUFFERS:0 */
		void main() {
			gl_FragData[0] = vec4(texture2D(colortex5, gl_FragCoord.xy / vec2(64.0, 64.0)).rgb, 1.0);
		}
		""";

	private static final String FINAL = """
		#version 120
		uniform sampler2D colortex0;
		void main() {
			gl_FragColor = texture2D(colortex0, gl_FragCoord.xy / vec2(64.0, 64.0));
		}
		""";

	@TempDir
	Path dir;

	private Path writePack() throws Exception {
		Path shaders = dir.resolve("pack/shaders");
		Files.createDirectories(shaders.resolve("tex"));
		BufferedImage red = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 4; y++) {
			for (int x = 0; x < 4; x++) {
				red.setRGB(x, y, 0xffff0000);
			}
		}
		ImageIO.write(red, "png", shaders.resolve("tex/red.png").toFile());
		Files.writeString(shaders.resolve("shaders.properties"), PROPERTIES);
		Files.writeString(shaders.resolve("composite.vsh"), VERTEX);
		Files.writeString(shaders.resolve("composite.fsh"), WRITER);
		Files.writeString(shaders.resolve("composite1.vsh"), VERTEX);
		Files.writeString(shaders.resolve("composite1.fsh"), READER);
		Files.writeString(shaders.resolve("final.vsh"), VERTEX);
		Files.writeString(shaders.resolve("final.fsh"), FINAL);
		return dir.resolve("pack");
	}

	private static long[] upload(long ctx, ByteBuffer bytes, List<Long> tracker) {
		bytes.rewind();
		long buffer = MetalBridge.newBuffer(ctx, Math.max(16, bytes.remaining()));
		MemorySegment.ofAddress(MetalBridge.bufferContents(buffer)).reinterpret(bytes.remaining())
			.copyFrom(MemorySegment.ofBuffer(bytes));
		tracker.add(buffer);
		return new long[] {buffer, 0};
	}

	/**
	 * The RGB of a pixel given in OpenGL's window coordinates (origin bottom left). The engine's targets and the main colour
	 * hold memory row 0 at the window's bottom, as OpenGL does (EngineScaleAndFinalGpuTest pins the same rows).
	 */
	private static int[] rgbAt(byte[] bytes, int x, int y) {
		int at = (y * W + x) * 4;
		return new int[] {bytes[at] & 0xff, bytes[at + 1] & 0xff, bytes[at + 2] & 0xff};
	}

	@Test
	void aWrittenTargetIsReadInsteadOfTheStageImageAndTheScaledPassCoversItsQuarter() throws Exception {
		PackModel model;
		try (PackSource source = PackSource.open(writePack())) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		assertTrue(model.problems().isEmpty(), model.problems().toString());
		List<Long> toRelease = new ArrayList<>();
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context(), enc = gpu.encoder();
			DimensionSet set = DimensionSet.load(ctx, "texover", model, model.rootFolder(), null, RGBA8,
				model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
			assertTrue(set.absent() == 0 && set.onRoad() == 0, set.notes().toString());
			Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(), set.usedTargets(), false,
				false, W, H, Targets.engineTextures(ctx), null);
			assertTrue(targets.create());
			long noise = Engine.makeNoise(ctx, set.noiseResolution());
			long copy = Engine.copyPipeline(ctx, targets.format(0));
			long world = ShaderNative.textureNew(ctx, RGBA8, ShaderNative.TYPE_2D, W, H, 1, 1, RT_READ, ShaderNative.STORAGE_PRIVATE);
			toRelease.add(world);
			EngineFrame.Main main = new EngineFrame.Main();
			main.set(world, RGBA8, W, H, null);
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
			CustomTextureSet customs = CustomTextureSet.create(ctx, model.textures(), path -> null, FakeGameViews.none());
			assertTrue(customs != null, "the custom set is created");
			EngineFrame frame = new EngineFrame(set, targets, null, noise, copy, 0, (bytes, out) -> {
				long[] slice = upload(ctx, bytes, toRelease);
				out[0] = slice[0];
				out[1] = slice[1];
			}, Map.of(), model.properties().scales(),
				new EngineFrame.Gbuffers(new int[0], new int[0], new int[0], false, phase -> {}, null), customs, null);
			frame.clearHook(enc, null, targets, blocks);
			frame.compositeHook(enc, null, targets, main, blocks);
			MetalBridge.commitAndWait(enc);

			byte[] bytes = gpu.readTexture(world, 0, 0, W, H, 1, 4);
			int[] green = {0, 255, 0};
			assertArrayEquals(green, rgbAt(bytes, 4, 4), "composite1 reads the green composite wrote, not the stage's red image");
			assertArrayEquals(green, rgbAt(bytes, 28, 28),
				"the scaled pass covers its quarter's upper-right corner: a quad, not one triangle");
			int[] outside = rgbAt(bytes, 48, 48);
			assertEquals(0, outside[0], "outside the quarter colortex5 keeps its clear, and the red image is not read");
			assertEquals(0, outside[1], "outside the quarter the scaled pass wrote nothing");

			customs.close();
			set.passes().forEach(pass -> toRelease.add(pass.pipeline()));
			targets.release();
			toRelease.add(noise);
			toRelease.add(copy);
		} finally {
			toRelease.forEach(handle -> {
				if (handle != null && handle != 0) {
					MetalBridge.release(handle);
				}
			});
		}
	}
}
