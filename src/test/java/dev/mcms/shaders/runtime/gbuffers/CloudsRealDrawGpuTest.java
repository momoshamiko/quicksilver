// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout.UniformDescription;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.runtime.DimensionSet;
import dev.mcms.shaders.runtime.Targets;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import dev.mcms.shaders.runtime.uniforms.FrameUniforms;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The clouds at the game's scale through the delegate (plan.md E4, the clouds root cause). {@link CloudsDelegateGpuTest}
 * draws one quad, non-indexed, with an identity projection; the game draws its clouds indexed, with the sequential
 * QUADS index buffer ({@code drawIndexed(6 x quads)}), a faces buffer of thousands of quads, a real projection and the
 * view's rotation. These tests run that shape: a field of bottom faces above the camera, looking up, into a depth
 * attachment cleared to 1.0 (the engine's translucent pass), with a 32-bit and a 16-bit index buffer (the game picks
 * 16-bit for a draw this small), and check the pixels the program writes.
 */
@Tag("gpu")
class CloudsRealDrawGpuTest {

	private static final int W = 128, H = 96;
	private static final float FOV = 70f, FAR = 512f;
	/** The game's CellSize (CloudRenderer: 12 blocks wide, 4 high) and the bottom face's grey (clouds.vsh: 0.7). */
	private static final float CELL_X = 12f, CELL_Y = 4f, CELL_Z = 12f, BOTTOM_GREY = 0.7f;
	/** Cells per side of the test's cloud field; the quads are cells squared (CloudRenderer's buffer grows the same way). */
	static final int CELLS = 96, QUADS = CELLS * CELLS;
	static final float[] CLOUD_OFFSET = {-576f, 42f, -200f};
	private static final float[] CLOUD_COLOR = {1f, 1f, 1f, 1f};
	private static PackModel model;
	private static CustomUniforms custom;

	@BeforeAll
	static void loadPack() throws Exception {
		model = GbufferFixtures.classesModel();
		custom = CustomUniforms.of(model);
	}

	@Test
	void theCloudsDrawnWithA32BitIndexBufferWritePixels() {
		assertCloudsWrite(false);
	}

	@Test
	void theCloudsDrawnWithA16BitIndexBufferWritePixels() {
		assertCloudsWrite(true);
	}

