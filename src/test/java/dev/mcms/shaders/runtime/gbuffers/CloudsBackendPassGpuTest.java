// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout.UniformDescription;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import dev.mcms.metal.BackendPassHarness;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.runtime.DimensionSet;
import dev.mcms.shaders.runtime.Targets;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import dev.mcms.shaders.runtime.uniforms.FrameUniforms;
import java.nio.ByteBuffer;
import java.util.List;
import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The clouds through the backend's real render-pass entry points (2026-11-14, E4 clouds root cause).
 * {@link CloudsRealDrawGpuTest} proved the engine draws the game's cloud shape when the delegate is called directly;
 * the game, though, drives everything through {@code FrontendRenderPass}: it resolves the uniform names to indices
 * and calls the backend's {@code setUniform(int, Object)}, and its {@code setUniform(String, GpuBuffer)} overload
 * converts a whole buffer to a slice first ({@code GpuBuffer.slice()}, javap: FrontendRenderPass). These tests run
 * that exact shape on a real {@code MetalRenderPass} with the delegate installed: the pipeline set, every game
 * uniform bound by index with the value the frontend would pass (the DynamicTransforms slice; CloudInfo and
 * CloudFaces as whole-buffer slices, the faces in the game's three-BYTES-per-quad packing, CloudRenderer.encodeFace
 * over an R8_SINT texel buffer, BindGroupLayouts.CLOUD_INFO), the sequential QUADS index buffer, then
 * {@code drawIndexed}. Before the fixes the delegate saw slices where it expected {@code GpuBuffer}s (no pixels), and
 * after that its texel view read four packed faces per R32 texel instead of one byte per R8 texel (no pixels again,
 * preview 9). The second test replays the mesh {@code buildExtrudedCell} builds for a camera BELOW the layer: bottom
 * faces, the field edges' side faces and the camera's own 3x3 cells' six inside faces (corners reversed, flag 16),
 * which the pack's winding and cull must not eat.
 */
@Tag("gpu")
class CloudsBackendPassGpuTest {

	private static final int W = 128, H = 96;
	private static final float FOV = 70f, FAR = 512f;
	/** The faces' greys (clouds.vsh): bottom 0.7, sides 0.8/0.9, top 1.0. */
	private static final float BOTTOM_GREY = 0.7f, SIDE_GREY = 0.8f, EAST_WEST_GREY = 0.9f, TOP_GREY = 1.0f;

	private static PackModel model;
	private static CustomUniforms custom;

	@BeforeAll
	static void loadPack() throws Exception {
		// MetalPipeline's cache directory asks Fabric for the game dir, which a headless test has none of.
		System.setProperty("mcms.metal.mslCache", "false");
		model = GbufferFixtures.classesModel();
		custom = CustomUniforms.of(model);
	}

	@Test
	void theCloudsDrawnThroughTheBackendsPassWritePixels() {
		// Looking up 20 degrees: the cloud layer fills the top of the picture (as CloudsRealDrawGpuTest's field).
		Matrix4f view = new Matrix4f().rotationX((float) Math.toRadians(-20));
		int[] written = drawThroughTheBackendPass(view, CloudsRealDrawGpuTest.facesBytes(),
			CloudsRealDrawGpuTest.QUADS, CloudsRealDrawGpuTest.CLOUD_OFFSET);
		assertTrue(near(written, BOTTOM_GREY) > W * H / 20,
			"the clouds must write their bottom faces through the backend's pass: " + near(written, BOTTOM_GREY)
				+ " of " + (W * H) + " pixels were written");
	}

	@Test
	void theBelowCameraMeshTheGameBuildsDraws() {
		// Looking up 60 degrees at a layer 42 above: the field fills the view, as the game's below-clouds view does.
		Matrix4f view = new Matrix4f().rotationX((float) Math.toRadians(-60));
		int[] written = drawThroughTheBackendPass(view, layerMesh(false), LAYER_QUADS,
			new float[] {-30f, 42f, -30f});  // the 5x5 field of 12-block cells centred on the camera, 42 blocks up
		assertTrue(near(written, BOTTOM_GREY) > W * H / 5, "the camera below the layer sees the bottom faces: "
			+ near(written, BOTTOM_GREY) + " pixels");
	}

