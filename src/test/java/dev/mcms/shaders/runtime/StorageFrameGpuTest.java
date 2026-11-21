// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.buffers.StorageBuffers;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
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
 * E6-B end to end: a pack declares {@code bufferObject.2 = 64} (binding 2 on purpose, so a slot-for-order mistake shows). A
 * compute kernel writes the buffer, a later full-screen pass reads it into colortex0, and the final pass shows colortex0.
 * Across two frames the buffer persists (v[0] counts the frames); a resize keeps the fixed buffer. A declared buffer with no
 * bytes at the view size skips the programs that bind it. The test checks the picture and the raw buffer bytes.
 */
@Tag("gpu")
final class StorageFrameGpuTest {

	private static final int W = 64, H = 64, RGBA8 = 70;
	private static final int RT_READ = ShaderNative.USAGE_SHADER_READ | ShaderNative.USAGE_RENDER_TARGET;

	private static final String PROPERTIES = "bufferObject.2 = 64\n";

	/**
	 * Frame count in v[0] (each frame adds one); v[1..15] hold 0xA0 + index. One group of one thread: without {@code workGroups}
	 * the documented default ({@code workGroupsRender = 1.0 1.0}) dispatches one group per pixel, and 4096 unsynchronised
	 * increments of v[0] gave 1 or, now and then under load, 2 (the test's own data race, not the engine's).
	 */
	private static final String KERNEL = """
		#version 430
		layout(local_size_x = 1) in;
		const ivec3 workGroups = ivec3(1, 1, 1);
		layout(std430, binding = 2) buffer Store { uint v[]; };
		void main() {
			v[0] = v[0] + 1u;
			for (int i = 1; i < 16; i++) {
				v[i] = 0xA0u + uint(i);
			}
		}
		""";

	private static final String FRAGMENT = """
		#version 430
		layout(std430, binding = 2) buffer Store { uint v[]; };
		layout(location = 0) out vec4 fragColor;
		/* DRAWBUFFERS:0 */
		void main() {
			fragColor = vec4(float(v[0]) / 255.0, float(v[3]) / 255.0, 0.0, 1.0);
		}
		""";

	private static final String VERTEX = """
		#version 120
		void main() {
			gl_Position = ftransform();
		}
		""";

