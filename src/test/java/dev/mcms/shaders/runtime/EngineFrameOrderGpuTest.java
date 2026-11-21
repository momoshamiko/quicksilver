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
import dev.mcms.shaders.runtime.uniforms.FrameUniforms;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The frame's hook order (plan.md E4, the frame-order and phases bullets) and the copies the hooks make. Most tests run
 * the frame with a recording {@link EngineFrame.HookOps}: every backend step is one entry of a list, so the documented
 * sequence is compared exactly. The last three run the Metal copies on the headless device and read the textures back.
 * The pack is {@code mcms-passes} (deferred, deferred1, composite .. composite5, final; no prepare pass).
 */
@Tag("gpu")
class EngineFrameOrderGpuTest {

	private static final int W = 64, H = 48;
	private static final int RGBA8 = 70, DEPTH32F = 252;
	private static final float[] CLEAR = {0.5f, 0.25f, 0.75f, 1f};

	private static PackModel model;
	private static CustomUniforms custom;

	@BeforeAll
	static void loadPack() throws Exception {
		Path dir = Path.of(System.getProperty("user.dir"));
		Path pack = null;
		for (int i = 0; i < 6 && pack == null; i++) {
			Path candidate = dir.resolve(Path.of("bench", "test-packs", "mcms-passes"));
			if (java.nio.file.Files.isDirectory(candidate)) {
				pack = candidate;
			}
			dir = dir.getParent();
		}
		assertTrue(pack != null, "bench/test-packs/mcms-passes not found above " + System.getProperty("user.dir"));
		Properties options = new Properties();
		options.setProperty("MCMS_HIGH_TARGETS", "true");
		try (PackSource source = PackSource.open(pack)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", options);
		}
		assertTrue(model.problems().isEmpty(), "the test pack must load clean: " + model.problems());
		custom = CustomUniforms.of(model);
	}

	/** Records every backend step as one entry: the order is what the tests compare. */
	private static final class Recorder implements EngineFrame.HookOps {
		final List<String> log = new ArrayList<>();
		private final List<String> passNames;

		Recorder(List<String> passNames) {
			this.passNames = passNames;
		}

		@Override
		public void targetClears(long enc, Targets targets) {
			this.log.add("clears");
		}

		@Override
		public void depthClears(long enc, Targets targets) {
			this.log.add("depthClears");
		}

		@Override
		public void depthCopy(long enc, Targets targets, int toIndex) {
			this.log.add("depthCopy:" + toIndex);
		}

		@Override
		public void loadCopy(long enc, Targets targets, int target) {
			this.log.add("load:" + target);
		}

		@Override
		public void carryCopy(long enc, Targets targets, int target) {
			this.log.add("carry:" + target);
		}

		@Override
		public void split(@Nullable Object encoder, long[] colors, long depth, int width, int height) {
			this.log.add("split:" + colors.length);
		}

		@Override
		public void flip(Targets targets, int target) {
			this.log.add("flip:" + target);
		}

		@Override
		public void worldCopy(long enc, @Nullable Object encoder, Targets targets, EngineFrame.Main main) {
			this.log.add("worldCopy");
		}

		@Override
		public void showColortex0(long enc, @Nullable Object encoder, Targets targets, EngineFrame.Main main) {
			this.log.add("showColortex0");
		}

		@Override
		public void setup(long enc, Targets targets, EngineFrame.PassBlocks blocks, int setupIndex) {
			this.log.add("setup:" + setupIndex);
		}

		@Override
		public void begin(long enc, Targets targets, EngineFrame.PassBlocks blocks, int planIndex) {
			this.log.add("begin:" + this.passNames.get(planIndex));
		}

		@Override
		public void clearImages(long enc, Targets targets) {
			this.log.add("clearImages");
		}

		@Override
		public void runPass(long enc, @Nullable Object encoder, Targets targets, EngineFrame.@Nullable Main main,
				EngineFrame.PassBlocks blocks, int planIndex) {
			this.log.add("pass:" + this.passNames.get(planIndex));
		}

		@Override
		public void endFrame(long enc) {
			this.log.add("endFrame");
		}
	}

	/** The set of the test pack on the headless device, its created targets and the engine's noise texture. */
	private record Rig(HeadlessGpu gpu, long ctx, long enc, DimensionSet set, Targets targets, long noise,
			List<Long> toRelease) {

		List<String> passNames() {
			return this.set.passes().stream().map(CompiledProgram::name).toList();
		}

		void release() {
			this.targets.release();
			this.toRelease.add(this.noise);
			this.set.passes().forEach(pass -> this.toRelease.add(pass.pipeline()));
			this.toRelease.forEach(handle -> {
				if (handle != 0) {
					MetalBridge.release(handle);
				}
			});
		}
	}

