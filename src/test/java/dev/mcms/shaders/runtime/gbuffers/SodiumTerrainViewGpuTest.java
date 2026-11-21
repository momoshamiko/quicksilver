// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout.UniformDescription;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.DimensionSet;
import dev.mcms.shaders.runtime.EngineSlots;
import dev.mcms.shaders.runtime.Targets;
import dev.mcms.shaders.runtime.terrain.SodiumGlobals;
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
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A Sodium terrain quad lands where its player-space position projects, through the pack's terrain program (plan.md E4,
 * "Sodium terrain"; ADR 0007). Sodium's terrain positions are camera-relative: its chunk shader computes
 * {@code u_ProjectionMatrix * u_ModelViewMatrix * (vertex + region offset [- u_CameraOffset])} with a model-view that is
 * the view rotation alone (block_layer_opaque.vsh, both allowed jars). The draw log of 2026-11-14 showed the engine
 * serving an identity model-view for these draws, so the pack's {@code gl_ModelViewMatrix * gl_Vertex} lost the camera's
 * rotation and the terrain projected behind the camera.
 *
 * <p>The scene: the camera at a fractional position, pitch 12 degrees, yaw 0 (looking +Z). The expected pixel comes from
 * the pinhole model written out here (right, up and forward vectors of the camera), not from the engine's matrices.
 * Two globals layouts are tested: beta.1's (no {@code u_CameraOffset}, the region offset carries the whole camera) and
 * beta.5's (the region offset is the integer camera position, and the prologue subtracts {@code u_CameraOffset}). Both
 * must put the quad at the same place. The layouts are written from the two jars' {@code globals.glsl} shape, which
 * {@code SodiumGlobalsLayoutTest} checks against the jars themselves.
 */
@Tag("gpu")
class SodiumTerrainViewGpuTest {

	private static final String GLOBALS_BETA1 = "layout(std140) uniform u_Globals {\n"
		+ "mat4 u_ProjectionMatrix;\nmat4 u_ModelViewMatrix;\nvec4 u_FogColor;\nvec2 u_EnvironmentFog;\nvec2 u_RenderFog;\n"
		+ "vec2 u_TexelSize;\nvec2 u_TexCoordShrink;\nfloat u_FadePeriodInv;\nbool u_UseRGSS;\n};";
	private static final String GLOBALS_BETA5 = GLOBALS_BETA1.replace("vec4 u_FogColor;",
		"vec4 u_FogColor;\nvec3 u_CameraOffset;");

	private static final int R8 = 10;
	private static final int RGBA8 = 70;
	private static final int W = 64, H = 48;
	private static final float FOV_Y = 70f, FAR = 192f;
	/** The class of gbuffers_terrain in mcms-classes (colortex4 holds it). */
	private static final int TERRAIN_CLASS = 4;
	private static PackModel model;
	private static CustomUniforms custom;

	private static final VertexFormat EXTENDED = TerrainVertexType.INSTANCE.getVertexFormat();

	/** The camera: pitch 12 degrees, yaw 0, at a fractional position (its integer part is the section's). */
	private static final double PITCH = Math.toRadians(12);
	private static final double[] CAMERA = {3.75, 100.75, -1.25};
	/** The section origin: the camera's integer position. */
	private static final double[] ORIGIN = {3, 100, -2};
	/** The camera's view basis in player space: right, up, forward (yaw 0 looks +Z; pitch positive looks down). */
	private static final double[] RIGHT = {-1, 0, 0};
	private static final double[] UP = {0, Math.cos(PITCH), Math.sin(PITCH)};
	private static final double[] FORWARD = {0, -Math.sin(PITCH), Math.cos(PITCH)};