	@Test
	void theInsideCameraMeshTheGameBuildsDrawsItsReversedFaces() {
		// The camera two blocks into the layer (the cells' bottom at -2, top at +2), looking straight ahead: the
		// 3x3 cells around it draw their six inside faces, corners REVERSED (flag 16) — the white ceiling overhead
		// and the grey floor below are those reversed faces (the regular UP faces are back-facing from below).
		Matrix4f view = new Matrix4f();
		int[] written = drawThroughTheBackendPass(view, layerMesh(true), LAYER_QUADS_INSIDE,
			new float[] {-30f, -2f, -30f});
		assertTrue(near(written, TOP_GREY) > W * H / 20, "the camera's own cells' reversed top faces survive the "
			+ "pack's winding and cull: " + near(written, TOP_GREY) + " white pixels");
		assertTrue(near(written, BOTTOM_GREY) > W * H / 20, "the reversed bottom faces below draw too: "
			+ near(written, BOTTOM_GREY) + " pixels");
	}

	/** The red value a face grey writes, as an index into the per-red pixel counts. */
	private static int index(float grey) {
		return Math.round(grey * 255);
	}

	/** The pixels whose red value is a face grey's within one (0.7 * 255 lands between two values). */
	private static int near(int[] byRed, float grey) {
		int centre = index(grey);
		return byRed[centre - 1] + byRed[centre] + byRed[Math.min(255, centre + 1)];
	}

	/** The cells of the mesh: a 5x5 field; the camera sits in the centre cell. */
	private static final int CELLS_LAYER = 5, LAYER_QUADS = 25 + 20 + 9 * 6, LAYER_QUADS_INSIDE = LAYER_QUADS + 25;

	/**
	 * The mesh {@code CloudRenderer.buildExtrudedCell} builds (javap 2026-11-14): every cell's bottom face — and its
	 * top face too when the camera is INSIDE the layer ({@code inside}: neither BELOW_CLOUDS nor ABOVE_CLOUDS) — the
	 * field edges' side faces toward the empty neighbours, and the camera's own 3x3 cells' six faces each with the
	 * inside flag (corners reversed, bit 4). Three bytes per quad, as {@code encodeFace} packs them.
	 */
	private static byte[] layerMesh(boolean inside) {
		ByteBuffer b = ByteBuffer.allocate((inside ? LAYER_QUADS_INSIDE : LAYER_QUADS) * 3);
		for (int z = 0; z < CELLS_LAYER; z++) {
			for (int x = 0; x < CELLS_LAYER; x++) {
				face(b, x, z, 0, 0);  // the DOWN face, unless ABOVE_CLOUDS
				if (inside) {
					face(b, x, z, 1, 0);  // the UP face, unless BELOW_CLOUDS
				}
				if (x == 0) {
					face(b, x, z, 4, 0);  // west neighbour empty
				}
				if (x == CELLS_LAYER - 1) {
					face(b, x, z, 5, 0);  // east neighbour empty
				}
				if (z == 0) {
					face(b, x, z, 2, 0);  // north neighbour empty
				}
				if (z == CELLS_LAYER - 1) {
					face(b, x, z, 3, 0);  // south neighbour empty
				}
				if (Math.abs(x - 2) <= 1 && Math.abs(z - 2) <= 1) {
					for (int dir = 0; dir < 6; dir++) {
						face(b, x, z, dir, 1 << 4);  // the inside faces, corners reversed
					}
				}
			}
		}
		assertTrue(b.remaining() == 0, "the mesh holds its quads");
		return b.array();
	}

	/** One {@code encodeFace} quad: cellX >> 1, cellZ >> 1, then the direction and flags with the cells' low bits. */
	private static void face(ByteBuffer b, int x, int z, int dir, int flags) {
		b.put((byte) (x >> 1)).put((byte) (z >> 1))
			.put((byte) (dir | flags | ((x & 1) << 7) | ((z & 1) << 6)));
	}

