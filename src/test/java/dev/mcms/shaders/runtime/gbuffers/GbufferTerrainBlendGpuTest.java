// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout.UniformDescription;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.DimensionSet;
import dev.mcms.shaders.runtime.EngineSlots;
import dev.mcms.shaders.runtime.Targets;
import dev.mcms.shaders.runtime.terrain.TerrainEncoder;
import dev.mcms.shaders.runtime.terrain.TerrainLayout;
import dev.mcms.shaders.runtime.terrain.TerrainUniforms;
import dev.mcms.shaders.runtime.terrain.TerrainVertex;
import dev.mcms.shaders.runtime.terrain.TerrainVertexType;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import dev.mcms.shaders.runtime.uniforms.FrameInputs;
import dev.mcms.shaders.runtime.uniforms.FrameUniforms;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The per-buffer blend directives reach the PSO a Sodium terrain draw uses ({@code blend.<program>.<buffer>}, OF-doc
 * section "blend"): the {@code mcms-targets} pack draws its {@code gbuffers_terrain} (magenta on colortex0, cyan on
 * colortex5) with {@code blend.gbuffers_terrain.colortex0=off} and {@code blend.gbuffers_terrain.colortex5=zero
 * src_color}, in the two-factor lower-case form. A terrain quad drawn through {@code sodium:pipeline/solid_terrain}'s
 * plan must replace colortex0 with the magenta and blend colortex5 as {@code dst * src}: the cyan source leaves the
 * clear's green, blue and alpha standing and zeroes its red (the source's red is 0). A PSO without the directive
 * writes the cyan over the clear — the silhouette the game showed in colortex5's cell (log
 * 2026-11-14-e4-preview12, "expected off or four blend factors").
 */
@Tag("gpu")
class GbufferTerrainBlendGpuTest {

	private static final int RGBA8 = 70;
	private static final int W = 64, H = 48;
	private static PackModel model;
	private static CustomUniforms custom;

	/** The registered extended terrain format Sodium's passes draw with (as GbufferCoverageGpuTest registers it). */
	private static final VertexFormat EXTENDED = TerrainVertexType.INSTANCE.getVertexFormat();
	private static final PrologueFacts FACTS = new PrologueFacts(EngineSlots.PUSH_CONSTANTS, 0,
		TerrainUniforms.GLOBALS_SLOT, 0, -1);

