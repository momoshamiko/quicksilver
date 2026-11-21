// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout.UniformDescription;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.DimensionSet;
import dev.mcms.shaders.runtime.EngineFrame;
import dev.mcms.shaders.runtime.EngineSlots;
import dev.mcms.shaders.runtime.Targets;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import dev.mcms.shaders.runtime.uniforms.FrameInputs;
import dev.mcms.shaders.runtime.uniforms.FrameUniforms;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

/**
 * The {@code mcms-classes} pack's reason to exist (M2, bench/classes_check.py): every gbuffers program writes its class
 * number to {@code colortex4} and {@code final} shows it in the screenshot's red channel. The game preview showed pure
 * green — {@code colortex4} read 0 at every pixel although the log said most locations draw through pack programs. This
 * is the whole path offline: the pack model loads the pack, {@code DimensionSet.load} builds the plans with the game's
 * real pipeline list, the delegate draws a sky triangle ({@code gbuffers_skybasic}, class 1) through the SKY phase's
 * layout and an entity triangle ({@code gbuffers_entities}, class 8) through the OPAQUE phase's layout, the frame's
 * phase hook flips the pictures to the read side, and the composite hook runs {@code final}, which samples
 * {@code colortex4}. The readback must show the classes; anything else is a step where the class value is lost (the
 * attachment mapping, the R8 pipeline format, the write mask, the blend, the flipped texture pair, the clear).
 */
@Tag("gpu")
class ClassesFinalGpuTest {

	/** MTLPixelFormatR8Unorm, colortex4's format word in this pack (TargetFormats' table). */
	private static final int R8 = 10;
	private static final int RGBA8 = 70;
	private static final int W = 64, H = 48;
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
	void theClassesTheGbuffersProgramsWriteReachFinal() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long enc = gpu.encoder();
			TestOwned toRelease = new TestOwned();
			DimensionSet set = DimensionSet.load(ctx, "classes", model, model.rootFolder(), custom, RGBA8,
				model.rootFolder().union().colorFormats(), gamePipelines(), copy -> null);
			try {
				assertTrue(set.absent() == 0, "the pack's programs must all run: " + set.notes());
				GbufferPlans plans = set.plans();
				assertNotNull(plans, "the pack's gbuffers plans must build");

				// The machinery the parent's checklist names, checked where it is decided (not a tautology: a layout or
				// format bug here loses the class value before any draw).
				assertEquals(1, plans.attachmentOf(GbufferPhase.SKY, 4), "colortex4's attachment in the sky layout");
				assertEquals(1, plans.attachmentOf(GbufferPhase.OPAQUE, 4), "colortex4's attachment in the opaque layout");
				assertEquals(R8, plans.attachmentFormat(GbufferPhase.OPAQUE, 1),
					"the R8 format word the pipeline's second colour attachment carries");

				Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(),
					set.usedTargets(), false, false, W, H, Targets.engineTextures(ctx), null);
				assertTrue(targets.create());
				assertEquals(R8, targets.format(4), "colortex4's texture is R8");
				// The depth and colour textures, the set's passes and the plans' pipelines are engine-owned:
				// Targets.release and DimensionSet/GbufferPlans close free them. Enqueuing one of those handles below
				// would free it twice — the second release is a use-after-free (objc_release on a dead object, the
				// hs_err crashes of 2026-11-14) — so the tracker takes only what this test created.

				long noise = ShaderNative.textureNew(ctx, RGBA8, ShaderNative.TYPE_2D, 16, 16, 1, 1,
					ShaderNative.USAGE_SHADER_READ, ShaderNative.STORAGE_PRIVATE);
				toRelease.add(noise);

				FrameUniforms uniforms = new FrameUniforms();
				uniforms.update(inputs());
				long[] frameBlock = upload(ctx, block(uniforms), toRelease);
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
				GbufferDelegate delegate = new GbufferDelegate(plans, new TestBindings(targets, noise, frameBlock));
				EngineFrame frame = new EngineFrame(set, targets, null, noise, 0, 0, (bytes, out) -> {
					long[] slice = upload(ctx, bytes, toRelease);
					out[0] = slice[0];
					out[1] = slice[1];
				}, Map.of(), Map.of(), new EngineFrame.Gbuffers(plans.layout(GbufferPhase.SKY),
					plans.layout(GbufferPhase.OPAQUE), plans.layout(GbufferPhase.TRANSLUCENT), true,
					delegate::phase, null));

