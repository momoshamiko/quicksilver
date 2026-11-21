// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import dev.mcms.shaders.runtime.uniforms.FrameUniforms;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The E3 review's allocation rule: a steady-state frame allocates no Java objects of its own. The mcms-passes pack's
 * frame runs on the headless device after a warm-up; the heap bytes this thread allocates across the measured frames
 * (ThreadMXBean, the frame path only: commits and the test's own uploads stay outside the window) must stay near
 * zero. Before the fix one EngineFrame per frame allocated a pass-block buffer, label strings, arenas, clear arrays,
 * the warn-once set and per-frame plans on every frame: thousands of bytes per frame.
 */
@Tag("gpu")
class EngineFrameAllocationGpuTest {

	private static final int W = 1920, H = 1080;
	private static final int RGBA8 = 70, DEPTH32F = 252;
	private static final int RT_READ = ShaderNative.USAGE_SHADER_READ | ShaderNative.USAGE_RENDER_TARGET;
	private static final int WARM_UP = 100, MEASURED = 2000;
	/**
	 * The heap bytes the measured frames may allocate in total. Measured on 2026-11-13: about 100 KB over 2000 frames
	 * and the same 100 KB over 200 (ThreadMXBean counts whole thread-buffer refills, so the one-off cost of the first
	 * refills shows once, not per frame). A per-frame allocation of 128 bytes would exceed this bound over 2000 frames.
	 */
	private static final long TOTAL_BYTES_LIMIT = 256L * 1024;

	@Test
	void aSteadyStateFrameAllocatesNothing() throws Exception {
		Path packDir = findPack();
		Properties options = new Properties();
		options.setProperty("MCMS_HIGH_TARGETS", "true");
		PackModel model;
		try (PackSource source = PackSource.open(packDir)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", options);
		}
		CustomUniforms custom = CustomUniforms.of(model);
		ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
		long thread = Thread.currentThread().threadId();
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long enc = gpu.encoder();
			List<Long> toRelease = new ArrayList<>();
			try {
				DimensionSet set = DimensionSet.load(ctx, "mcms-passes", model, model.rootFolder(), custom, RGBA8,
					model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
				assertTrue(set.absent() == 0 && set.onRoad() == 0, "every program runs from the pack's files");
				Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(),
					set.usedTargets(), false, false, W, H, Targets.engineTextures(ctx), null);
				assertTrue(targets.create());
				long noise = Engine.makeNoise(ctx, set.noiseResolution());
				long copy = Engine.copyPipeline(ctx, targets.format(0));
				long world = ShaderNative.textureNew(ctx, RGBA8, ShaderNative.TYPE_2D, W, H, 1, 1, RT_READ,
					ShaderNative.STORAGE_PRIVATE);
				toRelease.add(world);
				// One block buffer, written once: the frame path's uploads (a real frame's transient memory) reuse it.
				ByteBuffer bytes = ByteBuffer.allocateDirect(FrameUniforms.size()).order(ByteOrder.LITTLE_ENDIAN);
				FrameUniforms uniforms = new FrameUniforms();
				uniforms.write(bytes);
				long block = MetalBridge.newBuffer(ctx, Math.max(16, bytes.capacity()));
				toRelease.add(block);
				long[] frameBlock = {block, 0};
				EngineFrame.PassBlocks blocks = new EngineFrame.PassBlocks() {
					@Override
					public long[] frameBlock(String program) {
						return frameBlock;
					}

					@Override
					public long[] customBlock() {
						return null;
					}
				};
				EngineFrame frame = new EngineFrame(set, targets, null, noise, copy, 0, (uploaded, out) -> {
					out[0] = block;
					out[1] = 0;
				}, Map.of(), Map.of(), new EngineFrame.Gbuffers(new int[0], new int[0], new int[0], false,
					phase -> {}, null));
				EngineFrame.Main main = new EngineFrame.Main();
				main.set(world, RGBA8, W, H, null);
				long measured = 0;
				for (int frameNo = 0; frameNo < WARM_UP + MEASURED; frameNo++) {
					long before = threads.getThreadAllocatedBytes(thread);
					frame.clearHook(enc, null, targets, blocks);
					frame.compositeHook(enc, null, targets, main, blocks);
					long after = threads.getThreadAllocatedBytes(thread);
					if (frameNo >= WARM_UP) {
						measured += after - before;
					}
					MetalBridge.commitAndWait(enc);
				}
				assertTrue(measured <= TOTAL_BYTES_LIMIT, "the steady-state frames allocated " + measured + " bytes on the heap over "
					+ MEASURED + " frames (limit " + TOTAL_BYTES_LIMIT + ")");
				frame.close();
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

	private static Path findPack() {
		Path dir = Path.of(System.getProperty("user.dir"));
		for (int i = 0; i < 6; i++) {
			Path candidate = dir.resolve(Path.of("bench", "test-packs", "mcms-passes"));
			if (java.nio.file.Files.isDirectory(candidate)) {
				return candidate;
			}
			dir = dir.getParent();
		}
		throw new IllegalStateException("bench/test-packs/mcms-passes not found above " + System.getProperty("user.dir"));
	}
}
