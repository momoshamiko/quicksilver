// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.runtime.DimensionSet;
import dev.mcms.shaders.runtime.Targets;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import dev.mcms.shaders.runtime.uniforms.FrameUniforms;
import java.lang.management.ManagementFactory;
import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The horizon draw through the delegate (plan.md E4, the horizon): the sky pipeline maps to the pack's
 * {@code gbuffers_skybasic} in the sky phase, so the directions below the horizon (and only those) come out of that
 * program: its colour is the fog colour (the horizon's {@code gl_Color}) and its class marker is 1, the value the
 * classes pack writes from {@code gbuffers_skybasic}. Each pixel's expectation comes from the camera's own ray through
 * that pixel, not from the fan's formula. The steady-state draws allocate nothing (the frame path's rule).
 */
@Tag("gpu")
class HorizonGpuTest {

	private static final int W = 64, H = 48;
	private static final float FOV = 70f, FAR = 192f;
	/** The fog colour of the frame ({@link GbufferFixtures#inputs}: 0.75, 0.84, 0.95), in 8-bit channels. */
	private static final int FOG_R = 191, FOG_G = 214, FOG_B = 242;
	private static final int CLASS_SKYBASIC = 1;
	/**
	 * The allocation window: a warm-up and a settling run pay the one-off costs (JIT, class loading), then 50000 draws are
	 * measured. ThreadMXBean counts whole thread-buffer refills (EngineFrameAllocationGpuTest's note: a refill is a few
	 * hundred KB), so a short window reads refills, not draws; over 50000 draws the bound is about 20 bytes per draw.
	 */
	private static final int WARM_UP = 100, SETTLE = 2000, MEASURED = 50_000;
	private static final long TOTAL_BYTES_LIMIT = 1024L * 1024;
	private static PackModel model;
	private static CustomUniforms custom;

	@BeforeAll
	static void loadPack() throws Exception {
		model = GbufferFixtures.classesModel();
		custom = CustomUniforms.of(model);
	}

	@Test
	void theHorizonPaintsExactlyTheDirectionsBelowTheHorizonWithTheSkybasicProgramAtLevel() {
		assertCoverage(new Matrix4f());
	}

	@Test
	void theHorizonPaintsExactlyTheDirectionsBelowTheHorizonWithTheCameraLookingDown() {
		// A view rotated 35 degrees about x: the camera looks down, so the horizon crosses the picture diagonally
		// instead of at its middle row.
		assertCoverage(new Matrix4f().rotationX((float) Math.toRadians(35)));
	}