				long main = ShaderNative.textureNew(ctx, RGBA8, ShaderNative.TYPE_2D, W, H, 1, 1,
					ShaderNative.USAGE_SHADER_READ | 4, ShaderNative.STORAGE_PRIVATE);
				toRelease.add(main);
				EngineFrame.Main screen = new EngineFrame.Main();
				screen.set(main, RGBA8, W, H, null);

				// One frame: the clears, the sky and opaque passes the takeover redirects, the phase flip, the composite.
				frame.clearHook(enc, null, targets, blocks);
				drawThrough(gpu, enc, toRelease, delegate, plans, targets, "minecraft:pipeline/sky", GbufferPhase.SKY,
					-1.0f, 0.0f, 0.4f, List.of());
				drawThrough(gpu, enc, toRelease, delegate, plans, targets, "minecraft:pipeline/entity_solid",
					GbufferPhase.OPAQUE, 0.0f, 1.0f, -0.2f,
					List.of(new UniformDescription("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER)));
				frame.phaseHook(enc, null, targets, blocks, null);
				frame.compositeHook(enc, null, targets, screen, blocks);
				MetalBridge.commitAndWait(enc);
				frame.close();
				delegate.close();

				byte[] pixels = gpu.readTexture(main, 0, 0, W, H, 1, 4);
				// Memory row 0 is the window's bottom row (the targets keep OpenGL's orientation): rows below H/2 drew
				// the sky triangle (class 1), rows above it the entity triangle (class 8).
				assertEquals(1, red(pixels, W / 4, H / 4), "the sky program's class in the bottom half");
				assertEquals(8, red(pixels, W / 4, H * 3 / 4), "the entities program's class in the top half");
				assertEquals(255, green(pixels, W / 4, H / 4), "final marks every pixel it wrote green");

				targets.release();
			} finally {
				toRelease.release();
			}

		}
	}

	/** The game's own pipeline list the engine maps at install (Engine.gamePipelines). */
	private static List<RenderPipeline> gamePipelines() {
		List<RenderPipeline> all = new ArrayList<>(net.minecraft.client.renderer.RenderPipelines.requiredPipelines());
		all.addAll(net.minecraft.client.renderer.RenderPipelines.optionalPipelines());
		return all;
	}