	@BeforeAll
	static void loadPack() throws Exception {
		Path pack = null;
		Path dir = Path.of(System.getProperty("user.dir"));
		for (int i = 0; i < 6 && pack == null; i++) {
			Path candidate = dir.resolve(Path.of("bench", "test-packs", "mcms-classes"));
			if (Files.isDirectory(candidate)) {
				pack = candidate;
			}
			dir = dir.getParent();
		}
		assertTrue(pack != null, "bench/test-packs/mcms-classes not found");
		try (PackSource source = PackSource.open(pack)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		assertTrue(model.problems().isEmpty(), "the test pack must load clean: " + model.problems());
		custom = CustomUniforms.of(model);
	}

	/** beta.1's layout: no camera offset; the region offset is the origin minus the whole camera. */
	@Test
	void beta1TerrainQuadLandsWhereItsPlayerSpacePositionProjects() {
		assertQuadLandsAtItsPosition(GLOBALS_BETA1, false);
	}

	/** beta.5's layout: the region offset is the origin minus the integer camera; the prologue subtracts the fraction. */
	@Test
	void beta5TerrainQuadLandsWhereItsPlayerSpacePositionProjects() {
		assertQuadLandsAtItsPosition(GLOBALS_BETA5, true);
	}

	private static void assertQuadLandsAtItsPosition(String globalsText, boolean beta5) {
		SodiumGlobals globals = SodiumGlobals.parse(globalsText);
		assertTrue(globals.problems().isEmpty(), () -> "the test globals must parse: " + globals.problems());
		assertEquals(beta5 ? 144L : -1L, globals.cameraOffsetAt());
		PrologueFacts facts = new PrologueFacts(EngineSlots.PUSH_CONSTANTS, 0, TerrainUniforms.GLOBALS_SLOT,
			globals.cameraOffsetAt(), globals.offsetOf("u_ModelViewMatrix"));
		VertexLayoutRegistry.register(EXTENDED, TerrainLayout.layout(globals.offsetOf("u_TexCoordShrink")), facts);

		// The frac of the camera is what the region and the camera offset disagree on: on beta.1 the region offset
		// carries it, on beta.5 the camera offset does. Either way the vertex is the player-space point plus the fraction.
		double[] frac = {CAMERA[0] - ORIGIN[0], CAMERA[1] - ORIGIN[1], CAMERA[2] - ORIGIN[2]};
		double[] region = beta5 ? new double[] {0, 0, 0} : negate(frac);
		double[] cameraOffset = beta5 ? frac : null;

		// The quad: 1.5 blocks half-size, 8 blocks ahead, offset right and down, corners counter-clockwise in the view.
		double[] centre = add(add(scale(FORWARD, 8), scale(RIGHT, 1.5)), scale(UP, -1.2));
		double half = 1.5;
		double[][] corners = {
			add(add(centre, scale(RIGHT, -half)), scale(UP, -half)),
			add(add(centre, scale(RIGHT, half)), scale(UP, -half)),
			add(add(centre, scale(RIGHT, half)), scale(UP, half)),
			add(add(centre, scale(RIGHT, -half)), scale(UP, half)),
		};

		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long enc = gpu.encoder();
			List<Long> owned = new ArrayList<>();
			DimensionSet set = DimensionSet.load(ctx, "mcms-classes", model, model.rootFolder(), custom, RGBA8,
				model.rootFolder().union().colorFormats(), gamePipelines(), copy -> null);
			Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(),
				set.usedTargets(), false, false, W, H, Targets.engineTextures(ctx), null);
			try {
				assertTrue(set.absent() == 0, "the pack's programs must all run: " + set.notes());
				assertTrue(targets.create());
				GbufferPlans plans = set.plans();
				assertNotNull(plans, "the plans must build");

				FrameUniforms uniforms = new FrameUniforms();
				uniforms.update(inputs());
				ByteBuffer block = ByteBuffer.allocate(FrameUniforms.size()).order(ByteOrder.LITTLE_ENDIAN);
				uniforms.write(block);
				long frameBuffer = upload(ctx, block.array(), owned);
				List<UniformDescription> sodiumUniforms = List.of(
					new UniformDescription("u_BlockTex", UniformType.COMBINED_IMAGE_SAMPLER),
					new UniformDescription("u_LightTex", UniformType.COMBINED_IMAGE_SAMPLER),
					new UniformDescription("u_Globals", UniformType.UNIFORM_BUFFER));
				long[] colours = new long[plans.layout(GbufferPhase.OPAQUE).length];
				float[][] clears = new float[colours.length][];
				for (int i = 0; i < colours.length; i++) {
					colours[i] = targets.write(plans.layout(GbufferPhase.OPAQUE)[i]);
					clears[i] = new float[] {0, 0, 0, 0, 1};
				}
				MetalBridge.renderBegin(enc, colours, clears, targets.depth(0), true, 1.0f, W, H);

				GbufferDelegate delegate = new GbufferDelegate(plans, new TestBindings(targets, frameBuffer, uniforms));
				// Sodium's u_Globals mirror: the view rotation (Sodium's model-view), the projection, the camera offset.
				MemorySegment mirror = Arena.ofAuto().allocate(256, 16);
				MemorySegment.copy(globalsBytes(globals, cameraOffset), 0, mirror, ValueLayout.JAVA_BYTE, 0, 256);
				delegate.constantBytes(TerrainUniforms.GLOBALS_SLOT, mirror);
				delegate.begin(enc, true);
				delegate.phase(GbufferPhase.OPAQUE);
				int[] slots = delegate.select(enc, "sodium:pipeline/solid_terrain", null, null, sodiumUniforms);
				assertNotNull(slots, "the terrain pipeline must not be dropped");
				long atlas = whiteTexture(ctx, owned);
				long sampler = MetalBridge.samplerNew(ctx, 0, 0, 0, 0, 0, 1, 0.25f);
				owned.add(sampler);
				MetalBridge.renderTextureSlots(enc, slots[0], atlas, sampler, delegate.vertexSamplerSlot(0), delegate.fragmentSamplerSlot(0));
				// The region offset (push constants) of this section.
				MetalBridge.renderBuffer(enc, EngineSlots.PUSH_CONSTANTS, upload(ctx, floats(region), owned), 0);

				// The vertices are region-relative: the player-space corner plus the fraction the offsets remove.
				TerrainVertex[] quad = new TerrainVertex[4];
				for (int i = 0; i < 4; i++) {
					double[] local = add(corners[i], frac);
					quad[i] = new TerrainVertex();
					quad[i].x = (float) local[0];
					quad[i].y = (float) local[1];
					quad[i].z = (float) local[2];
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

				// Where the quad landed: the centroid of the terrain class's pixels (memory row 0 is the bottom row).
				byte[] classes = gpu.readTexture(targets.write(4), 0, 0, W, H, 1, 1);
				double sumX = 0, sumY = 0;
				int count = 0;
				for (int row = 0; row < H; row++) {
					for (int col = 0; col < W; col++) {
						if ((classes[row * W + col] & 0xff) == TERRAIN_CLASS) {
							sumX += col + 0.5;
							sumY += row + 0.5;
							count++;
						}
					}
				}
				assertTrue(count > 50, "the terrain quad must cover pixels (covered " + count + ")");
				double[] expected = pixelOf(centre);
				assertEquals(expected[0], sumX / count, 1.0, "the quad's centre column");
				assertEquals(expected[1], sumY / count, 1.0, "the quad's centre row");
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

	/**
	 * Sodium's u_Globals bytes for this camera: the projection (unused by the engine's terrain draws, which take the
	 * frame's), the model-view as the view rotation alone, the camera offset when the layout declares it.
	 */
	private static byte[] globalsBytes(SodiumGlobals globals, double[] cameraOffset) {
		ByteBuffer bytes = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
		float[] view = viewRotation();
		for (int i = 0; i < 16; i++) {
			bytes.putFloat((int) globals.offsetOf("u_ModelViewMatrix") + 4 * i, view[i]);
		}
		if (cameraOffset != null) {
			long at = globals.cameraOffsetAt();
			for (int i = 0; i < 3; i++) {
				bytes.putFloat((int) at + 4 * i, (float) cameraOffset[i]);
			}
		}
		return bytes.array();
	}

	/** The camera's view rotation, column-major: rows are right, up and minus forward (OpenGL's camera looks down -Z). */
	private static float[] viewRotation() {
		double[][] rows = {RIGHT, UP, negate(FORWARD)};
		float[] m = new float[16];
		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 3; column++) {
				m[4 * column + row] = (float) rows[row][column];
			}
		}
		m[15] = 1;
		return m;
	}

	/** The pixel (column, row) of a player-space point under the camera's pinhole model (memory row 0 is the bottom). */
	private static double[] pixelOf(double[] point) {
		double depth = dot(FORWARD, point);  // the distance in front of the camera (positive for a point ahead)
		double tan = Math.tan(Math.toRadians(FOV_Y) / 2);
		double aspect = (double) W / H;
		double ndcX = dot(RIGHT, point) / depth / (tan * aspect);
		double ndcY = dot(UP, point) / depth / tan;
		return new double[] {(ndcX + 1) / 2 * W, (ndcY + 1) / 2 * H};
	}

	private static double dot(double[] a, double[] b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	private static double[] add(double[] a, double[] b) {
		return new double[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]};
	}

	private static double[] scale(double[] a, double s) {
		return new double[] {a[0] * s, a[1] * s, a[2] * s};
	}

	private static double[] negate(double[] a) {
		return scale(a, -1);
	}

	private static byte[] floats(double[] values) {
		ByteBuffer bytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
		for (double value : values) {
			bytes.putFloat((float) value);
		}
		return bytes.array();
	}

	/** The game's own pipeline list the engine maps at install (Engine.gamePipelines's sources). */
	private static List<com.mojang.renderpearl.api.pipeline.RenderPipeline> gamePipelines() {
		List<com.mojang.renderpearl.api.pipeline.RenderPipeline> all = new ArrayList<>(
			net.minecraft.client.renderer.RenderPipelines.requiredPipelines());
		all.addAll(net.minecraft.client.renderer.RenderPipelines.optionalPipelines());
		return all;
	}

	/**
	 * A capture for the view: the camera at {@link #CAMERA}, its view rotation, pitch 12 and yaw 0 (the frame's projection
	 * is the GL-convention perspective of the field of view, the aspect and the far plane).
	 */
	private static FrameInputs inputs() {
		int width = W, height = H, renderDistanceChunks = 12, biomeId = 2, biomeCategory = 5;
		float partialTick = 0.5f, fovY = FOV_Y, farPlane = FAR;
		double cameraX = CAMERA[0], cameraY = CAMERA[1], cameraZ = CAMERA[2];
		double eyeX = CAMERA[0], eyeY = CAMERA[1] + 1.62, eyeZ = CAMERA[2];
		float yaw = 0, pitch = (float) Math.toDegrees(PITCH);
		float lookX = (float) FORWARD[0], lookY = (float) FORWARD[1], lookZ = (float) FORWARD[2];
		Matrix4f view = new Matrix4f();
		view.set(viewRotation(), 0);
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
			lookX, lookY, lookZ, view, fovY, farPlane, renderDistanceChunks, dayTime, sunAngleDegrees, moonPhaseOrdinal,
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

	private static long whiteTexture(long ctx, List<Long> owned) {
		long texture = ShaderNative.textureNew(ctx, RGBA8, ShaderNative.TYPE_2D, 4, 4, 1, 1,
			ShaderNative.USAGE_SHADER_READ, ShaderNative.STORAGE_PRIVATE);
		byte[] texels = new byte[4 * 4 * 4];
		Arrays.fill(texels, (byte) 0xff);
		assertTrue(ShaderNative.textureUpload(ctx, texture, texels, 16, 0, 0, 0, 0, 0, 0, 4, 4, 1));
		owned.add(texture);
		return texture;
	}

	/** The frame's bindings over the test's targets (the frame block; no pack blocks). */
	private record TestBindings(Targets targets, long frameBuffer, FrameUniforms uniforms) implements GbufferDelegate.Bindings {
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
			this.uniforms.gbufferProjection(out);
		}
	}
}
