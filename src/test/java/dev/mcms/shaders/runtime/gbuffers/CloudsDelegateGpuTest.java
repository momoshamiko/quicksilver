// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout.UniformDescription;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.DimensionSet;
import dev.mcms.shaders.runtime.Targets;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import dev.mcms.shaders.runtime.uniforms.FrameInputs;
import dev.mcms.shaders.runtime.uniforms.FrameUniforms;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.system.MemoryUtil;

/**
 * The clouds draw through the delegate (plan.md E4): the game's clouds pipelines bind no vertex format, so before E4
 * they drew as game variants and a pack's {@code gbuffers_clouds} never ran (the mcms-passes preview's clouds overdraw
 * the cell area). The plans must build them as pack programs through the clouds prologue, and the delegate must bind
 * the game's own {@code CloudFaces} texel buffer and {@code CloudInfo} block from the draw's uniform bindings. One
 * quad drawn through the delegate must land where the game's own pulling puts it with the face's colour:
 * {@code mcms-classes}' {@code gbuffers_clouds} writes that colour to colortex0 and its class 3 to colortex4.
 */
@Tag("gpu")
class CloudsDelegateGpuTest {

	private static final int RGBA8 = 70;
	private static final int W = 64, H = 48;
	private static final float[] CLOUD_COLOR = {0.8f, 0.9f, 1.0f, 1.0f};
	private static final float[] CLOUD_OFFSET = {0f, 0f, 0f};
	private static final float[] CELL_SIZE = {0.5f, 0.5f, 0.5f};
	private static PackModel model;
	private static CustomUniforms custom;

