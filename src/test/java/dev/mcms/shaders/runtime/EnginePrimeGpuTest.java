// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
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
 * The sky and opaque load bases (E4 review item 3, plan.md E4's frame-order bullet). The pack {@code mcms-prepare} has one
 * prepare pass: it writes colortex0 (flipped to the read side) and colortex1 (not cleared). Before the fix the sky's
 * write textures held the picture from before the prepare pass, and the carry copy of colortex0 overwrote the prepare
 * result with it. The order is checked with a recording backend; the carry is read back from the device.
 */
@Tag("gpu")
class EnginePrimeGpuTest {

	private static final int W = 64, H = 48;
	private static final int RGBA8 = 70;
	private static PackModel model;
	private static CustomUniforms custom;

	@BeforeAll
	static void loadPack() throws Exception {
		Path dir = Path.of(System.getProperty("user.dir"));
		Path pack = null;
		for (int i = 0; i < 6 && pack == null; i++) {
			Path candidate = dir.resolve(Path.of("bench", "test-packs", "mcms-prepare"));
			if (java.nio.file.Files.isDirectory(candidate)) {
				pack = candidate;
			}
			dir = dir.getParent();
		}
		assertTrue(pack != null, "bench/test-packs/mcms-prepare not found above " + System.getProperty("user.dir"));
		Properties options = new Properties();
		try (PackSource source = PackSource.open(pack)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", options);
		}
		custom = CustomUniforms.of(model);
	}

	/** Records every backend step in order (the phase setter logs too). */
	private static final class Recorder implements EngineFrame.HookOps {
		final List<String> log = new ArrayList<>();
		private final List<String> passNames;

		Recorder(List<String> passNames) {
			this.passNames = passNames;
		}

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
		@Override public void setup(long enc, Targets targets, EngineFrame.PassBlocks blocks, int setupIndex) {
			this.log.add("setup:" + setupIndex);
		}
		@Override public void begin(long enc, Targets targets, EngineFrame.PassBlocks blocks, int planIndex) {
			this.log.add("begin:" + this.passNames.get(planIndex));
		}
		@Override public void clearImages(long enc, Targets targets) {
			this.log.add("clearImages");
		}
		@Override public void runPass(long enc, @Nullable Object encoder, Targets targets, EngineFrame.@Nullable Main main,
				EngineFrame.PassBlocks blocks, int planIndex) {
			this.log.add("pass:" + this.passNames.get(planIndex));
		}
		@Override public void endFrame(long enc) { this.log.add("endFrame"); }
	}

	/** The set, its targets and the noise texture of the prepare pack on the headless device. */
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

	private static Rig rig(HeadlessGpu gpu) {
		long ctx = gpu.context();
		DimensionSet set = DimensionSet.load(ctx, "mcms-prepare", model, model.rootFolder(), custom, RGBA8,
			model.rootFolder().union().colorFormats(), List.of(), Engine.COPIES_NOT_COMPILED);
		Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(), set.usedTargets(),
			false, false, W, H, Targets.engineTextures(ctx), null);
		assertTrue(targets.create(), "the device must create the targets");
		return new Rig(gpu, ctx, gpu.encoder(), set, targets, Engine.makeNoise(ctx, set.noiseResolution()),
			new ArrayList<>());
	}

	/** A world frame's gbuffers: sky and opaque both draw colortex0 and colortex1; carry and feedback as given. */
	private static EngineFrame.Gbuffers world(EngineFrame.HookOps ops, int[] carriedOpaque, List<String> phases) {
		return new EngineFrame.Gbuffers(new int[] {0, 1}, new int[] {0, 1}, new int[0], true,
			phase -> phases.add("phase:" + phase), ops, new int[0], carriedOpaque, new int[0]);
	}

	private static EngineFrame frame(Rig rig, EngineFrame.Gbuffers gbuffers) {
		return new EngineFrame(rig.set(), rig.targets(), null, rig.noise(), 0, 0, (bytes, out) -> {
			long[] slice = upload(rig, bytes);
			out[0] = slice[0];
			out[1] = slice[1];
		}, Map.of(), Map.of(), gbuffers);
	}

	private static EngineFrame.PassBlocks blocks(Rig rig) {
		long[] frame = upload(rig, frameBlock());
		long[] customBlock = custom != null && custom.size() > 0 ? upload(rig, customBlock()) : null;
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

	private static ByteBuffer frameBlock() {
		ByteBuffer bytes = ByteBuffer.allocate(FrameUniforms.size()).order(ByteOrder.LITTLE_ENDIAN);
		new FrameUniforms().write(bytes);
		return bytes;
	}

	private static ByteBuffer customBlock() {
		ByteBuffer bytes = ByteBuffer.allocate(custom.size()).order(ByteOrder.LITTLE_ENDIAN);
		custom.write(bytes);
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
	void theLoadBasesCopyAfterThePrepareWrites() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			Rig rig = rig(gpu);
			try {
				List<String> phases = new ArrayList<>();
				Recorder ops = new Recorder(rig.passNames());
				EngineFrame frame = frame(rig, world(ops, new int[0], phases));
				frame.clearHook(rig.enc(), null, rig.targets(), blocks(rig));
				frame.close();
				// The clears refresh both targets; the prepare pass then writes both (flipping colortex0 and colortex1). The
				// sky and opaque layouts hold both targets, so each copies its read texture into its write texture after the
				// prepare pass: the sky's load base is the picture the prepare left.
				assertEquals(List.of("clearImages", "clears", "depthClears", "pass:prepare", "load:0", "load:1", "endFrame"),
					ops.log);
			} finally {
				rig.release();
			}
		}
	}

	@Test
	void theCarryKeepsThePreparePictureOnTheGpu() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			Rig rig = rig(gpu);
			try {
				Targets targets = rig.targets();
				long enc = rig.enc();
				List<String> phases = new ArrayList<>();
				EngineFrame frame = frame(rig, world(null, new int[] {0}, phases));
				// The clear hook: the clears, then the prepare pass writes colortex0 0.25 and flips it to the read side.
				frame.clearHook(enc, null, targets, blocks(rig));
				// The opaque hook carries the sky's picture of colortex0 into its read texture: the write texture must hold the
				// prepare picture by then, or the carry copies the cleared texture over it.
				frame.opaqueHook(enc, targets);
				frame.close();
				MetalBridge.endEncoders(enc);
				MetalBridge.commitAndWait(enc);
				assertEquals(0.25f, firstRed(rig.gpu(), targets.read(0)), 0.01f,
					"the read texture keeps the prepare pass's picture after the sky's carry");
			} finally {
				rig.release();
			}
		}
	}

	/** The first texel's red channel of an RGBA8 colour target (read back through the headless device). */
	private static float firstRed(HeadlessGpu gpu, long texture) {
		byte[] bytes = gpu.readTexture(texture, 0, 0, W, H, 1, HeadlessGpu.bytesPerPixel(RGBA8));
		return (bytes[0] & 0xff) / 255f;
	}
}
