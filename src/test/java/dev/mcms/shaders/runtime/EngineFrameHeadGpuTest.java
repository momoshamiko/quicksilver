// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.mcms.metal.BackendPassHarness;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Properties;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The frame head (plan.md E6; the documented order setup, begin, shadow, shadowcomp, prepare): setup runs once after a load
 * and once after each resize, begin runs every frame, the clearEachFrame images are zeroed before the clear hook's
 * passes, and a frame whose shadow hook did not run gets the head from the clear hook (findings 2 and 4, tests of 17).
 * The pack is two compute programs written here, so the kernels and the order are the only things under test.
 */
@Tag("gpu")
class EngineFrameHeadGpuTest {

	private static final int W = 64, H = 48;
	private static final int RGBA8 = 70, DEPTH32F = 252;

	private static final String KERNEL = """
		#version 430
		layout(local_size_x = 1) in;
		const ivec3 workGroups = ivec3(1, 1, 1);
		void main() {}
		""";

	@TempDir
	Path dir;

	/** Records the backend steps in one list, the frame head's and the clear hook's among them. */
	private static final class Recorder implements EngineFrame.HookOps {
		final List<String> log = new ArrayList<>();

		@Override public void targetClears(long enc, Targets targets) { this.log.add("clears"); }
		@Override public void depthClears(long enc, Targets targets) { this.log.add("depthClears"); }
		@Override public void depthCopy(long enc, Targets targets, int toIndex) { this.log.add("depthCopy:" + toIndex); }
		@Override public void loadCopy(long enc, Targets targets, int target) { this.log.add("load:" + target); }
		@Override public void carryCopy(long enc, Targets targets, int target) { this.log.add("carry:" + target); }
		@Override public void split(@Nullable Object encoder, long[] colors, long depth, int width, int height) {
			this.log.add("split");
		}
		@Override public void flip(Targets targets, int target) { this.log.add("flip:" + target); }
		@Override public void worldCopy(long enc, @Nullable Object encoder, Targets targets, EngineFrame.Main main) {
			this.log.add("worldCopy");
		}
		@Override public void showColortex0(long enc, @Nullable Object encoder, Targets targets, EngineFrame.Main main) {
			this.log.add("showColortex0");
		}
		@Override public void runPass(long enc, @Nullable Object encoder, Targets targets, EngineFrame.@Nullable Main main,
				EngineFrame.PassBlocks blocks, int planIndex) {
			this.log.add("pass:" + planIndex);
		}
		@Override public void setup(long enc, Targets targets, EngineFrame.PassBlocks blocks, int setupIndex) {
			this.log.add("setup:" + setupIndex);
		}
		@Override public void begin(long enc, Targets targets, EngineFrame.PassBlocks blocks, int planIndex) {
			this.log.add("begin:" + planIndex);
		}
		@Override public void clearImages(long enc, Targets targets) { this.log.add("clearImages"); }
		@Override public void endFrame(long enc) { this.log.add("endFrame"); }
	}