	private static Rig rig(HeadlessGpu gpu, boolean depth1, boolean depth2) {
		long ctx = gpu.context();
		DimensionSet set = DimensionSet.load(ctx, "mcms-passes", model, model.rootFolder(), custom, RGBA8,
			model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
		Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(), set.usedTargets(),
			depth1, depth2, W, H, Targets.engineTextures(ctx), null);
		assertTrue(targets.create(), "the device must create the targets");
		return new Rig(gpu, ctx, gpu.encoder(), set, targets, Engine.makeNoise(ctx, set.noiseResolution()),
			new ArrayList<>());
	}

	/** The gbuffers of a frame with a world: sky {0,1}, opaque {0,2}, translucent {1}; feedback and carry as given. */
	private static EngineFrame.Gbuffers worldGbuffers(List<String> phases, EngineFrame.HookOps ops, int[] feedbackOpaque,
			int[] carriedOpaque, int[] feedbackTranslucent) {
		return new EngineFrame.Gbuffers(new int[] {0, 1}, new int[] {0, 2}, new int[] {1}, true,
			phase -> phases.add("phase:" + phase), ops, feedbackOpaque, carriedOpaque, feedbackTranslucent);
	}

	/** A frame whose per-frame block is uploaded the way the engine uploads it (a transient slice of the device). */
	private static EngineFrame frame(Rig rig, EngineFrame.Gbuffers gbuffers) {
		return new EngineFrame(rig.set(), rig.targets(), null, rig.noise(), 0, 0, (bytes, out) -> {
			long[] slice = upload(rig, bytes);
			out[0] = slice[0];
			out[1] = slice[1];
		}, Map.of(), Map.of(), gbuffers);
	}

	/** Blocks of zeros for the passes that bind them: the programs read them, the values do not matter here. */
	private static EngineFrame.PassBlocks blocks(Rig rig) {
		long[] frame = upload(rig, block(new FrameUniforms(), null));
		long[] customBlock = custom != null && custom.size() > 0 ? upload(rig, block(null, custom)) : null;
		return new EngineFrame.PassBlocks() {
			@Override
			public long @Nullable [] frameBlock(String program) {
				return frame;
			}

			@Override
			public long @Nullable [] customBlock() {
				return customBlock;
			}
		};
	}

	private static ByteBuffer block(@Nullable FrameUniforms uniforms, @Nullable CustomUniforms customUniforms) {
		if (uniforms != null) {
			ByteBuffer bytes = ByteBuffer.allocate(FrameUniforms.size()).order(ByteOrder.LITTLE_ENDIAN);
			uniforms.write(bytes);
			return bytes;
		}
		ByteBuffer bytes = ByteBuffer.allocate(customUniforms.size()).order(ByteOrder.LITTLE_ENDIAN);
		customUniforms.write(bytes);
		return bytes;
	}

	private static long[] upload(Rig rig, ByteBuffer bytes) {
		bytes.rewind();
		long buffer = MetalBridge.newBuffer(rig.ctx(), Math.max(16, bytes.remaining()));
		MemorySegment.ofAddress(MetalBridge.bufferContents(buffer)).reinterpret(bytes.remaining())
			.copyFrom(MemorySegment.ofBuffer(bytes));
		rig.toRelease().add(buffer);
		return new long[] {buffer, 0};
	}