	/** Draws the cloud field through the delegate and checks the pixels the bottom faces write. */
	private static void assertCloudsWrite(boolean shortIndices) {
		// Looking up 20 degrees: the cloud layer (42 blocks above the eye) fills the top of the picture.
		Matrix4f view = new Matrix4f().rotationX((float) Math.toRadians(-20));
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context(), enc = gpu.encoder();
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
				List<UniformDescription> gameUniforms = GbufferFixtures.gamePipeline(pipeline).getBindGroupLayouts().stream()
					.flatMap(group -> group.uniforms().stream()).toList();
				assertNotNull(delegate.select(enc, pipeline, null, null, gameUniforms), pipeline + " must not be dropped");
				// The game's own draw bindings (CloudRenderer.render): DynamicTransforms (the view rotation, ColorModulator
				// white), CloudInfo and CloudFaces as whole buffers, then drawIndexed over the sequential QUADS indices.
				delegate.uniform(enc, "DynamicTransforms", transforms(ctx, view, owned));
				delegate.uniform(enc, "CloudInfo", cloudInfo(ctx, owned));
				delegate.uniform(enc, "CloudFaces", faces(ctx, owned));
				GpuBuffer indices = quadIndices(ctx, owned, shortIndices);
				MetalBridge.index(enc, indices, !shortIndices);
				assertTrue(delegate.beforeDraw(enc), "the clouds draw must run");
				MetalBridge.drawIndexed(enc, 6 * QUADS, 1, 0, 0, 0);
				MetalBridge.endEncoders(enc);
				MetalBridge.commitAndWait(enc);
				delegate.close();

				byte[] main = gpu.readTexture(targets.write(0), 0, 0, W, H, 1, 4);
				byte[] classes = gpu.readTexture(targets.write(4), 0, 0, W, H, 1, 1);
				int covered = 0;
				for (int i = 0; i < W * H; i++) {
					int r = main[i * 4] & 0xff, a = main[i * 4 + 3] & 0xff;
					if (a == 255 && Math.abs(r - Math.round(BOTTOM_GREY * 255)) <= 1) {
						covered++;
						assertEquals(3, classes[i] & 0xff, "gbuffers_clouds' class marker where the cloud was written");
					}
				}
				assertTrue(covered > W * H / 20,
					"the clouds must write their bottom faces: " + covered + " of " + (W * H) + " pixels were written");
				targets.release();
			} finally {
				owned.release();
			}
		}
	}

	/** DynamicTransforms: ModelViewMat (the view rotation) at 0, the colour modulator at 128, as the game writes them. */
	static GpuBufferSlice transforms(long ctx, Matrix4f view, GbufferFixtures.Owned owned) {
		GpuBuffer buffer = MetalBridge.newGpuBuffer(ctx, 160);
		ByteBuffer bytes = ByteBuffer.allocate(160).order(ByteOrder.LITTLE_ENDIAN);
		float[] mv = new float[16];
		view.get(mv);
		for (float v : mv) {
			bytes.putFloat(v);
		}
		for (int i = 0; i < 16; i++) {
			bytes.putFloat(i % 5 == 0 ? 1f : 0f);  // TextureMat: identity
		}
		for (float v : new float[] {1f, 1f, 1f, 1f}) {
			bytes.putFloat(v);  // ColorModulator
		}
		for (int i = 0; i < 3; i++) {
			bytes.putFloat(0f);  // ModelOffset
		}
		GbufferFixtures.fill(buffer, bytes.array());
		owned.add(MetalBridge.bufferHandle(buffer));
		return buffer.slice();
	}

	/** CloudInfo (std140): vec4 CloudColor at 0, vec3 CloudOffset at 16, vec3 CellSize at 32, over this test's field. */
	static GpuBuffer cloudInfo(long ctx, GbufferFixtures.Owned owned) {
		return cloudInfo(ctx, owned, CLOUD_OFFSET);
	}

	/** CloudInfo with the given CloudOffset (a mesh-sized field centres itself on the camera with it). */
	static GpuBuffer cloudInfo(long ctx, GbufferFixtures.Owned owned, float[] cloudOffset) {
		GpuBuffer buffer = MetalBridge.newGpuBuffer(ctx, 48);
		ByteBuffer bytes = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
		for (float v : CLOUD_COLOR) {
			bytes.putFloat(v);
		}
		for (float v : cloudOffset) {
			bytes.putFloat(v);
		}
		bytes.putFloat(0f);
		for (float v : new float[] {CELL_X, CELL_Y, CELL_Z}) {
			bytes.putFloat(v);
		}
		bytes.putFloat(0f);
		GbufferFixtures.fill(buffer, bytes.array());
		owned.add(MetalBridge.bufferHandle(buffer));
		return buffer;
	}

	/**
	 * CloudFaces exactly as the game's {@code encodeFace} packs it: three BYTES per quad ({@code cellX >> 1},
	 * {@code cellZ >> 1}, then direction 0 with cellX's and cellZ's low bits in 7 and 6 — the pipeline's layout
	 * declares the buffer R8_SINT); every quad is a bottom face, what {@code buildExtrudedCell} builds below the
	 * clouds (javap 2026-11-14).
	 */
	static GpuBuffer faces(long ctx, GbufferFixtures.Owned owned) {
		return GbufferFixtures.gpuBuffer(ctx, facesBytes(), owned);
	}

	/** The field's packed faces: a bottom face per cell, three bytes each (encodeFace). */
	static byte[] facesBytes() {
		ByteBuffer bytes = ByteBuffer.allocate(QUADS * 3);
		for (int z = 0; z < CELLS; z++) {
			for (int x = 0; x < CELLS; x++) {
				bytes.put((byte) (x >> 1)).put((byte) (z >> 1))
					.put((byte) (((x & 1) << 7) | ((z & 1) << 6)));
			}
		}
		return bytes.array();
	}

	static GpuBuffer quadIndices(long ctx, GbufferFixtures.Owned owned, boolean shortIndices) {
		return quadIndices(ctx, owned, shortIndices, QUADS);
	}

	/** The sequential QUADS index buffer for {@code quads} quads (a mesh smaller than this field's). */
	static GpuBuffer quadIndices(long ctx, GbufferFixtures.Owned owned, boolean shortIndices, int quads) {
		int width = shortIndices ? 2 : 4;
		GpuBuffer buffer = MetalBridge.newGpuBuffer(ctx, 6L * quads * width);
		ByteBuffer bytes = ByteBuffer.allocate(6 * quads * width).order(ByteOrder.LITTLE_ENDIAN);
		int[] pattern = {0, 1, 2, 2, 3, 0};
		for (int q = 0; q < quads; q++) {
			for (int p : pattern) {
				if (shortIndices) {
					bytes.putShort((short) (4 * q + p));
				} else {
					bytes.putInt(4 * q + p);
				}
			}
		}
		GbufferFixtures.fill(buffer, bytes.array());
		owned.add(MetalBridge.bufferHandle(buffer));
		return buffer;
	}
}