	@BeforeAll
	static void loadPack() throws Exception {
		Path dir = Path.of(System.getProperty("user.dir"));
		Path pack = null;
		for (int i = 0; i < 6 && pack == null; i++) {
			Path candidate = dir.resolve(Path.of("bench", "test-packs", "mcms-classes"));
			if (Files.isDirectory(candidate)) {
				pack = candidate;
			}
			dir = dir.getParent();
		}
		assertTrue(pack != null, "bench/test-packs/mcms-classes not found above " + System.getProperty("user.dir"));
		try (PackSource source = PackSource.open(pack)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		assertTrue(model.problems().isEmpty(), "the test pack must load clean: " + model.problems());
		custom = CustomUniforms.of(model);
	}

	@Test
	void theCloudsDrawThroughTheDelegateWithTheGamesOwnBindings() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context(), enc = gpu.encoder();
			TestOwned toRelease = new TestOwned();
			DimensionSet set = DimensionSet.load(ctx, "classes", model, model.rootFolder(), custom, RGBA8,
				model.rootFolder().union().colorFormats(), gamePipelines(), copy -> null);
			try {
				GbufferPlans plans = set.plans();
				assertNotNull(plans, "the pack's gbuffers plans must build");
				String pipeline = "minecraft:pipeline/clouds";
				GbufferPlans.Plan plan = plans.planOf(pipeline);
				assertNotNull(plan, pipeline + " must have a plan");
				GbufferPlans.Variant variant = plan.variant(PassKind.GBUFFERS, GbufferPhase.TRANSLUCENT);
			assertTrue(variant instanceof GbufferPlans.PackVariant pv && pv.program().equals("gbuffers_clouds"),
					pipeline + " must draw through the pack's gbuffers_clouds in the translucent phase, not "
						+ plan.reason(PassKind.GBUFFERS, GbufferPhase.TRANSLUCENT));

				Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(),
					set.usedTargets(), false, false, W, H, Targets.engineTextures(ctx), null);
				assertTrue(targets.create());
			long noise = ShaderNative.textureNew(ctx, RGBA8, ShaderNative.TYPE_2D, 16, 16, 1, 1,
				ShaderNative.USAGE_SHADER_READ, ShaderNative.STORAGE_PRIVATE);
				toRelease.add(noise);
				FrameUniforms uniforms = new FrameUniforms();
				uniforms.update(inputs());
				long[] frameBlock = upload(ctx, block(uniforms), toRelease);

				GbufferDelegate delegate = new GbufferDelegate(plans, new TestBindings(targets, noise, frameBlock));
				long[] colours = new long[plans.layout(GbufferPhase.TRANSLUCENT).length];
				for (int i = 0; i < colours.length; i++) {
					colours[i] = targets.write(plans.layout(GbufferPhase.TRANSLUCENT)[i]);
				}
				MetalBridge.renderBegin(enc, colours, null, 0, false, 0, W, H);
				delegate.begin(enc, false);
				delegate.phase(GbufferPhase.TRANSLUCENT);
				List<UniformDescription> gameUniforms = gamePipeline(pipeline).getBindGroupLayouts().stream()
					.flatMap(group -> group.uniforms().stream()).toList();
				int[] slots = delegate.select(enc, pipeline, null, null, gameUniforms);
				assertNotNull(slots, pipeline + " must not be dropped in the translucent phase");
				// The game's own draw bindings (CloudRenderer.render, javap 2026-11-14): the CloudInfo block and the
				// CloudFaces texel buffer, both by name, as whole GpuBuffers (setUniform(String, GpuBuffer)), not slices.
				delegate.uniform(enc, "CloudInfo", cloudInfo(ctx, toRelease));
				delegate.uniform(enc, "CloudFaces", cloudFaces(ctx, toRelease));
				assertTrue(delegate.beforeDraw(enc), "the clouds draw must run");
				MetalBridge.renderDraw(enc, 3, 1, 0, 0);
				MetalBridge.endEncoders(enc);
				MetalBridge.commitAndWait(enc);
				delegate.close();

				// The triangle the prologue pulls: a north face (dir 2) with the inside flag, whose reversed corner
				// order (1,0),(1,1),(0,1) in x,y is counter-clockwise in GL's convention, at cell 0,0 with CellSize
				// 0.5: clip x 0..0.5 (cols W/2..W), y 0..0.5 (rows H/2..H*3/4); memory row 0 is clip y -1. The north
				// face's grey is 0.8, so the colour is 0.8 * CloudColor; colortex4 gets the program's class 3.
				byte[] main = gpu.readTexture(targets.write(0), 0, 0, W, H, 1, 4);
				int inside = pixel(main, 42, 32);  // the triangle's centroid (clip 1/3, 1/3)
				assertEquals(Math.round(0.8f * CLOUD_COLOR[0] * 255), (inside >> 16) & 0xff, "the cloud colour's red");
				assertEquals(Math.round(0.8f * CLOUD_COLOR[1] * 255), (inside >> 8) & 0xff, "the cloud colour's green");
				assertEquals(Math.round(0.8f * CLOUD_COLOR[2] * 255), inside & 0xff, "the cloud colour's blue");
				assertEquals(255, (inside >> 24) & 0xff, "the cloud colour's alpha");
				assertEquals(0, pixel(main, 4, 4), "a pixel outside the triangle stays untouched");
				byte[] classes = gpu.readTexture(targets.write(4), 0, 0, W, H, 1, 1);
				assertEquals(3, classes[32 * W + 42] & 0xff, "gbuffers_clouds' class in colortex4");
				assertEquals(0, classes[4 * W + 4] & 0xff, "colortex4 outside the triangle stays untouched");

				targets.release();
			} finally {
				toRelease.release();
			}
		}
	}

	/**
	 * A non-clouds draw after a clouds draw binds no cloud buffers (E4 review item 5): the game sets CloudInfo and CloudFaces
	 * per clouds pass, so the delegate binds them for the clouds programs alone. The draw log names every engine buffer a
	 * draw binds: the clouds draw's line names both (the positive control), and the translucent terrain draw that follows it
	 * in the same pass names neither.
	 */
	@Test
	void aNonCloudsDrawAfterACloudsDrawBindsNoCloudBuffers(@TempDir Path dir) throws IOException {
		Path log = dir.resolve("mcms-drawlog.txt");
		DrawLog.reset();
		DrawLog.file(log);
		System.setProperty(DrawLog.PROPERTY, "1:clouds");
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context(), enc = gpu.encoder();
			TestOwned toRelease = new TestOwned();
			DimensionSet set = DimensionSet.load(ctx, "classes", model, model.rootFolder(), custom, RGBA8,
				model.rootFolder().union().colorFormats(), gamePipelines(), copy -> null);
			try {
				GbufferPlans plans = set.plans();
				assertNotNull(plans, "the pack's gbuffers plans must build");
				Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(),
					set.usedTargets(), false, false, W, H, Targets.engineTextures(ctx), null);
				assertTrue(targets.create());
				long noise = ShaderNative.textureNew(ctx, RGBA8, ShaderNative.TYPE_2D, 16, 16, 1, 1,
					ShaderNative.USAGE_SHADER_READ, ShaderNative.STORAGE_PRIVATE);
				toRelease.add(noise);
				FrameUniforms uniforms = new FrameUniforms();
				uniforms.update(inputs());
				long[] frameBlock = upload(ctx, block(uniforms), toRelease);
				GbufferDelegate delegate = new GbufferDelegate(plans, new TestBindings(targets, noise, frameBlock));
				String clouds = "minecraft:pipeline/clouds", terrain = "minecraft:pipeline/translucent_terrain";

				DrawLog.frame(true);  // the block starts: the draws below are logged
				beginPass(enc, delegate, plans, targets, GbufferPhase.TRANSLUCENT);
				assertNotNull(delegate.select(enc, clouds, null, null, gameUniforms(clouds)), clouds + " must not be dropped");
				delegate.uniform(enc, "CloudInfo", cloudInfo(ctx, toRelease));
				delegate.uniform(enc, "CloudFaces", cloudFaces(ctx, toRelease));
				assertTrue(delegate.beforeDraw(enc), "the clouds draw must run");
				MetalBridge.renderDraw(enc, 3, 1, 0, 0);
				// The same pass, after the clouds draw: the pass's game bindings stand, and the terrain draw must take none
				// of them (the clouds' bindings are for the clouds programs alone).
				assertNotNull(delegate.select(enc, terrain, null, null, gameUniforms(terrain)), terrain + " must not be dropped");
				assertTrue(delegate.beforeDraw(enc), "the terrain draw must run");
				delegate.end(enc);
				MetalBridge.endEncoders(enc);
				MetalBridge.commitAndWait(enc);
				DrawLog.frame(true);  // the block ends and the log is written

				String text = Files.readString(log);
				String cloudsLine = drawLine(text, clouds), terrainLine = drawLine(text, terrain);
				assertTrue(cloudsLine.contains("22:h=") && cloudsLine.contains("30:h="),
					"the clouds draw binds both cloud buffers (the positive control): " + cloudsLine);
				assertTrue(terrainLine.contains("variant=pack:"), "the terrain draw runs a pack program: " + terrainLine);
				assertFalse(terrainLine.contains("22:h=") || terrainLine.contains("30:h="),
					"the terrain draw after the clouds binds no cloud buffer: " + terrainLine);
			} finally {
				toRelease.release();
			}
		} finally {
			System.clearProperty(DrawLog.PROPERTY);
			DrawLog.reset();
		}
	}

	/** Opens a pass of {@code phase} on {@code enc} with the phase's colour layout, and tells the delegate the phase. */
	private static void beginPass(long enc, GbufferDelegate delegate, GbufferPlans plans, Targets targets, GbufferPhase phase) {
		int[] layout = plans.layout(phase);
		long[] colours = new long[layout.length];
		for (int i = 0; i < colours.length; i++) {
			colours[i] = targets.write(layout[i]);
		}
		MetalBridge.renderBegin(enc, colours, null, 0, false, 0, W, H);
		delegate.begin(enc, false);
		delegate.phase(phase);
	}

	private static List<UniformDescription> gameUniforms(String pipeline) {
		return gamePipeline(pipeline).getBindGroupLayouts().stream().flatMap(group -> group.uniforms().stream()).toList();
	}

	/** The first DRAW line of the pipeline in the log text. */
	private static String drawLine(String text, String pipeline) {
		return text.lines().filter(line -> line.startsWith("DRAW ") && line.contains(" pipe=" + pipeline + " "))
			.findFirst().orElseThrow(() -> new AssertionError("no DRAW line for " + pipeline + " in:\n" + text));
	}

	private static int pixel(byte[] rgba, int x, int y) {
		int at = (y * W + x) * 4;
		return ((rgba[at] & 0xff) << 16) | ((rgba[at + 1] & 0xff) << 8) | (rgba[at + 2] & 0xff)
			| ((rgba[at + 3] & 0xff) << 24);
	}

	/** The game's own pipeline list the engine maps at install (Engine.gamePipelines). */
	private static List<RenderPipeline> gamePipelines() {
		List<RenderPipeline> all = new ArrayList<>(net.minecraft.client.renderer.RenderPipelines.requiredPipelines());
		all.addAll(net.minecraft.client.renderer.RenderPipelines.optionalPipelines());
		return all;
	}

	private static RenderPipeline gamePipeline(String name) {
		for (RenderPipeline pipeline : gamePipelines()) {
			if (pipeline.getLocation().toString().equals(name)) {
				return pipeline;
			}
		}
		throw new IllegalStateException("no game pipeline " + name);
	}

	/** The game's CloudInfo block (std140: vec4 at 0, vec3 at 16, vec3 at 32) in a shared buffer. */
	private static GpuBuffer cloudInfo(long ctx, TestOwned toRelease) {
		GpuBuffer buffer = MetalBridge.newGpuBuffer(ctx, 48);
		ByteBuffer bytes = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
		for (float v : CLOUD_COLOR) {
			bytes.putFloat(v);
		}
		for (int i = 1; i <= 2; i++) {
			for (float v : i == 1 ? CLOUD_OFFSET : CELL_SIZE) {
				bytes.putFloat(v);
			}
			bytes.putFloat(0f);  // std140 vec3 padding
		}
		fill(buffer, bytes.array());
		toRelease.add(MetalBridge.bufferHandle(buffer));
		return buffer;
	}

	/**
	 * One quad of CloudFaces as the game packs it (encodeFace): three bytes — cellX >> 1 = 0, cellZ >> 1 = 0,
	 * direction 2 (the north face) with the inside flag.
	 */
	private static GpuBuffer cloudFaces(long ctx, TestOwned toRelease) {
		GpuBuffer buffer = MetalBridge.newGpuBuffer(ctx, 3);
		ByteBuffer bytes = ByteBuffer.allocate(3);
		bytes.put((byte) 0).put((byte) 0).put((byte) (2 | (1 << 4)));
		fill(buffer, bytes.array());
		toRelease.add(MetalBridge.bufferHandle(buffer));
		return buffer;
	}

	private static void fill(GpuBuffer buffer, byte[] data) {
		MemorySegment.ofAddress(MetalBridge.bufferAddress(buffer)).reinterpret(data.length)
			.copyFrom(MemorySegment.ofArray(data));
	}

	/** The frame's bindings over the test's targets (the engine's EngineBindings without the game's blocks). */
	private record TestBindings(Targets targets, long noise, long[] frameBlock) implements GbufferDelegate.Bindings {
		@Override
		public long[] frameBlock(String program) {
			return this.frameBlock;
		}

		@Override
		public long[] customBlock() {
			return null;
		}

		@Override
		public long[] block(String name) {
			return null;
		}

		@Override
		public long texture(String colortexName, boolean write) {
			int index = dev.mcms.shaders.runtime.ProgramRequests.targetIndex(colortexName);
			return index >= 0 && index < this.targets.count() ? write ? this.targets.write(index) : this.targets.read(index) : 0;
		}

		@Override
		public long depth(String name) {
			return this.targets.depth(0);
		}

		@Override
		public long noise() {
			return this.noise;
		}

		@Override
		public long defaultTexture(String name) {
			return 0;
		}

		@Override
		public void projection(float[] out) {
			new org.joml.Matrix4f().get(out);
		}
	}

	/** A capture with everything the pack's programs read (ClassesFinalGpuTest's plain overworld camera). */
	private static FrameInputs inputs() {
		int width = W, height = H, renderDistanceChunks = 12, biomeId = 2, biomeCategory = 5;
		float partialTick = 0.5f, fovY = 70f, farPlane = 192f;
		double cameraX = 0, cameraY = 100, cameraZ = 0, eyeX = 0, eyeY = 101.62, eyeZ = 0;
		float yaw = 0, pitch = 0, lookX = 0, lookY = 1, lookZ = 0;
		long dayTime = 6000L;
		float sunAngleDegrees = 0f;
		int moonPhaseOrdinal = 0, eyeFluid = 0, eyeBlockLight = 15, eyeSkyLight = 15;
		float rainLevel = 0f, thunderLevel = 0f;
		float skyR = 0.47f, skyG = 0.65f, skyB = 0.92f, fogR = 0.75f, fogG = 0.84f, fogB = 0.95f;
		float fogEnvironmentalStart = 10f, fogEnvironmentalEnd = 100f, fogRenderDistanceStart = 90f,
			fogRenderDistanceEnd = 192f;
		float screenBrightness = 1f;
		boolean hideGui = false, firstPerson = true, spectator = false, rightHanded = true, creative = false;
		float nightVision = 0f, blindness = 0f, darknessFactor = 0f, darknessLightFactor = 0f, playerMood = 0f;
		int heldBlockLightValue = 0, heldBlockLightValue2 = 0, heldItemId = 0, heldItemId2 = 0;
		boolean sneaking = false, sprinting = false, onGround = true, swimming = false, inWaterFeet = false,
			inLava = false, wet = false, burning = false, hurt = false, invisible = false, glowing = false,
			alive = true, child = false, ridden = false, riding = false, elytraFlying = false,
			vehicleInWater = false;
		float health = 20f, maxHealth = 20f, armor = 10f, maxArmor = 50f, air = 300f, maxAir = 300f, food = 20f,
			maxFood = 20f;
		int biomePrecipitation = 0;
		float temperature = 0.7f, rainfall = 0f, ambientLight = 0f, cloudHeight = 192f;
		int bedrockLevel = -64, heightLimit = 384, logicalHeightLimit = 384;
		boolean hasCeiling = false, hasSkylight = true, lightning = false;
		float endFlashIntensity = 0f, endFlashXAngle = 0f, endFlashYAngle = 0f;
		double lightningX = 0, lightningY = 64, lightningZ = 210;
		int dateYear = 2026, dateMonth = 11, dateDay = 13, timeHour = 12, timeMinute = 30, timeSecond = 15;
		int yearSecondIn = 1000, yearSecondLeft = 2000;
		return new FrameInputs(width, height, partialTick, cameraX, cameraY, cameraZ, eyeX, eyeY, eyeZ, yaw, pitch,
			lookX, lookY, lookZ, new org.joml.Matrix4f(), fovY, farPlane, renderDistanceChunks, dayTime, sunAngleDegrees, moonPhaseOrdinal,
			rainLevel, thunderLevel, skyR, skyG, skyB, fogR, fogG, fogB, fogEnvironmentalStart, fogEnvironmentalEnd,
			fogRenderDistanceStart, fogRenderDistanceEnd, eyeFluid, eyeBlockLight, eyeSkyLight, screenBrightness,
			hideGui, firstPerson, spectator, rightHanded, creative, nightVision, blindness, darknessFactor,
			darknessLightFactor, playerMood, heldBlockLightValue, heldBlockLightValue2, heldItemId, heldItemId2,
			sneaking, sprinting, onGround, swimming, inWaterFeet, inLava, wet, burning, hurt, invisible, glowing,
			alive, child, ridden, riding, elytraFlying, vehicleInWater, health, maxHealth, armor, maxArmor, air,
			maxAir, food, maxFood, biomeId, biomeCategory, biomePrecipitation, temperature, rainfall, ambientLight,
			cloudHeight, bedrockLevel, heightLimit, logicalHeightLimit, hasCeiling, hasSkylight, endFlashIntensity,
			endFlashXAngle, endFlashYAngle, lightning, lightningX, lightningY, lightningZ, dateYear, dateMonth,
			dateDay, timeHour, timeMinute, timeSecond, yearSecondIn, yearSecondLeft);
	}

	private static ByteBuffer block(FrameUniforms uniforms) {
		ByteBuffer bytes = ByteBuffer.allocate(FrameUniforms.size()).order(ByteOrder.LITTLE_ENDIAN);
		uniforms.write(bytes);
		return bytes;
	}

	private static long[] upload(long ctx, ByteBuffer bytes, TestOwned tracker) {
		bytes.rewind();
		long buffer = MetalBridge.newBuffer(ctx, Math.max(16, bytes.remaining()));
		MemorySegment.ofAddress(MetalBridge.bufferContents(buffer)).reinterpret(bytes.remaining())
			.copyFrom(MemorySegment.ofBuffer(bytes));
		tracker.add(buffer);
		return new long[] {buffer, 0};
	}

	/** The native handles this test created and frees them once (ClassesFinalGpuTest's one-owner rule). */
	private static final class TestOwned {
		private final java.util.Set<Long> handles = new java.util.LinkedHashSet<>();

		void add(long handle) {
			assertTrue(handle == 0 || handles.add(handle),
				"handle " + handle + " is enqueued for release twice: one owner per native handle");
		}

		void release() {
			handles.forEach(handle -> {
				if (handle != 0) {
					MetalBridge.release(handle);
				}
			});
			handles.clear();
		}
	}
}