	/** A vertex stage that writes the buffer (v[5] = 0x77, the render stages' storage binding): Complementary's real path. */
	private static final String VERTEX_WRITES = """
		#version 430 compatibility
		layout(std430, binding = 2) buffer Store { uint v[]; };
		void main() {
			v[5] = 0x77u;
			gl_Position = ftransform();
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
		Files.writeString(shaders.resolve("composite.csh"), KERNEL);
		Files.writeString(shaders.resolve("composite1.vsh"), VERTEX_WRITES);
		Files.writeString(shaders.resolve("composite1.fsh"), FRAGMENT);
		Files.writeString(shaders.resolve("final.vsh"), VERTEX);
		Files.writeString(shaders.resolve("final.fsh"), FINAL);
		return dir.resolve("pack");
	}

	/** The buffer's bytes, read from its CPU-visible contents. */
	private static byte[] contents(long handle, long size) {
		return MemorySegment.ofAddress(MetalBridge.bufferContents(handle)).reinterpret(size).toArray(ValueLayout.JAVA_BYTE);
	}

	/** One frame's uniform block bytes, uploaded as a fresh buffer the test releases at the end. */
	private static long[] upload(long ctx, ByteBuffer bytes, List<Long> tracker) {
		bytes.rewind();
		long buffer = MetalBridge.newBuffer(ctx, Math.max(16, bytes.remaining()));
		MemorySegment.ofAddress(MetalBridge.bufferContents(buffer)).reinterpret(bytes.remaining())
			.copyFrom(MemorySegment.ofBuffer(bytes));
		tracker.add(buffer);
		return new long[] {buffer, 0};
	}

	/** The red and green of the picture's first top-down row pixel (the final pass writes the main colour). */
	private static float[] pixel(HeadlessGpu gpu, long texture) {
		byte[] bytes = gpu.readTexture(texture, 0, 0, W, H, 1, 4);
		int at = ((H - 1 - 8) * W + 8) * 4;  // a pixel well inside the picture, top-down row 8
		return new float[] {(bytes[at] & 0xff) / 255f, (bytes[at + 1] & 0xff) / 255f};
	}

	/** One engine frame: the head, then the composite (the kernel, the read pass, the final pass). */
	private static void frame(EngineFrame frame, long enc, Targets targets, EngineFrame.Main main, EngineFrame.PassBlocks blocks) {
		frame.clearHook(enc, null, targets, blocks);
		frame.compositeHook(enc, null, targets, main, blocks);
		MetalBridge.commitAndWait(enc);
	}

	@Test
	void aKernelWritesAFixedBufferAPassReadsItAndTheBufferPersistsAcrossFrames() throws Exception {
		PackModel model;
		try (PackSource source = PackSource.open(writePack())) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		assertTrue(model.problems().isEmpty(), model.problems().toString());
		assertTrue(model.buffers().problems().isEmpty(), model.buffers().problems().toString());
		List<Long> toRelease = new ArrayList<>();
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context(), enc = gpu.encoder();
			DimensionSet set = DimensionSet.load(ctx, "storage", model, model.rootFolder(), null, RGBA8,
				model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
			assertTrue(set.absent() == 0 && set.onRoad() == 0, set.notes().toString());
			Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(), set.usedTargets(), false,
				false, W, H, Targets.engineTextures(ctx), null);
			assertTrue(targets.create());
			StorageBuffers storage = StorageBuffers.create(ctx, model.buffers(), W, H);
			assertNotNull(storage);
			long bufferHandle = storage.handle(2);
			assertNotEquals(0L, bufferHandle, "the pack's bufferObject.2 is created");
			long noise = Engine.makeNoise(ctx, set.noiseResolution());
			long copy = Engine.copyPipeline(ctx, targets.format(0));
			// The main colour: a zero picture (the final pass writes it, so its start does not matter).
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
			EngineFrame engine = new EngineFrame(set, targets, null, noise, copy, 0, (bytes, out) -> {
				long[] slice = upload(ctx, bytes, toRelease);
				out[0] = slice[0];
				out[1] = slice[1];
			}, Map.of(), Map.of(), new EngineFrame.Gbuffers(new int[0], new int[0], new int[0], false, phase -> {}, null),
				null, storage);

			frame(engine, enc, targets, main, blocks);
			assertEquals(1, contents(bufferHandle, 16)[0], "the kernel ran once: v[0] counts the frame");
			frame(engine, enc, targets, main, blocks);
			byte[] after = contents(bufferHandle, 64);
			assertEquals(2, after[0], "the buffer persists across frames: the second kernel adds to the first's count");
			assertEquals((byte) 0xA3, after[3 * 4], "v[3] holds the kernel's 0xA0 + 3");
			assertEquals((byte) 0x77, after[5 * 4], "the vertex stage's write reached the buffer after the kernel's 0xA5");
			float[] picture = pixel(gpu, world);
			assertEquals(2f / 255, picture[0], 1e-3f, "the read pass saw v[0] = 2");
			assertEquals(0xA3 / 255f, picture[1], 1e-3f, "and v[3] = 0xA3 (the fixed binding 2 reached the fragment stage)");

			// A resize keeps the fixed buffer and its contents.
			assertTrue(storage.resize(ctx, W * 2, H * 2), "the resize succeeds");
			assertEquals(bufferHandle, storage.handle(2), "a fixed buffer keeps its handle through a resize");
			assertEquals(2, contents(storage.handle(2), 64)[0], "and its contents");

			storage.close();
			targets.release();
			set.passes().forEach(pass -> toRelease.add(pass.pipeline()));
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

	/**
	 * A declared buffer with no bytes at this view size (a relative size scaled to 0; E6, finding 3): the set holds no buffer
	 * for it, so the kernel and the read pass that bind it are skipped (each logged once), and the frame goes on.
	 */
	@Test
	void aDeclaredBufferWithNoBytesAtThisSizeSkipsItsProgramsAndBindsNothing() throws Exception {
		Path pack = writePack();
		Files.writeString(dir.resolve("pack/shaders/shaders.properties"), "bufferObject.2 = 4 true 0.0001 0.0001\n");
		PackModel model;
		try (PackSource source = PackSource.open(pack)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		assertTrue(model.buffers().problems().isEmpty(), model.buffers().problems().toString());
		List<Long> toRelease = new ArrayList<>();
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context(), enc = gpu.encoder();
			DimensionSet set = DimensionSet.load(ctx, "storage-empty", model, model.rootFolder(), null, RGBA8,
				model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
			assertTrue(set.absent() == 0 && set.onRoad() == 0, set.notes().toString());
			Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(), set.usedTargets(), false,
				false, W, H, Targets.engineTextures(ctx), null);
			assertTrue(targets.create());
			StorageBuffers storage = StorageBuffers.create(ctx, model.buffers(), W, H);
			assertNotNull(storage);
			assertEquals(0L, storage.handle(2), "the zero-byte buffer is not created");
			assertEquals(1, storage.problems().size(), storage.problems().toString());
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
			EngineFrame engine = new EngineFrame(set, targets, null, noise, copy, 0, (bytes, out) -> {
				long[] slice = upload(ctx, bytes, toRelease);
				out[0] = slice[0];
				out[1] = slice[1];
			}, Map.of(), Map.of(), new EngineFrame.Gbuffers(new int[0], new int[0], new int[0], false, phase -> {}, null),
				null, storage);
			frame(engine, enc, targets, main, blocks);
			assertEquals(2, engine.storageSkipCount(), "the kernel and the read pass are each skipped");
			frame(engine, enc, targets, main, blocks);
			assertEquals(2, engine.storageSkipCount(), "and a second frame adds no skip: each program is logged once");

			storage.close();
			targets.release();
			set.passes().forEach(pass -> toRelease.add(pass.pipeline()));
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