	@Test
	void aSteadyStateHorizonDrawAllocatesNothing() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			GbufferFixtures.Owned owned = new GbufferFixtures.Owned();
			Scene scene = Scene.build(gpu, owned, new Matrix4f().rotationX((float) Math.toRadians(35)));
			ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
			long thread = Thread.currentThread().threadId();
			assertTrue(threads.isThreadAllocatedMemoryEnabled(), "the JVM must count thread allocations");
			long measured = 0;
			try {
				for (int frame = 0; frame < WARM_UP + SETTLE + MEASURED; frame++) {
					long before = threads.getThreadAllocatedBytes(thread);
					scene.horizon.draw(scene.enc, scene.delegate, scene.targets, scene.sky);
					long after = threads.getThreadAllocatedBytes(thread);
					if (frame >= WARM_UP + SETTLE) {
						measured += after - before;
					}
					MetalBridge.endEncoders(scene.enc);
					MetalBridge.commitAndWait(scene.enc);
				}
			} finally {
				scene.close();
				owned.release();
			}
			assertTrue(measured <= TOTAL_BYTES_LIMIT, "the steady-state horizon draws allocated " + measured
				+ " bytes on the heap over " + MEASURED + " draws (limit " + TOTAL_BYTES_LIMIT + ")");
		}
	}

	/**
	 * A pack without a sky program (mcms-colors has no gbuffers program at all) draws the horizon nothing: the game's
	 * sky pipeline is not replaced, the picture stays as it was, and no WARN is logged (E4 review item 6).
	 */
	@Test
	void aPackWithoutASkyProgramDrawsNothingAndLogsNothing() throws Exception {
		PackModel targets = GbufferFixtures.testPack("mcms-colors");
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			GbufferFixtures.Owned owned = new GbufferFixtures.Owned();
			Scene scene = Scene.over(gpu, owned, new Matrix4f().rotationX((float) Math.toRadians(35)), targets,
				CustomUniforms.of(targets), "colors");
			try {
				GbufferPlans.Plan sky = scene.plans().planOf(Horizon.PIPELINE);
				assertNotNull(sky, Horizon.PIPELINE + " must have a plan");
				assertFalse(sky.variant(PassKind.GBUFFERS, GbufferPhase.SKY) instanceof GbufferPlans.PackVariant,
					"the premise: this pack has no sky program");
				byte[] before = gpu.readTexture(scene.targets().write(0), 0, 0, W, H, 1, 4);
				scene.horizon().draw(scene.enc(), scene.delegate(), scene.targets(), scene.sky());
				MetalBridge.endEncoders(scene.enc());
				MetalBridge.commitAndWait(scene.enc());
				assertArrayEquals(before, gpu.readTexture(scene.targets().write(0), 0, 0, W, H, 1, 4),
					"the horizon paints nothing");
				assertTrue(scene.delegate().warnings().isEmpty(),
					"a pack without a sky program logs no WARN: " + scene.delegate().warnings());
			} finally {
				scene.close();
				owned.release();
			}
		}
	}

	/** Draws the horizon through the delegate and checks every pixel against the camera's ray through it. */
	private static void assertCoverage(Matrix4f viewRotation) {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			GbufferFixtures.Owned owned = new GbufferFixtures.Owned();
			Scene scene = Scene.build(gpu, owned, viewRotation);
			try {
				scene.horizon.draw(scene.enc, scene.delegate, scene.targets, scene.sky);
				MetalBridge.endEncoders(scene.enc);
				MetalBridge.commitAndWait(scene.enc);
				byte[] main = gpu.readTexture(scene.targets.write(0), 0, 0, W, H, 1, 4);
				byte[] classes = gpu.readTexture(scene.targets.write(4), 0, 0, W, H, 1, 1);
				Expectation expect = expectations(viewRotation);
				assertTrue(expect.below > W * H / 3, "the frame must put a third of its pixels below the horizon");
				for (int row = 0; row < H; row++) {
					for (int col = 0; col < W; col++) {
						double elevation = expect.elevation[row * W + col];
						int at = (row * W + col) * 4;
						String where = "pixel (" + col + "," + row + ") at elevation " + elevation;
						// Covered up to the sky disc's edge (atan(16/512) ≈ 1.79°): the fan's rim rises past it, and the
						// band between 1.5 and 2.6 degrees is the overlap zone (no assertion there).
						if (elevation < 1.5) {
							near(FOG_R, main[at] & 0xff, "the horizon's red at " + where);
							near(FOG_G, main[at + 1] & 0xff, "the horizon's green at " + where);
							near(FOG_B, main[at + 2] & 0xff, "the horizon's blue at " + where);
							assertEquals(255, main[at + 3] & 0xff, "the horizon's alpha at " + where);
							assertEquals(CLASS_SKYBASIC, classes[row * W + col] & 0xff,
								"gbuffers_skybasic's class marker at " + where);
						} else if (elevation > 2.6) {
							assertEquals(0, pixel(main, col, row), "nothing above the horizon at " + where);
							assertEquals(0, classes[row * W + col] & 0xff, "no class above the horizon at " + where);
						}
					}
				}
			} finally {
				scene.close();
				owned.release();
			}
		}
	}

	/** The pass's targets, delegate, horizon and encoder for one frame capture (the caller closes it, then the owned handles). */
	private record Scene(long enc, Targets targets, GbufferDelegate delegate, Horizon horizon, PhaseTargets sky, GbufferPlans plans) {

		/** The classes pack's scene; its sky must draw through the pack's gbuffers_skybasic. */
		static Scene build(HeadlessGpu gpu, GbufferFixtures.Owned owned, Matrix4f viewRotation) {
			Scene scene = over(gpu, owned, viewRotation, model, custom, "classes");
			GbufferPlans.Plan plan = scene.plans().planOf(Horizon.PIPELINE);
			assertNotNull(plan, Horizon.PIPELINE + " must have a plan");
			GbufferPlans.Variant variant = plan.variant(PassKind.GBUFFERS, GbufferPhase.SKY);
			assertTrue(variant instanceof GbufferPlans.PackVariant pv && pv.program().equals("gbuffers_skybasic"),
				Horizon.PIPELINE + " must draw through the pack's gbuffers_skybasic in the sky phase, not "
					+ plan.reason(PassKind.GBUFFERS, GbufferPhase.SKY));
			return scene;
		}

		/** A scene over any pack: its set's targets, the delegate over them, and the horizon. */
		static Scene over(HeadlessGpu gpu, GbufferFixtures.Owned owned, Matrix4f viewRotation, PackModel pack,
				CustomUniforms customs, String name) {
			long ctx = gpu.context();
			DimensionSet set = DimensionSet.load(ctx, name, pack, pack.rootFolder(), customs, GbufferFixtures.RGBA8,
				pack.rootFolder().union().colorFormats(), GbufferFixtures.gamePipelines(), copy -> null);
			GbufferPlans plans = set.plans();
			assertNotNull(plans, "the pack's gbuffers plans must build");
			PhaseTargets sky = PhaseTargets.single(plans.layout(GbufferPhase.SKY));
			Targets targets = new Targets(set.dimension().union(), pack.properties().bufferSizes(),
				set.usedTargets(), false, false, W, H, Targets.engineTextures(ctx), null);
			assertTrue(targets.create());
			long noise = ShaderNative.textureNew(ctx, GbufferFixtures.RGBA8, ShaderNative.TYPE_2D, 16, 16, 1, 1,
				ShaderNative.USAGE_SHADER_READ, ShaderNative.STORAGE_PRIVATE);
			owned.add(noise);
			FrameUniforms uniforms = new FrameUniforms();
			uniforms.update(GbufferFixtures.inputs(W, H, viewRotation, FOV, FAR));
			long[] frameBlock = GbufferFixtures.upload(ctx, GbufferFixtures.block(uniforms), owned);
			GbufferDelegate delegate = new GbufferDelegate(plans,
				new GbufferFixtures.Bindings(targets, noise, frameBlock, uniforms));
			Horizon horizon = new Horizon(ctx, GbufferFixtures.gamePipelines(), sky.groups().length, uniforms);
			return new Scene(gpu.encoder(), targets, delegate, horizon, sky, plans);
		}

		void close() {
			this.horizon.close();
			this.delegate.close();
			this.targets.release();
		}
	}

	/** Per-pixel elevation (degrees, read in the picture's own rows: row 0 is the bottom) and the count below the horizon. */
	private record Expectation(double[] elevation, int below) {
	}

	/**
	 * The elevation of each pixel's ray in world space: the view ray (from the field of view and the aspect) rotated back
	 * by the view rotation's transpose, then its vertical component.
	 */
	private static Expectation expectations(Matrix4f viewRotation) {
		Matrix4f toWorld = new Matrix4f(viewRotation).transpose();
		double tanY = Math.tan(Math.toRadians(FOV) / 2), aspect = (double) W / H;
		double[] elevation = new double[W * H];
		int below = 0;
		for (int row = 0; row < H; row++) {
			for (int col = 0; col < W; col++) {
				double x = ((col + 0.5) / W) * 2 - 1, y = ((row + 0.5) / H) * 2 - 1;
				double dx = x * tanY * aspect, dy = y * tanY, dz = -1;
				double wx = toWorld.m00() * dx + toWorld.m10() * dy + toWorld.m20() * dz;
				double wy = toWorld.m01() * dx + toWorld.m11() * dy + toWorld.m21() * dz;
				double wz = toWorld.m02() * dx + toWorld.m12() * dy + toWorld.m22() * dz;
				double length = Math.sqrt(wx * wx + wy * wy + wz * wz);
				double degrees = Math.toDegrees(Math.asin(wy / length));
				elevation[row * W + col] = degrees;
				if (degrees < 1.5) {
					below++;
				}
			}
		}
		return new Expectation(elevation, below);
	}

	private static int pixel(byte[] rgba, int x, int y) {
		int at = (y * W + x) * 4;
		return ((rgba[at] & 0xff) << 16) | ((rgba[at + 1] & 0xff) << 8) | (rgba[at + 2] & 0xff)
			| ((rgba[at + 3] & 0xff) << 24);
	}

	/** A channel within one 8-bit step of the fog colour (the unorm conversion's rounding). */
	private static void near(int expected, int actual, String what) {
		assertTrue(Math.abs(expected - actual) <= 1, what + ": expected " + expected + ", got " + actual);
	}
}
