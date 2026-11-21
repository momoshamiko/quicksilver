// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.ProgramRequests;
import dev.mcms.shaders.runtime.Targets;
import dev.mcms.shaders.runtime.uniforms.FrameInputs;
import dev.mcms.shaders.runtime.uniforms.FrameUniforms;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.joml.Matrix4f;

/**
 * Shared GPU-test fixtures for the gbuffers delegate: the classes test pack, a realistic frame capture (the camera's
 * view rotation, field of view and far plane are the caller's), the delegate's bindings over test targets, and the
 * handle bookkeeping ({@link Owned}: one owner per native handle).
 */
final class GbufferFixtures {

	static final int RGBA8 = 70;

	private GbufferFixtures() {
	}

	/** The mcms-classes test pack, loaded clean. */
	static PackModel classesModel() throws Exception {
		return testPack("mcms-classes");
	}

	/** The named test pack from bench/test-packs, loaded clean. */
	static PackModel testPack(String name) throws Exception {
		Path dir = Path.of(System.getProperty("user.dir"));
		Path pack = null;
		for (int i = 0; i < 6 && pack == null; i++) {
			Path candidate = dir.resolve(Path.of("bench", "test-packs", name));
			if (Files.isDirectory(candidate)) {
				pack = candidate;
			}
			dir = dir.getParent();
		}
		assertTrue(pack != null, "bench/test-packs/" + name + " not found above " + System.getProperty("user.dir"));
		try (PackSource source = PackSource.open(pack)) {
			PackModel model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
			assertTrue(model.problems().isEmpty(), "the test pack must load clean: " + model.problems());
			return model;
		}
	}

	/** The game's own pipeline list the engine maps at install (Engine.gamePipelines). */
	static List<RenderPipeline> gamePipelines() {
		List<RenderPipeline> all = new ArrayList<>(net.minecraft.client.renderer.RenderPipelines.requiredPipelines());
		all.addAll(net.minecraft.client.renderer.RenderPipelines.optionalPipelines());
		return all;
	}

	static RenderPipeline gamePipeline(String name) {
		for (RenderPipeline pipeline : gamePipelines()) {
			if (pipeline.getLocation().toString().equals(name)) {
				return pipeline;
			}
		}
		throw new IllegalStateException("no game pipeline " + name);
	}

	/**
	 * A realistic frame capture: the camera at (0, 100, 0) in the overworld, the given view rotation, vertical field of view
	 * and far plane in blocks, the test's width and height. The projection the frame builds is the game's shape (near 0.05).
	 */
	static FrameInputs inputs(int width, int height, Matrix4f viewRotation, float fovY, float farPlane) {
		return inputs(width, height, viewRotation, fovY, farPlane, 0.75f, 0.84f, 0.95f);
	}

	/** The same capture with the frame's fog colour given (the clear hook's colortex0 default follows it). */
	static FrameInputs inputs(int width, int height, Matrix4f viewRotation, float fovY, float farPlane, float fogR,
			float fogG, float fogB) {
		int renderDistanceChunks = 12, biomeId = 2, biomeCategory = 5;
		float partialTick = 0.5f;
		double cameraX = 0, cameraY = 100, cameraZ = 0, eyeX = 0, eyeY = 101.62, eyeZ = 0;
		float yaw = 0, pitch = 0, lookX = 0, lookY = 1, lookZ = 0;
		long dayTime = 6000L;
		float sunAngleDegrees = 0f;
		int moonPhaseOrdinal = 0, eyeFluid = 0, eyeBlockLight = 15, eyeSkyLight = 15;
		float rainLevel = 0f, thunderLevel = 0f;
		float skyR = 0.47f, skyG = 0.65f, skyB = 0.92f;
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
			lookX, lookY, lookZ, viewRotation, fovY, farPlane, renderDistanceChunks, dayTime, sunAngleDegrees, moonPhaseOrdinal,
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

	/** The frame block's bytes (the block's full size, written). */
	static ByteBuffer block(FrameUniforms uniforms) {
		ByteBuffer bytes = ByteBuffer.allocate(FrameUniforms.size()).order(ByteOrder.LITTLE_ENDIAN);
		uniforms.write(bytes);
		return bytes;
	}

	/** Uploads bytes into a new backend buffer; {handle, offset 0} as the delegate's frame block expects. */
	static long[] upload(long ctx, ByteBuffer bytes, Owned tracker) {
		bytes.rewind();
		long buffer = MetalBridge.newBuffer(ctx, Math.max(16, bytes.remaining()));
		MemorySegment.ofAddress(MetalBridge.bufferContents(buffer)).reinterpret(bytes.remaining())
			.copyFrom(MemorySegment.ofBuffer(bytes));
		tracker.add(buffer);
		return new long[] {buffer, 0};
	}

	/** Writes {@code data} into a backend GPU buffer (shared storage; the address is CPU-visible). */
	static void fill(GpuBuffer buffer, byte[] data) {
		MemorySegment.ofAddress(MetalBridge.bufferAddress(buffer)).reinterpret(data.length)
			.copyFrom(MemorySegment.ofArray(data));
	}

	/** A backend GPU buffer holding {@code data} (the game's packed bytes, such as CloudFaces). */
	static GpuBuffer gpuBuffer(long ctx, byte[] data, Owned tracker) {
		GpuBuffer buffer = MetalBridge.newGpuBuffer(ctx, data.length);
		fill(buffer, data);
		tracker.add(MetalBridge.bufferHandle(buffer));
		return buffer;
	}

	/** The delegate's bindings over the test's targets: the frame block, the frame's projection, no custom or game blocks. */
	record Bindings(Targets targets, long noise, long[] frameBlock, FrameUniforms uniforms) implements GbufferDelegate.Bindings {
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
			int index = ProgramRequests.targetIndex(colortexName);
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
			this.uniforms.gbufferProjection(out);
		}
	}

	/** The native handles one test created; released once (the one-owner rule of the clouds tests). */
	static final class Owned {
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