	@BeforeAll
	static void loadPack() throws Exception {
		Path pack = null;
		Path dir = Path.of(System.getProperty("user.dir"));
		for (int i = 0; i < 6 && pack == null; i++) {
			Path candidate = dir.resolve(Path.of("bench", "test-packs", "mcms-targets"));
			if (Files.isDirectory(candidate)) {
				pack = candidate;
			}
			dir = dir.getParent();
		}
		assertTrue(pack != null, "bench/test-packs/mcms-targets not found");
		try (PackSource source = PackSource.open(pack)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		assertTrue(model.problems().isEmpty(), "the test pack must load clean: " + model.problems());
		custom = CustomUniforms.of(model);
	}

	@Test
	void sodiumsTerrainAppliesThePerBufferBlendOfItsPackProgram() {
		VertexLayoutRegistry.register(EXTENDED, TerrainLayout.layout(0), FACTS);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long enc = gpu.encoder();
			List<Long> owned = new ArrayList<>();
			DimensionSet set = DimensionSet.load(ctx, "mcms-targets", model, model.rootFolder(), custom, RGBA8,
				model.rootFolder().union().colorFormats(), gamePipelines(), copy -> null);
			Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(),
				set.usedTargets(), false, false, W, H, Targets.engineTextures(ctx), null);
			try {
				assertTrue(set.absent() == 0, "the pack's programs must all run: " + set.notes());
				assertTrue(targets.create());
				GbufferPlans plans = set.plans();
				assertNotNull(plans, "the plans must build");
			int[] layout = plans.layout(GbufferPhase.OPAQUE);
			assertEquals(2, layout.length, "the opaque layout holds the terrain's two targets: "
				+ java.util.Arrays.toString(layout));
			assertEquals(0, layout[0]);
			assertEquals(5, layout[1]);

				FrameUniforms uniforms = new FrameUniforms();
				uniforms.update(inputs());
				ByteBuffer block = ByteBuffer.allocate(FrameUniforms.size()).order(ByteOrder.LITTLE_ENDIAN);
				uniforms.write(block);
				long frameBuffer = upload(ctx, block.array(), owned);
				List<UniformDescription> sodiumUniforms = List.of(
					new UniformDescription("u_BlockTex", UniformType.COMBINED_IMAGE_SAMPLER),
					new UniformDescription("u_LightTex", UniformType.COMBINED_IMAGE_SAMPLER),
					new UniformDescription("u_Globals", UniformType.UNIFORM_BUFFER));
				// Distinct clears so a lost directive shows: colortex0 (0.25, 0.5, 0.75) and colortex5 (0.75, 0.5, 0.25).
				long[] colours = new long[layout.length];
				float[][] clears = new float[layout.length][];
				for (int i = 0; i < layout.length; i++) {
					colours[i] = targets.write(layout[i]);
					clears[i] = layout[i] == 0 ? new float[] {0.25f, 0.5f, 0.75f, 1} : new float[] {0.75f, 0.5f, 0.25f, 1};
				}
				MetalBridge.renderBegin(enc, colours, clears, targets.depth(0), true, 1.0f, W, H);

				GbufferDelegate delegate = new GbufferDelegate(plans, new TestBindings(targets, frameBuffer));
				delegate.begin(enc, true);
				delegate.phase(GbufferPhase.OPAQUE);
				int[] slots = delegate.select(enc, "sodium:pipeline/solid_terrain", null, null, sodiumUniforms);
				assertNotNull(slots, "the terrain pipeline must not be dropped");

				// The globals mirror and the region offset the terrain prologue reads: zeros (no camera offset, no
				// region translation), bound at the slots the engine's map names.
				long zeros = upload(ctx, new byte[256], owned);
				MetalBridge.renderBuffer(enc, TerrainUniforms.GLOBALS_SLOT, zeros, 0);
				MetalBridge.renderBuffer(enc, EngineSlots.PUSH_CONSTANTS, zeros, 0);

				// One quad in Sodium's own encoding, covering the screen at clip z = 0 (identity matrices).
				TerrainVertex[] quad = new TerrainVertex[4];
				float[][] positions = {{-1, -1, 0}, {1, -1, 0}, {1, 1, 0}, {-1, 1, 0}};
				for (int i = 0; i < 4; i++) {
					quad[i] = new TerrainVertex();
					quad[i].x = positions[i][0];
					quad[i].y = positions[i][1];
					quad[i].z = positions[i][2];
					quad[i].color = 0xFFFFFFFF;
					quad[i].u = 0.5f;
					quad[i].v = 0.5f;
					quad[i].light = 0x00F000F0;
				}
				byte[] vertices;
				try (Arena arena = Arena.ofConfined()) {
					MemorySegment memory = arena.allocate(TerrainVertexType.STRIDE * 4L);
					new TerrainEncoder().write(memory.address(), 0x25, quad, 0);
					vertices = memory.toArray(ValueLayout.JAVA_BYTE);
				}
				MetalBridge.vertexBuffer(enc, EngineSlots.VERTEX_DATA, upload(ctx, vertices, owned), 0);
				// The game draws quads through the sequential index buffer (two triangles); the pack pipeline's
				// primitive is triangles, so this draw is that expansion.
				ByteBuffer indices = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
				for (int index : new int[] {0, 1, 2, 0, 2, 3}) {
					indices.putShort((short) index);
				}
				indices.flip();
				MetalBridge.indexBuffer(enc, upload(ctx, indices.array(), owned), false);
				assertTrue(delegate.beforeDraw(enc), "the terrain draw must run");
				MetalBridge.drawIndexed(enc, 6, 1, 0, 0, 0);
				MetalBridge.endEncoders(enc);
				MetalBridge.commitAndWait(enc);
				delegate.close();

				// colortex0 replaces its clear with the terrain's magenta (its directive is off).
				byte[] primary = gpu.readTexture(targets.write(0), 0, 0, W, H, 1, 4);
				assertEquals(255, channel(primary, 0), "colortex0 red (magenta, blend off)");
				assertEquals(0, channel(primary, 1), "colortex0 green");
				assertEquals(255, channel(primary, 2), "colortex0 blue (magenta, blend off)");
				// colortex5 blends the cyan over its clear as dst * src: red zeroed (the source's red is 0), the
				// clear's green, blue and alpha standing (the source's are 1). A lost directive leaves the cyan.
				byte[] blended = gpu.readTexture(targets.write(5), 0, 0, W, H, 1, 4);
				assertEquals(0, channel(blended, 0), "colortex5 red: the clear's red times the source's 0");
				assertEquals(128, channel(blended, 1), 1, "colortex5 green: the clear's green kept");
				assertEquals(64, channel(blended, 2), 1, "colortex5 blue: the clear's blue kept");
				assertEquals(255, channel(blended, 3), 1, "colortex5 alpha: the clear's alpha kept");
			} finally {
				targets.release();
				for (long handle : owned) {
					MetalBridge.release(handle);
				}
			}
		} finally {
			VertexLayoutRegistry.unregister(EXTENDED);
		}
	}

	private static int channel(byte[] pixels, int c) {
		return pixels[((H / 2) * W + W / 2) * 4 + c] & 0xff;
	}

	/** The game's own pipeline list the engine maps at install (Engine.gamePipelines's sources). */
	private static List<RenderPipeline> gamePipelines() {
		List<RenderPipeline> all = new ArrayList<>(net.minecraft.client.renderer.RenderPipelines.requiredPipelines());
		all.addAll(net.minecraft.client.renderer.RenderPipelines.optionalPipelines());
		return all;
	}

	/** A capture with everything the pack's programs read (the same values SodiumTerrainSamplersGpuTest serves). */
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
			lookX, lookY, lookZ, new Matrix4f(), fovY, farPlane, renderDistanceChunks, dayTime, sunAngleDegrees, moonPhaseOrdinal,
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

	private static long upload(long ctx, byte[] bytes, List<Long> owned) {
		long buffer = MetalBridge.newBuffer(ctx, Math.max(16, bytes.length));
		MemorySegment.ofAddress(MetalBridge.bufferContents(buffer)).reinterpret(bytes.length)
			.copyFrom(MemorySegment.ofArray(bytes));
		owned.add(buffer);
		return buffer;
	}

	/** The frame's bindings over the test's targets (the frame block; no pack blocks). */
	private record TestBindings(Targets targets, long frameBuffer) implements GbufferDelegate.Bindings {
		@Override
		public long[] frameBlock(String program) {
			return new long[] {this.frameBuffer, 0};
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
			return index >= 0 && index < this.targets.count()
				? write ? this.targets.write(index) : this.targets.read(index) : 0;
		}

		@Override
		public long depth(String name) {
			return this.targets.depth(name.equals("depthtex1") ? 1 : name.equals("depthtex2") ? 2 : 0);
		}

		@Override
		public long noise() {
			return 0;
		}

		@Override
		public long defaultTexture(String name) {
			return 0;
		}

		@Override
		public void projection(float[] out) {
			new Matrix4f().get(out);
		}
	}
}