	/**
	 * Draws {@code quads} packed faces through the backend's real pass and returns, per red value, how many pixels
	 * that value covers (the classes target names the class where the bottoms wrote).
	 */
	private static int[] drawThroughTheBackendPass(Matrix4f view, byte[] packedFaces, int quads, float[] cloudOffset) {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			Object encoder = BackendPassHarness.newEncoder(ctx);
			long enc = BackendPassHarness.encoderHandle(encoder);
			GbufferFixtures.Owned owned = new GbufferFixtures.Owned();
			DimensionSet set = DimensionSet.load(ctx, "classes", model, model.rootFolder(), custom, GbufferFixtures.RGBA8,
				model.rootFolder().union().colorFormats(), GbufferFixtures.gamePipelines(), copy -> null);
			try {
				GbufferPlans plans = set.plans();
				String pipeline = "minecraft:pipeline/clouds";
				GbufferPlans.Plan plan = plans.planOf(pipeline);
				assertNotNull(plan, pipeline + " must have a plan");
				GbufferPlans.Variant variant = plan.variant(PassKind.GBUFFERS, GbufferPhase.TRANSLUCENT);
				assertTrue(variant instanceof GbufferPlans.PackVariant pv && pv.program().equals("gbuffers_clouds"),
					pipeline + " must draw through the pack's gbuffers_clouds in the translucent phase");

				int[] layout = plans.layout(GbufferPhase.TRANSLUCENT);
				Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(),
					set.usedTargets(), false, false, W, H, Targets.engineTextures(ctx), null);
				assertTrue(targets.create());
				long noise = ShaderNative.textureNew(ctx, GbufferFixtures.RGBA8, ShaderNative.TYPE_2D, 16, 16, 1, 1,
					ShaderNative.USAGE_SHADER_READ, ShaderNative.STORAGE_PRIVATE);
				owned.add(noise);

				FrameUniforms uniforms = new FrameUniforms();
				uniforms.update(GbufferFixtures.inputs(W, H, view, FOV, FAR));
				long[] frameBlock = GbufferFixtures.upload(ctx, GbufferFixtures.block(uniforms), owned);
				GbufferDelegate delegate = new GbufferDelegate(plans,
					new GbufferFixtures.Bindings(targets, noise, frameBlock, uniforms));

				long[] colours = new long[layout.length];
				for (int i = 0; i < colours.length; i++) {
					colours[i] = targets.write(layout[i]);
				}
				MetalBridge.renderBegin(enc, colours, null, targets.depth(0), true, 1f, W, H);
				delegate.reversedDepth(false);
				delegate.begin(enc, true);
				delegate.phase(GbufferPhase.TRANSLUCENT);
				// The game's uniform list (the flattened bind-group layouts the frontend resolves names in).
				RenderPipeline clouds = GbufferFixtures.gamePipeline(pipeline);
				List<UniformDescription> gameUniforms = clouds.getBindGroupLayouts().stream()
					.flatMap(group -> group.uniforms().stream()).toList();
				BackendRenderPipeline backendPipeline = BackendPassHarness.detachedPipeline(pipeline, gameUniforms);
				RenderPassBackend pass = BackendPassHarness.openPass(encoder, true, W, H, delegate);
				pass.setPipeline(backendPipeline);
				// FrontendRenderPass's conversion: the DynamicTransforms slice as the game sets it, CloudInfo and
				// CloudFaces as whole buffers converted to slices (GpuBuffer.slice(), offset 0, the buffer's length).
				GpuBufferSlice transforms = CloudsRealDrawGpuTest.transforms(ctx, view, owned);
				GpuBuffer cloudInfo = CloudsRealDrawGpuTest.cloudInfo(ctx, owned, cloudOffset);
				GpuBuffer faces = GbufferFixtures.gpuBuffer(ctx, packedFaces, owned);
				for (int i = 0; i < gameUniforms.size(); i++) {
					String name = gameUniforms.get(i).name();
					Object value = name.equals("DynamicTransforms") ? transforms
						: name.equals("CloudInfo") ? cloudInfo.slice()
						: name.equals("CloudFaces") ? faces.slice()
						: transforms;  // a block the pipeline's layout also lists (Projection and the like)
					pass.setUniform(i, value);
				}
				pass.setIndexBuffer(CloudsRealDrawGpuTest.quadIndices(ctx, owned, false, quads), IndexType.INT);
				pass.drawIndexed(6 * quads, 1, 0, 0, 0);
				MetalBridge.endEncoders(enc);
				MetalBridge.commitAndWait(enc);
				delegate.close();

				byte[] main = gpu.readTexture(targets.write(0), 0, 0, W, H, 1, 4);
				byte[] classes = gpu.readTexture(targets.write(4), 0, 0, W, H, 1, 1);
				int[] byRed = new int[256];
				for (int i = 0; i < W * H; i++) {
					int r = main[i * 4] & 0xff, a = main[i * 4 + 3] & 0xff;
					if (a == 255) {
						byRed[r]++;
						if (Math.abs(r - index(BOTTOM_GREY)) <= 1) {
							assertEquals(3, classes[i] & 0xff, "gbuffers_clouds' class marker where the cloud wrote");
						}
					}
				}
				targets.release();
				return byRed;
			} finally {
				owned.release();
			}
		}
	}
}
