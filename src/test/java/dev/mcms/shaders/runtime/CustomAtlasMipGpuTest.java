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
import dev.mcms.shaders.runtime.textures.CustomTextureSet;
import dev.mcms.shaders.runtime.textures.FakeGameViews;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review item 7 on the device: a custom texture of the game's atlas samples with a mip filter, so a program's texture2DLod
 * reads the atlas's level 1. The fake game atlas is a two-level texture, red at level 0 and green at level 1; the
 * composite pass reads it at lod 1.0 and the final pass shows the result. Without the mip filter Metal would read red.
 */
@Tag("gpu")
final class CustomAtlasMipGpuTest {

	private static final int W = 64, H = 64, RGBA8 = 70;
	private static final int RT_READ = ShaderNative.USAGE_SHADER_READ | ShaderNative.USAGE_RENDER_TARGET;

	private static final String PROPERTIES = "customTexture.textureAtlas=minecraft:textures/atlas/blocks.png\n";

	private static final String VERTEX = """
		#version 120
		void main() {
			gl_Position = ftransform();
		}
		""";

	private static final String FRAGMENT = """
		#version 120
		uniform sampler2D textureAtlas;
		/* DRAWBUFFERS:0 */
		void main() {
			gl_FragData[0] = texture2DLod(textureAtlas, vec2(0.25, 0.25), 1.0);
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
		Files.createDirectories(shaders);
		Files.writeString(shaders.resolve("shaders.properties"), PROPERTIES);
		Files.writeString(shaders.resolve("composite1.vsh"), VERTEX);
		Files.writeString(shaders.resolve("composite1.fsh"), FRAGMENT);
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

	/** A two-level RGBA8 atlas: level 0 (4x4) red, level 1 (2x2) green. */
	private static long atlas(long ctx, List<Long> tracker) {
		long texture = ShaderNative.textureNew(ctx, RGBA8, ShaderNative.TYPE_2D, 4, 4, 1, 2, ShaderNative.USAGE_SHADER_READ,
			ShaderNative.STORAGE_PRIVATE);
		assertTrue(texture != 0, "the device refused the atlas");
		byte[] red = new byte[4 * 4 * 4], green = new byte[2 * 2 * 4];
		for (int i = 0; i < red.length; i += 4) {
			red[i] = (byte) 255;
			red[i + 3] = (byte) 255;
		}
		for (int i = 0; i < green.length; i += 4) {
			green[i + 1] = (byte) 255;
			green[i + 3] = (byte) 255;
		}
		assertTrue(ShaderNative.textureUpload(ctx, texture, red, 4 * 4L, 0, 0, 0, 0, 0, 0, 4, 4, 1));
		assertTrue(ShaderNative.textureUpload(ctx, texture, green, 2 * 4L, 0, 0, 1, 0, 0, 0, 2, 2, 1));
		tracker.add(texture);
		return texture;
	}

	@Test
	void aCustomAtlasReadsItsMipLevelThroughTexture2DLod() throws Exception {
		PackModel model;
		try (PackSource source = PackSource.open(writePack())) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		assertTrue(model.problems().isEmpty(), model.problems().toString());
		List<Long> toRelease = new ArrayList<>();
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context(), enc = gpu.encoder();
			DimensionSet set = DimensionSet.load(ctx, "atlas", model, model.rootFolder(), null, RGBA8,
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
			FakeGameViews views = FakeGameViews.none();
			views.atlasView = atlas(ctx, toRelease);
			CustomTextureSet customs = CustomTextureSet.create(ctx, model.textures(), path -> null, views);
			assertTrue(customs != null, "the custom set is created");
			EngineFrame frame = new EngineFrame(set, targets, null, noise, copy, 0, (bytes, out) -> {
				long[] slice = upload(ctx, bytes, toRelease);
				out[0] = slice[0];
				out[1] = slice[1];
			}, Map.of(), Map.of(), new EngineFrame.Gbuffers(new int[0], new int[0], new int[0], false, phase -> {}, null),
				customs, null);
			frame.clearHook(enc, null, targets, blocks);
			frame.compositeHook(enc, null, targets, main, blocks);
			MetalBridge.commitAndWait(enc);

			byte[] bytes = gpu.readTexture(world, 0, 0, W, H, 1, 4);
			int at = ((H - 1 - 8) * W + 8) * 4;  // a pixel well inside the picture, top-down row 8
			assertEquals(0, bytes[at] & 0xff, "the level 0 red is not what the pass read");
			assertEquals(255, bytes[at + 1] & 0xff, "the pass read level 1 (green): the custom atlas has its mip filter");

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