	@Test
	void setupRunsOnceAfterLoadAndAfterResizeAndBeginEveryFrameBeforeTheClears() throws Exception {
		Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(dir.resolve("shaders/setup.csh"), KERNEL);
		Files.writeString(dir.resolve("shaders/begin.csh"), KERNEL);
		PackModel model;
		try (PackSource source = PackSource.open(dir)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		CustomUniforms custom = CustomUniforms.of(model);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			DimensionSet set = DimensionSet.load(ctx, "head", model, model.rootFolder(), custom, RGBA8,
				model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
			assertEquals(0, set.absent(), "both kernels must compile: " + set.notes());
			Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(), set.usedTargets(),
				false, false, W, H, Targets.engineTextures(ctx), null);
			assertEquals(true, targets.create());
			List<Long> toRelease = new ArrayList<>();
			long noise = Engine.makeNoise(ctx, set.noiseResolution());
			Recorder ops = new Recorder();
			EngineFrame frame = new EngineFrame(set, targets, null, noise, 0, 0, (bytes, out) -> {
				bytes.rewind();
				long buffer = MetalBridge.newBuffer(ctx, Math.max(16, bytes.remaining()));
				MemorySegment.ofAddress(MetalBridge.bufferContents(buffer)).reinterpret(bytes.remaining())
					.copyFrom(MemorySegment.ofBuffer(bytes));
				toRelease.add(buffer);
				out[0] = buffer;
				out[1] = 0;
			}, Map.of(), Map.of(), new EngineFrame.Gbuffers(new int[0], new int[0], new int[0], false, phase -> { }, ops));
			EngineFrame.PassBlocks blocks = new EngineFrame.PassBlocks() {
				@Override public long @Nullable [] frameBlock(String program) { return null; }
				@Override public long @Nullable [] customBlock() { return null; }
			};
			long enc = gpu.encoder();
			try {
				frame(frame, enc, targets, blocks, ops, "load");
				frame(frame, enc, targets, blocks, ops, "second");
				frame.resized();
				frame(frame, enc, targets, blocks, ops, "after resize");
			} finally {
				frame.close();
				targets.release();
				set.passes().forEach(pass -> MetalBridge.release(pass.pipeline()));
				toRelease.forEach(MetalBridge::release);
				MetalBridge.release(noise);
			}
		}
	}

	/** One frame: the shadow hook's head, then the clear hook; the log of this frame is checked and cleared. */
	private static void frame(EngineFrame frame, long enc, Targets targets, EngineFrame.PassBlocks blocks, Recorder ops,
			String label) {
		ops.log.clear();
		frame.frameHead(enc, targets, blocks);
		frame.clearHook(enc, null, targets, blocks);
		List<String> expected = label.equals("second")
			? List.of("begin:0", "clearImages", "clears", "depthClears", "endFrame")
			: List.of("setup:0", "begin:0", "clearImages", "clears", "depthClears", "endFrame");
		assertEquals(expected, ops.log, "the frame " + label);
	}

	@Test
	void aFrameWithNoShadowHookGetsTheHeadFromTheClearHook() throws Exception {
		Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(dir.resolve("shaders/begin.csh"), KERNEL);
		PackModel model;
		try (PackSource source = PackSource.open(dir)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			DimensionSet set = DimensionSet.load(ctx, "headless", model, model.rootFolder(), CustomUniforms.of(model), RGBA8,
				model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
			Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(), set.usedTargets(),
				false, false, W, H, Targets.engineTextures(ctx), null);
			assertEquals(true, targets.create());
			Recorder ops = new Recorder();
			EngineFrame frame = new EngineFrame(set, targets, null, 0L, 0, 0, (bytes, out) -> { }, Map.of(), Map.of(),
				new EngineFrame.Gbuffers(new int[0], new int[0], new int[0], false, phase -> { }, ops));
			EngineFrame.PassBlocks blocks = new EngineFrame.PassBlocks() {
				@Override public long @Nullable [] frameBlock(String program) { return null; }
				@Override public long @Nullable [] customBlock() { return null; }
			};
			try {
				frame.clearHook(gpu.encoder(), null, targets, blocks);
				assertEquals(List.of("begin:0", "clearImages", "clears", "depthClears", "endFrame"), ops.log,
					"no shadow hook ran: the head still comes first");
			} finally {
				frame.close();
				targets.release();
				set.passes().forEach(pass -> MetalBridge.release(pass.pipeline()));
			}
		}
	}

