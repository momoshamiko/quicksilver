// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The no-final path's steady state (the review's N2): a pack without a final, whose main colour format differs from
 * colortex0's, so the frame copies colortex0 onto the main colour through the draw path every frame. That path must
 * allocate no Java objects of its own, like the rest of the frame (see {@link EngineFrameAllocationGpuTest}).
 */
@Tag("gpu")
class EngineNoFinalAllocationGpuTest {

	private static final int W = 1920, H = 1080;
	private static final int RGBA8 = 70, RGBA16F = 115, DEPTH32F = 252;
	private static final int RT_READ = ShaderNative.USAGE_SHADER_READ | ShaderNative.USAGE_RENDER_TARGET;
	private static final int WARM_UP = 100, MEASURED = 5000;
	/** The fixed one-off refill cost (about 100 KB, see EngineFrameAllocationGpuTest) plus room; 72 bytes a frame exceeds it. */
	private static final long TOTAL_BYTES_LIMIT = 256L * 1024;

	@Test
	void aNoFinalFrameWithAFormatChangingCopyAllocatesNothing(@TempDir Path dir) throws Exception {
		Path shaders = Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(shaders.resolve("composite.vsh"), """
			#version 130
			varying vec2 texcoord;
			void main() {
				texcoord = gl_MultiTexCoord0.xy;
				gl_Position = ftransform();
			}
			""");
		Files.writeString(shaders.resolve("composite.fsh"), """
			#version 130
			/* DRAWBUFFERS:0 */
			void main() {
				gl_FragColor = vec4(0.0, 1.0, 0.0, 1.0);
			}
			""");
		PackModel model;
		try (PackSource source = PackSource.open(dir)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
		long thread = Thread.currentThread().threadId();
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long enc = gpu.encoder();
			DimensionSet set = DimensionSet.load(ctx, "no-final", model, model.rootFolder(), CustomUniforms.of(model),
				RGBA16F, model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
			assertTrue(set.absent() == 0 && set.passes().size() == 1, "the composite must run: " + set.notes());
			Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(), set.usedTargets(),
				false, false, W, H, Targets.engineTextures(ctx), null);
			assertTrue(targets.create());
			assertEquals(RGBA8, targets.format(0), "colortex0 is RGBA8, the main colour RGBA16F: the draw path");
			long noise = Engine.makeNoise(ctx, set.noiseResolution());
			long copy = Engine.copyPipeline(ctx, targets.format(0));
			long finalCopy = Engine.copyPipeline(ctx, RGBA16F);
			long world = ShaderNative.textureNew(ctx, RGBA16F, ShaderNative.TYPE_2D, W, H, 1, 1, RT_READ,
				ShaderNative.STORAGE_PRIVATE);
			long block = MetalBridge.newBuffer(ctx, 256);
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
			EngineFrame frame = new EngineFrame(set, targets, null, noise, copy, finalCopy, (uploaded, out) -> {
				assertTrue(uploaded.isDirect());
				out[0] = block;
				out[1] = 0;
			}, Map.of(), Map.of(), new EngineFrame.Gbuffers(new int[0], new int[0], new int[0], false,
				phase -> {}, null));
			EngineFrame.Main main = new EngineFrame.Main();
			main.set(world, RGBA16F, W, H, null);
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
			assertTrue(measured <= TOTAL_BYTES_LIMIT, "the no-final draw path allocated " + measured
				+ " bytes on the heap over " + MEASURED + " frames (limit " + TOTAL_BYTES_LIMIT + ")");
			frame.close();
			set.passes().forEach(pass -> MetalBridge.release(pass.pipeline()));
			targets.release();
			MetalBridge.release(noise);
			MetalBridge.release(copy);
			MetalBridge.release(finalCopy);
			MetalBridge.release(world);
		}
	}
}
