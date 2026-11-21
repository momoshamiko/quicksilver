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
import dev.mcms.shaders.runtime.uniforms.FrameInputs;
import dev.mcms.shaders.runtime.uniforms.FrameUniforms;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The mcms-passes test pack through the engine's own frame path on the headless Metal device: the pack loads through
 * the pack model with {@code MCMS_HIGH_TARGETS} on, DimensionSet translates and compiles its programs, and EngineFrame
 * runs two frames (the persistence cells need a frame's earlier writes) with a synthetic world picture as the copy
 * source. The final pass writes the main colour; the test reads it back and applies {@code bench/passes_check.py}'s
 * cell rules (its cell map): every check cell all green, the read-out rows decoding to the served values. The second
 * copy path runs too: a main colour whose format differs from colortex0's takes the engine's copy program, not the
 * blit (plan.md E3, the encoding bullet).
 */
@Tag("gpu")
class EngineFrameGpuTest {

	private static final int W = 1920, H = 1080, CELL = 32;
	private static final int RGBA8 = 70, RGBA16F = 115, DEPTH32F = 252;
	private static final int RT_READ = ShaderNative.USAGE_SHADER_READ | ShaderNative.USAGE_RENDER_TARGET;

	/** The checker's cell map (bench/passes_check.py, CELLS): name, column, row (bottom-left anchor is handled apart). */
	private record Cell(String name, int column, int row) {
	}

	private static final List<Cell> CELLS = List.of(
		new Cell("clear.colortex1.white", 0, 0), new Cell("clear.colortex2.custom", 1, 0),
		new Cell("clear.colortex5.black", 2, 0), new Cell("clear.colortex7.persist", 3, 0),
		new Cell("flip.read1", 4, 0), new Cell("format.r32f", 5, 0), new Cell("format.rgba16f.above1", 6, 0),
		new Cell("orient.texcoord", 7, 0), new Cell("orient.world", 8, 0), new Cell("flip.read2", 9, 0),
		new Cell("format.rgba8.quantised", 10, 0), new Cell("mip.colortex0.level3", 11, 0),
		new Cell("mip.colortex2.level3", 12, 0),
		new Cell("noisetex", 0, 1), new Cell("custom.uniform", 1, 1),
		new Cell("aspectRatio", 2, 1), new Cell("near", 3, 1),
		new Cell("clear.colortex9.custom", 4, 1), new Cell("clear.colortex15.black", 5, 1),
		new Cell("format.r32f.colortex8", 6, 1), new Cell("format.rgba16f.colortex12", 7, 1),
		new Cell("flip.high.replaced", 8, 1), new Cell("flip.replaced", 13, 0),
		new Cell("orient.texturematrix", 9, 1),
		new Cell("named.outColor0.colortex6", 14, 0), new Cell("named.outColor1.colortex5", 15, 0));

	private static PackModel model;
	private static CustomUniforms custom;
	private static Path packDir;

	@BeforeAll
	static void loadPack() throws Exception {
		packDir = findPack();
		Properties options = new Properties();
		options.setProperty("MCMS_HIGH_TARGETS", "true");
		try (PackSource source = PackSource.open(packDir)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", options);
		}
		assertTrue(model.problems().isEmpty(), "the test pack must load clean: " + model.problems());
		custom = CustomUniforms.of(model);
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

	@Test
	void everyCellGreenThroughTheBlitCopy() {
		runAndCheck(RGBA8, false);
	}

	@Test
	void everyCellGreenThroughTheCopyProgram() {
		runAndCheck(RGBA16F, true);
	}

	private void runAndCheck(int mainFormat, boolean expectDrawCopy) {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long enc = gpu.encoder();
			List<Long> toRelease = new ArrayList<>();
			try {
				DimensionSet set = DimensionSet.load(ctx, "mcms-passes", model, model.rootFolder(), custom, mainFormat,
					model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
				assertEquals(List.of("deferred", "deferred1", "composite", "composite1", "composite2", "composite3",
					"composite4", "composite5", "final"),
					set.passes().stream().map(CompiledProgram::name).toList(), "the frame order of the pack");
				assertTrue(set.absent() == 0 && set.onRoad() == 0,
					"every program must run from the pack's own files: " + set.notes());

				Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(),
					set.usedTargets(), false, false, W, H, Targets.engineTextures(ctx), null);
				assertTrue(targets.create());
				long noise = Engine.makeNoise(ctx, set.noiseResolution());
				long copy = Engine.copyPipeline(ctx, targets.format(0));
				assertTrue(copy != 0, "the engine's copy program must compile");
				long world = worldTexture(ctx, mainFormat, toRelease);

				// The frame and custom blocks from a synthetic capture: viewWidth 1920, viewHeight 1080, near 0.05
				// (the documented value), far 192 (12 chunks), and the pack's custom uniform 0.625.
				FrameUniforms uniforms = new FrameUniforms();
				uniforms.update(inputs());
				if (custom != null) {
					custom.update(uniforms);
				}
				long[] frameBlock = upload(ctx, block(uniforms, null), toRelease);
				long[] customBlock = custom != null ? upload(ctx, block(null, custom), toRelease) : null;
				EngineFrame.PassBlocks blocks = new EngineFrame.PassBlocks() {
					@Override
					public long[] frameBlock(String program) {
						return frameBlock;
					}

					@Override
					public long[] customBlock() {
						return customBlock;
					}
				};
				// The block upload: the frame's transient slice (the test copies the bytes into a fresh buffer).
				EngineFrame frame = new EngineFrame(set, targets, null, noise, copy, 0, (bytes, out) -> {
					long[] slice = upload(ctx, bytes, toRelease);
					out[0] = slice[0];
					out[1] = slice[1];
				}, Map.of(), Map.of(), new EngineFrame.Gbuffers(new int[0], new int[0], new int[0], false,
					phase -> {}, null));
				EngineFrame.Main main = new EngineFrame.Main();
				main.set(world, mainFormat, W, H, null);
				// Two frames: the persistence cells (Clear=false colortex7, colortex15's next-frame clear) read
				// what the first frame wrote (the checker reads a screenshot of a running game).
				frame.clearHook(enc, null, targets, blocks);
				frame.compositeHook(enc, null, targets, main, blocks);
				MetalBridge.commitAndWait(enc);
				frame.clearHook(enc, null, targets, blocks);
				frame.compositeHook(enc, null, targets, main, blocks);
				MetalBridge.commitAndWait(enc);
				frame.close();

				float[][][] picture = readTopDown(gpu, world, mainFormat);
				assertCells(picture);
				assertReadout(picture, 2, 1920f, "viewWidth");
				assertReadout(picture, 3, 1080f, "viewHeight");
				assertReadout(picture, 4, 192f, "far");
				assertEquals(expectDrawCopy, mainFormat != targets.format(0),
					"the copy path the format difference picks");

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

	/** A capture with everything the pack's cells read: the screen, the render distance, a plain overworld camera. */
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
			rainLevel,
			thunderLevel, skyR, skyG, skyB, fogR, fogG, fogB, fogEnvironmentalStart, fogEnvironmentalEnd,
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

	/** The frame block's or the custom block's bytes (one of the two owners is null). */
	private static ByteBuffer block(FrameUniforms uniforms, CustomUniforms custom) {
		if (uniforms != null) {
			ByteBuffer bytes = ByteBuffer.allocate(FrameUniforms.size()).order(ByteOrder.LITTLE_ENDIAN);
			uniforms.write(bytes);
			return bytes;
		}
		ByteBuffer bytes = ByteBuffer.allocate(custom.size()).order(ByteOrder.LITTLE_ENDIAN);
		custom.write(bytes);
		return bytes;
	}

	private static long[] upload(long ctx, ByteBuffer bytes, List<Long> tracker) {
		bytes.rewind();
		long buffer = MetalBridge.newBuffer(ctx, Math.max(16, bytes.remaining()));
		MemorySegment.ofAddress(MetalBridge.bufferContents(buffer)).reinterpret(bytes.remaining())
			.copyFrom(MemorySegment.ofBuffer(bytes));
		tracker.add(buffer);
		return new long[] {buffer, 0};
	}

	/**
	 * The synthetic world picture in OpenGL's row order (memory row 0 is the window's bottom row): ground in the
	 * bottom half (luminance 0.4), sky in the top half (0.67), the difference the upright cell checks.
	 */
	private static long worldTexture(long ctx, int format, List<Long> tracker) {
		int bpp = format == RGBA8 ? 4 : 8;
		byte[] texels = new byte[W * H * bpp];
		for (int y = 0; y < H; y++) {
			float value = y < H / 2 ? 0.4f : 0.67f;
			for (int x = 0; x < W; x++) {
				int at = (y * W + x) * bpp;
				if (format == RGBA8) {
					byte channel = (byte) Math.round(value * 255);
					texels[at] = channel;
					texels[at + 1] = channel;
					texels[at + 2] = channel;
					texels[at + 3] = (byte) 0xff;
				} else {
					short half = half(value);
					for (int c = 0; c < 4; c++) {
						texels[at + c * 2] = (byte) half;
						texels[at + c * 2 + 1] = (byte) (half >> 8);
					}
				}
			}
		}
		long texture = ShaderNative.textureNew(ctx, format, ShaderNative.TYPE_2D, W, H, 1, 1, RT_READ,
			ShaderNative.STORAGE_PRIVATE);
		assertTrue(texture != 0, "the device refused the synthetic main colour");
		assertTrue(ShaderNative.textureUpload(ctx, texture, texels, (long) W * bpp, 0, 0, 0, 0, 0, 0, W, H, 1));
		tracker.add(texture);
		return texture;
	}

	/** The picture as float rows, top-down like the checker's screenshot (memory row 0 is the bottom row). */
	private static float[][][] readTopDown(HeadlessGpu gpu, long texture, int format) {
		int bpp = format == RGBA8 ? 4 : 8;
		byte[] bytes = gpu.readTexture(texture, 0, 0, W, H, 1, bpp);
		float[][][] out = new float[H][W][];
		for (int y = 0; y < H; y++) {
			for (int x = 0; x < W; x++) {
				int at = ((H - 1 - y) * W + x) * bpp;  // top-down row y reads memory row H-1-y
				float[] rgba = new float[4];
				for (int c = 0; c < 4; c++) {
					if (format == RGBA8) {
						rgba[c] = (bytes[at + c] & 0xff) / 255f;
					} else {
						int lo = bytes[at + c * 2] & 0xff, hi = bytes[at + c * 2 + 1] & 0xff;
						rgba[c] = fromHalf((short) (lo | hi << 8));
					}
				}
				out[y][x] = rgba;
			}
		}
		return out;
	}

	private void assertCells(float[][][] picture) {
		List<String> bad = new ArrayList<>();
		for (Cell cell : CELLS) {
			if (!allGreen(picture, 32 + CELL * cell.column(), 32 + CELL * cell.row())) {
				bad.add(cell.name());
			}
		}
		// The orientation cell at the picture's bottom-left corner, in gl_FragCoord (composite3's anchor).
		if (!allGreen(picture, 0, H - CELL)) {
			bad.add("orient.fragcoord");
		}
		assertTrue(bad.isEmpty(), "cells not green: " + bad + firstRed(picture, bad));
	}

	/** passes_check.py's rule for an unscaled cell: every pixel pure green; the message shows a failing pixel. */
	private static boolean allGreen(float[][][] picture, int x0, int y0) {
		for (int y = y0; y < y0 + CELL; y++) {
			for (int x = x0; x < x0 + CELL; x++) {
				float[] p = picture[y][x];
				if (p[0] != 0f || p[1] != 1f || p[2] != 0f) {
					return false;
				}
			}
		}
		return true;
	}

	private static String firstRed(float[][][] picture, List<String> badCells) {
		for (Cell cell : CELLS) {
			if (!badCells.contains(cell.name())) {
				continue;
			}
			float[] p = picture[32 + CELL * cell.row()][32 + CELL * cell.column()];
			return String.format(" (first pixel of %s: %.3f %.3f %.3f)", cell.name(), p[0], p[1], p[2]);
		}
		return "";
	}

	/** The checker's read-out row: 16 bit cells, column 0 the most significant bit, white 1 and black 0. */
	private static void assertReadout(float[][][] picture, int row, float expected, String name) {
		int value = 0;
		for (int column = 0; column < 16; column++) {
			float[] p = picture[32 + CELL * row][32 + CELL * column];
			boolean white = p[0] > 0.5f && p[1] > 0.5f && p[2] > 0.5f;
			boolean black = p[0] < 0.5f && p[1] < 0.5f && p[2] < 0.5f;
			assertTrue(white || black, name + " bit cell " + column + " is neither black nor white");
			if (white) {
				value |= 1 << (15 - column);
			}
		}
		assertEquals((int) expected, value, name + " as the pack read it back");
	}

	/** IEEE 754 binary16 for the world's plain values (0, 0.4, 0.67, 1; no denormals, no overflow). */
	private static short half(float v) {
		int bits = Float.floatToIntBits(v);
		int sign = (bits >>> 16) & 0x8000;
		int exponent = ((bits >>> 23) & 0xff) - 127 + 15;
		int mantissa = (bits & 0x7fffff) >>> 13;
		if (exponent <= 0) {
			return (short) sign;
		}
		return (short) (sign | exponent << 10 | mantissa);
	}

	private static float fromHalf(short half) {
		int sign = (half & 0x8000) != 0 ? -1 : 1;
		int exponent = (half >> 10) & 0x1f;
		int mantissa = half & 0x3ff;
		if (exponent == 0) {
			return 0f;
		}
		return sign * (float) (Math.pow(2, exponent - 15) * (1 + mantissa / 1024.0));
	}
}