	/**
	 * One redirected pass: the phase's layout as the colours (the write textures, loaded — the clear hook cleared them)
	 * with the engine's depth, the pipeline's variant selected by name, one geometry drawn in the game's vertex format.
	 * The atlas uniform's slot comes back from {@code select}; the pass binds its texture there, as the game's own pass
	 * does with {@code Sampler0} — here a plain white texture, so the entities program's {@code gtexture} sample passes
	 * its alpha test.
	 */
	private void drawThrough(HeadlessGpu gpu, long enc, TestOwned toRelease, GbufferDelegate delegate,
			GbufferPlans plans, Targets targets, String pipeline, GbufferPhase phase, float y0, float y1, float z,
			List<UniformDescription> uniforms) {
		GbufferPlans.Plan plan = plans.planOf(pipeline);
		assertNotNull(plan, pipeline + " must have a plan");
		GbufferPlans.Variant variant = plan.variant(PassKind.GBUFFERS, phase);
		assertTrue(variant instanceof GbufferPlans.PackVariant pv && !pv.program().isEmpty(),
			pipeline + " must draw through a pack program in " + phase);
		RenderPipeline game = gamePipeline(pipeline);
		long[] colours = new long[plans.layout(phase).length];
		for (int i = 0; i < colours.length; i++) {
			colours[i] = targets.write(plans.layout(phase)[i]);
		}
		MetalBridge.renderBegin(enc, colours, null, targets.depth(0), false, 0, W, H);
		delegate.begin(enc, true);
		delegate.phase(phase);
		int[] slots = delegate.select(enc, pipeline, null, null, uniforms);
		assertNotNull(slots, pipeline + " must not be dropped in " + phase);
		if (!uniforms.isEmpty() && slots[0] >= 0) {
			// The program samples its atlas through an argument sampler (sampler(0) in the emitted MSL); the game's
			// own pass binds its sampler there with the texture. Without one every sample reads undefined texels and
			// the program's alpha discard kills the draw: a plain nearest, clamp-to-edge, base-level sampler.
			long sampler = MetalBridge.samplerNew(gpu.context(), 0, 0, 0, 0, 0, 1, 0.25f);
			toRelease.add(sampler);
			MetalBridge.renderTextureSlots(enc, slots[0], whiteTexture(gpu.context(), toRelease), sampler,
				delegate.vertexSamplerSlot(0), delegate.fragmentSamplerSlot(0));
		}
		// Quads reach the backend pre-expanded into triangles (MetalConst.primitive: the game draws a quad through
		// the sequential index buffer), so a quad's draw is the same two counter-clockwise triangles a TRIANGLES
		// pipeline draws: a four-vertex non-indexed draw would rasterise only one triangle of the span.
		boolean triangles = game.getPrimitiveTopology() == PrimitiveTopology.TRIANGLES
			|| game.getPrimitiveTopology() == PrimitiveTopology.QUADS;
		long buffer = vertexBuffer(gpu.context(), game.getVertexFormatBinding(0), y0, y1, z, triangles, toRelease);
		MetalBridge.vertexBuffer(enc, EngineSlots.VERTEX_DATA, buffer, 0);
		assertTrue(delegate.beforeDraw(enc), pipeline + "'s draw must run");
		int count = triangles ? 6 : 4;
		MetalBridge.renderDraw(enc, count, 1, 0, 0);
		MetalBridge.endEncoders(enc);
	}

	/** The pack sampler the variant's program reads the atlas through (diagnostics). */
	private static String packAtlasOf(GbufferPlans.Variant variant) {
		if (variant instanceof GbufferPlans.PackVariant pv) {
			for (var texture : pv.textures()) {
				if (texture.samplerSlot() >= 0) {
					return texture.name();
				}
			}
		}
		return "(none)";
	}

	private static RenderPipeline gamePipeline(String name) {
		for (RenderPipeline pipeline : gamePipelines()) {
			if (pipeline.getLocation().toString().equals(name)) {
				return pipeline;
			}
		}
		throw new IllegalStateException("no game pipeline " + name);
	}

	/** A plain white RGBA8 texture (the atlas the entities program samples). */
	private static long whiteTexture(long ctx, TestOwned toRelease) {
		long texture = ShaderNative.textureNew(ctx, RGBA8, ShaderNative.TYPE_2D, 4, 4, 1, 1,
			ShaderNative.USAGE_SHADER_READ, ShaderNative.STORAGE_PRIVATE);
		assertTrue(texture != 0, "the device refused the white atlas");
		byte[] texels = new byte[4 * 4 * 4];
		for (int i = 0; i < texels.length; i++) {
			texels[i] = (byte) 0xff;
		}
		assertTrue(ShaderNative.textureUpload(ctx, texture, texels, 16, 0, 0, 0, 0, 0, 0, 4, 4, 1));
		toRelease.add(texture);
		return texture;
	}