	/**
	 * A frame whose engine clear hook returned early still closes its head window (E6, finding 12). The clear hook returns
	 * early here because a render pass is left open (drawClearHook logs it and returns), so the engine's finally is what
	 * closes the window. The next frame's shadow hook then runs the head with begin and the custom image clears; before the
	 * fix the flag stayed set and that head was skipped.
	 */
	@Test
	@Tag("gpu")
	void aFrameWhoseClearHookReturnedEarlyGivesTheNextFrameItsHead() throws Exception {
		renderDevice();
		Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(dir.resolve("shaders/begin.csh"), KERNEL);
		PackModel model;
		try (PackSource source = PackSource.open(dir)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			DimensionSet set = DimensionSet.load(ctx, "head-hook", model, model.rootFolder(), CustomUniforms.of(model), RGBA8,
				model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
			Engine engine = new Engine();
			engine.prepareLoad(model);
			engine.install(set, new Engine.Surface(ctx, W, H, RGBA8));
			assertEquals(Engine.State.ACTIVE, engine.state(), "the set installs, so the engine's hooks run");
			Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(), set.usedTargets(),
				false, false, W, H, Targets.engineTextures(ctx), null);
			assertEquals(true, targets.create());
			Recorder ops = new Recorder();
			EngineFrame frame = new EngineFrame(set, targets, null, 0L, 0, 0, (bytes, out) -> { }, Map.of(), Map.of(),
				new EngineFrame.Gbuffers(new int[0], new int[0], new int[0], false, phase -> { }, ops));
			// The engine's own frame is replaced by one that logs its steps; the engine's hooks drive it unchanged.
			swapFrame(engine, frame).close();
			GpuDevice device = RenderSystem.getDevice();
			GpuTexture texture = device.createTexture(() -> "clear hook: open pass", GpuTexture.USAGE_RENDER_ATTACHMENT,
				GpuFormat.RGBA8_UNORM, 16, 16, 1, 1);
			GpuTextureView view = device.createTextureView(texture);
			try {
				engine.shadowFrameHook(null);  // frame 1: the shadow hook runs the head
				assertEquals(List.of("begin:0", "clearImages"), ops.log, "the shadow hook runs the head");
				ops.log.clear();
				CommandEncoder commands = device.createCommandEncoder();
				RenderPass leaked = commands.createRenderPass(() -> "clear hook: open pass", view, Optional.empty(), null,
					OptionalDouble.empty());
				try {
					assertTrue(MetalBridge.inRenderPass(backendEncoder(device)), "the pass is open on the engine's encoder");
					engine.clearHook();  // returns early: a render pass is open, so the frame's clears are skipped
				} finally {
					leaked.close();
				}
				assertEquals(List.of(), ops.log, "the clear hook drew nothing");
				engine.shadowFrameHook(null);  // frame 2: the shadow hook runs the head again
				assertEquals(List.of("begin:0", "clearImages"), ops.log, "the next frame's head runs in full");
			} finally {
				view.close();
				texture.close();
				engine.shutdown();
				targets.release();
			}
		}
	}

	/** The game's device for the engine's hooks: the render thread and a Metal device, as the other engine tests set them. */
	private static void renderDevice() throws Exception {
		System.setProperty("mcms.metal.mslCache", "false");
		if (RenderSystem.tryGetDevice() == null) {
			RenderSystem.initRenderThread();
		}
		if (RenderSystem.tryGetDevice() == null) {
			RenderSystem.initRenderer(BackendPassHarness.newDevice());
		}
	}

	/** The encoder object the engine's hooks read: the frontend encoder's backend, as the engine's own lookup takes it. */
	private static Object backendEncoder(GpuDevice device) {
		return MetalBridge.encoder(((FrontendCommandEncoder) ((FrontendGpuDevice) device).createCommandEncoder()).backend());
	}

	/** Puts {@code frame} in the engine's frame field and returns the frame it replaced. */
	private static EngineFrame swapFrame(Engine engine, EngineFrame frame) throws ReflectiveOperationException {
		Field field = Engine.class.getDeclaredField("frame");
		field.setAccessible(true);
		EngineFrame replaced = (EngineFrame) field.get(engine);
		field.set(engine, frame);
		return replaced;
	}
}