	@Test
	void frameOrderWithAWorld() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			Rig rig = rig(gpu, true, true);
			try {
				Recorder ops = new Recorder(rig.passNames());
				// The phase setter writes into the same ordered log, so the phase entries sit where the phases begin.
				EngineFrame frame = frame(rig, worldGbuffers(ops.log, ops, new int[] {2}, new int[] {0}, new int[0]));
				EngineFrame.PassBlocks blocks = blocks(rig);
				EngineFrame.Main main = new EngineFrame.Main();

				frame.clearHook(rig.enc(), null, rig.targets(), blocks);
				// opaqueHook: the carried target copies first, then the opaque feedback target loads.
				frame.opaqueHook(rig.enc(), rig.targets());
				frame.phaseHook(rig.enc(), null, rig.targets(), blocks, () -> ops.log.add("hand"));
				frame.compositeHook(rig.enc(), null, rig.targets(), main, blocks);
				frame.close();

				// clearHook: clears, depthClears, no sky feedback, no prepare pass, endFrame.
				// phaseHook: depthtex2 copy; the opaque phase begins (split on its 2 attachments); the hand; depthtex1 copy;
				// flips of sky and opaque targets ({0,1,2}, ascending); the deferred passes (indexes 0..1); the flip of
				// translucent target 1, which no program of the phase samples (no load copy); the translucent phase begins
				// (split on its 1 attachment).
				// compositeHook: flip of the translucent target (the phase hook ran); composite passes 2..7; final; endFrame.
				// No colortex0 display: final ran.
				assertEquals(List.of(
					"clearImages", "clears", "depthClears", "endFrame",
					"carry:0", "load:2",
					"depthCopy:2", "phase:OPAQUE", "split:2", "hand", "depthCopy:1",
					"flip:0", "flip:1", "flip:2", "pass:deferred", "pass:deferred1", "flip:1", "phase:TRANSLUCENT", "split:1",
					"flip:1", "pass:composite", "pass:composite1", "pass:composite2", "pass:composite3", "pass:composite4",
					"pass:composite5", "pass:final", "endFrame"), ops.log, "the documented order of one world frame");
			} finally {
				rig.release();
			}
		}
	}

	@Test
	void aTranslucentTargetThePhaseSamplesLoadsInsteadOfFlipping() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			Rig rig = rig(gpu, false, false);
			try {
				Recorder ops = new Recorder(rig.passNames());
				EngineFrame frame = frame(rig, worldGbuffers(ops.log, ops, new int[0], new int[0], new int[] {1}));
				EngineFrame.PassBlocks blocks = blocks(rig);
				frame.phaseHook(rig.enc(), null, rig.targets(), blocks, null);
				frame.close();
				// The sampled target's write texture starts as a copy of the picture, and its read texture keeps the picture
				// the phase's programs sample: the load copy, no flip, between deferred and the translucent split.
				assertEquals(List.of(
					"flip:0", "flip:1", "flip:2", "pass:deferred", "pass:deferred1", "load:1", "phase:TRANSLUCENT", "split:1"),
					ops.log, "a sampled translucent target keeps the load copy");
			} finally {
				rig.release();
			}
		}
	}

	@Test
	void noWorldPathCopiesTheWorldThenRunsDeferredAndComposite() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			Rig rig = rig(gpu, false, false);
			try {
				List<String> phases = new ArrayList<>();
				Recorder ops = new Recorder(rig.passNames());
				EngineFrame.Gbuffers none = new EngineFrame.Gbuffers(new int[0], new int[0], new int[0], false,
					phase -> phases.add("phase:" + phase), ops);
				EngineFrame frame = frame(rig, none);
				EngineFrame.PassBlocks blocks = blocks(rig);
				frame.clearHook(rig.enc(), null, rig.targets(), blocks);
				frame.compositeHook(rig.enc(), null, rig.targets(), new EngineFrame.Main(), blocks);
				frame.close();
				// No takeover: no phase hook, no opaque hook, no depth copies. The world copy comes before deferred.
				assertEquals(List.of(
					"clearImages", "clears", "depthClears", "endFrame",
					"worldCopy", "pass:deferred", "pass:deferred1", "pass:composite", "pass:composite1", "pass:composite2",
					"pass:composite3", "pass:composite4", "pass:composite5", "pass:final", "endFrame"), ops.log);
				assertEquals(List.of(), phases, "no phase is entered without a world");
			} finally {
				rig.release();
			}
		}
	}

	@Test
	void opaqueHookCopiesCarriedTargetsBeforeFeedbackAndNothingWhenEmpty() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			Rig rig = rig(gpu, false, false);
			try {
				Recorder ops = new Recorder(rig.passNames());
				EngineFrame frame = frame(rig, worldGbuffers(new ArrayList<>(), ops, new int[] {2}, new int[] {0}, new int[0]));
				frame.opaqueHook(rig.enc(), rig.targets());
				assertEquals(List.of("carry:0", "load:2"), ops.log, "carried copies, then the opaque feedback loads");
				ops.log.clear();
				EngineFrame empty = frame(rig, worldGbuffers(new ArrayList<>(), ops, new int[0], new int[0], new int[0]));
				empty.opaqueHook(rig.enc(), rig.targets());
				assertEquals(List.of(), ops.log, "no carried or feedback target: no copy at all");
				frame.close();
				empty.close();
			} finally {
				rig.release();
			}
		}
	}

	@Test
	void aClearedSkyTargetNoPrepareWroteIsNotPrimed() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			Rig rig = rig(gpu, false, false);
			try {
				Recorder ops = new Recorder(rig.passNames());
				// The clear refreshes colortex1 on both sides, and this pack's prepare passes do not exist: no load base is
				// copied (the clear already gives the sky its base; the primes are the targets the clear leaves unrefreshed).
				EngineFrame frame = frame(rig, new EngineFrame.Gbuffers(new int[] {1}, new int[0], new int[0], true,
					phase -> { }, ops, new int[0], new int[0], new int[0]));
				frame.clearHook(rig.enc(), null, rig.targets(), blocks(rig));
				frame.close();
				assertEquals(List.of("clearImages", "clears", "depthClears", "endFrame"), ops.log,
					"a cleared target that no prepare pass wrote needs no load base");
			} finally {
				rig.release();
			}
		}
	}

	@Test
	void depthCopiesOnTheGpuCarryTheLiveDepth() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			Rig rig = rig(gpu, true, true);
			try {
				Targets targets = rig.targets();
				long enc = rig.enc();
				EngineFrame frame = frame(rig, new EngineFrame.Gbuffers(new int[0], new int[] {0}, new int[0], true,
					phase -> { }, null));
				EngineFrame.PassBlocks blocks = blocks(rig);
				frame.clearHook(enc, null, targets, blocks);  // every depth target to 1.0
				MetalBridge.commitAndWait(enc);
				// The live depth to 0.25: a depth-only clear of depthtex0, the same call shape the frame's depth clear uses.
				MetalBridge.renderBegin(enc, new long[0], new float[0][], targets.depth(0), true, 0.25f, W, H);
				MetalBridge.endEncoders(enc);
				MetalBridge.commitAndWait(enc);
				// The phase hook copies depthtex2 and depthtex1 from the live depth (no hand: null).
				frame.phaseHook(enc, null, targets, blocks, null);
				frame.close();
				MetalBridge.endEncoders(enc);
				MetalBridge.commitAndWait(enc);
				assertEquals(0.25f, firstDepth(gpu, targets.depth(1)), 1e-6f, "depthtex1 is a copy of the live depth");
				assertEquals(0.25f, firstDepth(gpu, targets.depth(2)), 1e-6f, "depthtex2 is a copy of the live depth");
			} finally {
				rig.release();
			}
		}
	}

	@Test
	void carriedTargetReachesTheReadTextureOnTheGpu() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			Rig rig = rig(gpu, false, false);
			try {
				Targets targets = rig.targets();
				long enc = rig.enc();
				// The sky's picture: a colour clear of the write texture of target 0 only.
				MetalBridge.renderBegin(enc, new long[] {targets.write(0)}, new float[][] {CLEAR}, 0, false, 0, W, H);
				MetalBridge.endEncoders(enc);
				MetalBridge.commitAndWait(enc);
				float before = firstRed(gpu, targets.read(0), targets.format(0));
				assertTrue(Math.abs(before - CLEAR[0]) > 0.01f,
					"before the carry the read texture does not hold the sky's picture (red " + before + ")");
				EngineFrame frame = frame(rig, new EngineFrame.Gbuffers(new int[0], new int[0], new int[0], true,
					phase -> { }, null, new int[0], new int[] {0}, new int[0]));
				frame.opaqueHook(enc, targets);
				frame.close();
				MetalBridge.endEncoders(enc);
				MetalBridge.commitAndWait(enc);
				assertEquals(CLEAR[0], firstRed(gpu, targets.read(0), targets.format(0)), 0.01f,
					"the carry copies the sky's write texture into the read texture");
			} finally {
				rig.release();
			}
		}
	}

	/** The first texel's depth of a depth texture (read back through the headless device). */
	private static float firstDepth(HeadlessGpu gpu, long texture) {
		byte[] bytes = gpu.readTexture(texture, 0, 0, W, H, 1, HeadlessGpu.bytesPerPixel(DEPTH32F));
		return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getFloat(0);
	}

	/** The first texel's red channel of a colour target: RGBA8 (unorm), RGBA16F (half) or R32F (float). */
	private static float firstRed(HeadlessGpu gpu, long texture, int format) {
		byte[] bytes = gpu.readTexture(texture, 0, 0, W, H, 1, HeadlessGpu.bytesPerPixel(format));
		ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		if (format == RGBA8) {
			return (bytes[0] & 0xff) / 255f;
		}
		if (format == HeadlessGpu.RGBA16_FLOAT) {
			return Float.float16ToFloat(buffer.getShort(0));
		}
		return buffer.getFloat(0);
	}
}