	/**
	 * Two counter-clockwise (GL convention) triangles spanning clip y from {@code y0} to {@code y1} at clip z {@code z}
	 * ({@code triangles}: the TRIANGLES pipelines, and the QUADS pipelines whose draws the game's sequential index
	 * buffer expands into the same two triangles), or the same span as one counter-clockwise quad for every other topology
	 * (the sky pipeline draws a triangle fan, where the quad order BL, BR, TR, TL keeps both fan triangles
	 * counter-clockwise — a repeated BL would leave a degenerate second fan triangle and cover half the span),
	 * encoded in the game pipeline's vertex format: the Position element carries the coordinates, every other
	 * element (Color, UV0, UV1, UV2, Normal) its maximal value.
	 */
	private static long vertexBuffer(long ctx, VertexFormat format, float y0, float y1, float z, boolean triangles,
			TestOwned toRelease) {
		int stride = format.getVertexSize();
		float[][] positions = triangles
			? new float[][] {{-1, y0, z}, {1, y0, z}, {1, y1, z}, {-1, y0, z}, {1, y1, z}, {-1, y1, z}}
			: new float[][] {{-1, y0, z}, {1, y0, z}, {1, y1, z}, {-1, y1, z}};
		long buffer = MetalBridge.newBuffer(ctx, stride * positions.length);
		toRelease.add(buffer);
		long at = MetalBridge.bufferContents(buffer);
		for (int vertex = 0; vertex < positions.length; vertex++) {
			long base = at + (long) stride * vertex;
			for (VertexFormatElement element : format.getElements()) {
				writeElement(base + element.offset(), element, positions[vertex]);
			}
		}
		return buffer;
	}

	/**
	 * One element's value at {@code at}: the position for {@code Position}, the maximal value for every other semantic
	 * (a white vertex colour, the texture's centre-ish coordinate, a forward normal). The encoding follows the
	 * {@code GpuFormat} word's channel count, bits and kind.
	 */
	private static void writeElement(long at, VertexFormatElement element, float[] position) {
		String word = element.format().name();
		int under = word.indexOf('_');
		int digit = 0;
		while (digit < under && !Character.isDigit(word.charAt(digit))) {
			digit++;
		}
		int channels = switch (word.substring(0, digit)) {
			case "R" -> 1;
			case "RG" -> 2;
			case "RGB" -> 3;
			case "RGBA" -> 4;
			default -> 0;
		};
		int bits = Integer.parseInt(word.substring(digit, under));
		String kind = word.substring(under + 1);
		boolean isPosition = element.name().equals("Position");
		for (int c = 0; c < channels; c++) {
			float value = isPosition && c < position.length ? position[c] : 1.0f;
			long field = at + (long) c * (bits / 8);
			if (kind.equals("FLOAT")) {
				MemoryUtil.memPutFloat(field, value);
			} else if (kind.startsWith("U") && kind.endsWith("NORM")) {
				putUnsigned(field, bits, 0xffff_ffff_ffff_ffffL);
			} else if (kind.startsWith("S") && kind.endsWith("NORM")) {
				putUnsigned(field, bits, (1L << bits - 1) - 1);
			} else if (kind.startsWith("U")) {
				putUnsigned(field, bits, 1);
			} else {
				MemoryUtil.memPutInt(field, 1);
			}
		}
	}

	private static void putUnsigned(long at, int bits, long max) {
		switch (bits) {
			case 8 -> MemoryUtil.memPutByte(at, (byte) max);
			case 16 -> MemoryUtil.memPutShort(at, (short) max);
			default -> MemoryUtil.memPutInt(at, (int) max);
		}
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
			return switch (name) {
				case "depthtex1" -> this.targets.depth(1);
				case "depthtex2" -> this.targets.depth(2);
				default -> this.targets.depth(0);
			};
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

	/** A capture with everything the pack's programs read: the screen and a plain overworld camera (no extras). */
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



	private static int red(byte[] pixels, int x, int y) {
		return pixels[(y * W + x) * 4] & 0xff;
	}

	/**
	 * The native handles this test created and frees them once. One owner per handle: an engine object frees what it
	 * created ({@code Targets.release} the depth and colour textures; the plans' close their pipelines), so enqueuing
	 * such a handle here frees it twice — the second release is a use-after-free (objc_release on a dead object, the
	 * hs_err crashes of 2026-11-14). The duplicate guard makes that mistake fail the test instead of corrupting the
	 * process.
	 */
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


	private static int green(byte[] pixels, int x, int y) {
		return pixels[(y * W + x) * 4 + 1] & 0xff;
	}
}
