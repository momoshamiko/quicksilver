// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.msl.ProgramTranslation.Reflection.ResourceUse;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.pack.DimensionPrograms;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import dev.mcms.shaders.runtime.uniforms.FrameUniforms;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Random;
import java.util.function.Function;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * composite7 at the VERY_HIGH options (E6 FXAA speckle, 2026-11-15) runs on the engine's own textures: colortex3's read texture
 * with its mip chain filled as the phase boundaries and fillMips fill it, the depth targets, and the pass block as EngineFrame
 * writes it. The test asserts the binding: colortex3 carries a mip chain, its level 1 is the 2x2 average of level 0, and the
 * pass's implicit-LOD lookups read level 0 (the hybrid mip rule, engine-pack-format.md section 7), so its output equals the
 * pass on fresh level-0 textures. The two zero-block lines print how much the pass block changes the output (diagnostics).
 */
@Tag("gpu")
@Tag("corpus")
class Composite7RuntimeBindingGpuTest {

	static final int W = 640, H = 360;
	static final int RGBA8 = 70, RGBA16F = 115, DEPTH32 = 252, TYPE_2D = 2;
	static final int RT = ShaderNative.USAGE_SHADER_READ | ShaderNative.USAGE_RENDER_TARGET;
	static final int UPLOAD = ShaderNative.USAGE_SHADER_READ | ShaderNative.USAGE_SHADER_WRITE;
	static final String PACK = "ComplementaryReimagined_r5.9.3.zip";

	@Test
	void composite7RuntimeVersusFresh() throws Exception {
		String dir = System.getProperty("mcms.test.corpus");
		Assumptions.assumeTrue(dir != null);
		Path zip = Path.of(dir).resolve(PACK);
		Properties options = new Properties();
		options.load(new StringReader(Files.readString(findUp("bench/pack-options/profile-veryhigh/" + PACK + ".txt"))));
		PackModel model;
		try (PackSource source = PackSource.open(zip)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", options);
		}
		CustomUniforms custom = CustomUniforms.of(model);
		DimensionPrograms dimension = Engine.programsOf(model, "minecraft:overworld");
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			DimensionSet set = DimensionSet.load(ctx, PACK, model, dimension, custom, RGBA8,
				dimension.union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
			try {
				CompiledProgram c7 = set.passes().stream().filter(p -> p.name().equals("composite7")).findFirst()
					.orElseThrow();
				Targets t = Engine.newTargets(set, model, W, H, ctx);
				if (!t.create()) {
					throw new IllegalStateException("targets");
				}
				assertTrue(t.mipmapped(3), "colortex3 carries a mip chain (composite6 enables it)");

				// Synthetic inputs: a shaded face with dithering noise, cloud-like blobs with hard edges, TAA-like history.
				byte[] c3 = image3(7);
				byte[] c2 = history2(11);
				byte[] d0 = depth(13);
				byte[] d1 = depth(17);
				byte[] frame = frameBytes();
				byte[] draw = passBlock();
				byte[] zeroDraw = new byte[draw.length];

				long[] fresh = new long[] {
					fresh(ctx, RGBA8, c3, UPLOAD), fresh(ctx, RGBA16F, c2, UPLOAD),
					fresh(ctx, DEPTH32, d0, UPLOAD), fresh(ctx, DEPTH32, d1, UPLOAD)};
				long freshOut = ShaderNative.textureNew(ctx, RGBA8, TYPE_2D, W, H, 1, 1, RT, ShaderNative.STORAGE_PRIVATE);

				Function<String, Long> runtime = name -> switch (name) {
					case "colortex3" -> t.read(3);
					case "colortex2" -> t.read(2);
					case "depthtex0" -> t.depth(0);
					case "depthtex1" -> t.depth(1);
					default -> throw new IllegalStateException("unexpected " + name);
				};
				Function<String, Long> reference = name -> switch (name) {
					case "colortex3" -> fresh[0];
					case "colortex2" -> fresh[1];
					case "depthtex0" -> fresh[2];
					case "depthtex1" -> fresh[3];
					default -> throw new IllegalStateException("unexpected " + name);
				};

				// The engine's own textures: colortex3's read texture carries its mip chain, filled as fillMips fills it.
				upload(ctx, t.read(3), c3, RGBA8);
				upload(ctx, t.read(2), c2, RGBA16F);
				upload(ctx, t.depth(0), d0, DEPTH32);
				upload(ctx, t.depth(1), d1, DEPTH32);
				long e0 = gpu.encoder();
				MetalBridge.generateMipmaps(e0, t.read(3));
				MetalBridge.commitAndWait(e0);
				byte[] level0 = gpu.readTexture(t.read(3), 0, 0, W, H, 1, 4);
				byte[] mip1 = gpu.readTexture(t.read(3), 0, 1, W / 2, H / 2, 1, 4);
				assertTrue(boxDiff(level0, mip1) <= 1, "mip level 1 is the 2x2 average of level 0 (one code value)");

				byte[] a = pass(gpu, c7, t.write(3), frame, draw, runtime);
				byte[] b = pass(gpu, c7, freshOut, frame, draw, reference);
				int[] vsRef = texelDiff(a, b);
				assertArrayEquals(b, a, "composite7 (no MipmapEnabled of its own) must read level 0 for its implicit lookups of the "
					+ "mipped colortex3: " + vsRef[0] + " texels differ from the mip-less reference, " + vsRef[1] + " darker");
				byte[] zero = pass(gpu, c7, t.write(3), frame, zeroDraw, runtime);
				int differ = 0;
				for (int i = 0; i < a.length; i++) {
					if (a[i] != zero[i]) {
						differ++;
					}
				}
				System.out.println("[c7-exp] bytes differing between the real pass block and an all-zero pass block: " + differ
					+ " of " + a.length);
				byte[] zeroFresh = pass(gpu, c7, freshOut, frame, zeroDraw, reference);
				System.out.println("[c7-exp] zero-block fresh vs real-block fresh differ in bytes: " + diffCount(zeroFresh, b));
			} finally {
				Engine.releaseSet(set);
			}
		}
	}

	private static byte[] pass(HeadlessGpu gpu, CompiledProgram c7, long out, byte[] frame, byte[] draw,
			Function<String, Long> textures) {
		long e = gpu.encoder();
		MetalBridge.renderBegin(e, new long[] {out}, new float[][] {MetalBridge.DONT_CARE}, 0, false, 0, W, H);
		MetalBridge.renderPipeline(e, c7.pipeline(), 0, false, false, 0, 0, 3);
		MetalBridge.renderBytes(e, EngineSlots.FRAME, frame);
		MetalBridge.renderBytes(e, EngineSlots.DRAW, draw);
		for (ResourceUse u : c7.reflection().textures()) {
			String name = u.name();
			if (name.equals("colortex3") || name.equals("colortex2") || name.startsWith("depthtex")) {
				MetalBridge.renderTexture(e, u.slot(), textures.apply(name));
			}
		}
		MetalBridge.renderDraw(e, 6, 1, 0, 0);  // the translated full-screen quad: two triangles
		MetalBridge.endEncoders(e);
		MetalBridge.commitAndWait(e);
		return gpu.readTexture(out, 0, 0, W, H, 1, 4);
	}

	/** Per texel (4 bytes): {texels with any byte differing from y, texels whose RGB sum in x is lower than in y}. */
	static int[] texelDiff(byte[] x, byte[] y) {
		int differing = 0, darker = 0;
		for (int i = 0; i < x.length; i += 4) {
			boolean any = false;
			int sx = 0, sy = 0;
			for (int c = 0; c < 4; c++) {
				any |= x[i + c] != y[i + c];
			}
			for (int c = 0; c < 3; c++) {
				sx += x[i + c] & 0xff;
				sy += y[i + c] & 0xff;
			}
			if (any) {
				differing++;
			}
			if (sx < sy) {
				darker++;
			}
		}
		return new int[] {differing, darker};
	}

	private static int diffCount(byte[] x, byte[] y) {
		int n = 0;
		for (int i = 0; i < x.length; i++) {
			if (x[i] != y[i]) {
				n++;
			}
		}
		return n;
	}

	/** The engine's pass block (EngineFrame.writePassBlock): identity model-view and texture matrices, the quad projection. */
	static byte[] passBlock() {
		float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
		float[] quad = {2, 0, 0, 0, 0, 2, 0, 0, 0, 0, -1, 0, -1, -1, 0, 1};
		float[] quadInverse = {0.5f, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0, -1, 0, 0.5f, 0.5f, 0, 1};
		float[] normal = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
		ByteBuffer bb = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
		int at = 0;
		for (float[] m : new float[][] {identity, quad, identity, quadInverse, identity}) {
			for (float v : m) {
				bb.putFloat(at, v);
				at += 4;
			}
		}
		for (float v : normal) {
			bb.putFloat(at, v);
			at += 4;
		}
		return bb.array();
	}

	private static int boxDiff(byte[] level0, byte[] mip1) {
		int max = 0;
		int w2 = W / 2;
		for (int y = 0; y < H / 2; y++) {
			for (int x = 0; x < w2; x++) {
				for (int c = 0; c < 4; c++) {
					int s = 0;
					for (int dy = 0; dy < 2; dy++) {
						for (int dx = 0; dx < 2; dx++) {
							s += level0[((2 * y + dy) * W + 2 * x + dx) * 4 + c] & 0xff;
						}
					}
					int avg = (s + 2) / 4;
					int got = mip1[(y * w2 + x) * 4 + c] & 0xff;
					max = Math.max(max, Math.abs(avg - got));
				}
			}
		}
		return max;
	}

	private static void upload(long ctx, long texture, byte[] bytes, int format) {
		int bpp = format == RGBA8 ? 4 : format == RGBA16F ? 8 : 4;
		if (!ShaderNative.textureUpload(ctx, texture, bytes, (long) W * bpp, 0, 0, 0, 0, 0, 0, W, H, 1)) {
			throw new IllegalStateException("upload refused for format " + format);
		}
	}

	private static long fresh(long ctx, int format, byte[] bytes, int usage) {
		long handle = ShaderNative.textureNew(ctx, format, TYPE_2D, W, H, 1, 1, usage, ShaderNative.STORAGE_PRIVATE);
		if (handle == 0) {
			throw new IllegalStateException("texture refused " + format);
		}
		upload(ctx, handle, bytes, format);
		return handle;
	}

	/** colortex3: a shaded face (gradient plus dithering noise) on the left, blobs with hard edges on the right. */
	static byte[] image3(long seed) {
		Random rng = new Random(seed);
		byte[] out = new byte[W * H * 4];
		for (int y = 0; y < H; y++) {
			for (int x = 0; x < W; x++) {
				boolean blob = ((x / 40) + (y / 40)) % 3 == 0 && x > W / 2;
				double base = blob ? 0.9 : 0.45 + 0.15 * x / W;
				for (int c = 0; c < 3; c++) {
					double v = base + (rng.nextDouble() - 0.5) * 0.04;
					out[(y * W + x) * 4 + c] = (byte) Math.round(Math.max(0, Math.min(1, v)) * 255);
				}
				out[(y * W + x) * 4 + 3] = (byte) 255;
			}
		}
		return out;
	}

	static byte[] history2(long seed) {
		Random rng = new Random(seed);
		ByteBuffer bb = ByteBuffer.allocate(W * H * 8).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < W * H * 4; i++) {
			bb.putShort(Float.floatToFloat16(0.3f + 0.4f * rng.nextFloat()));
		}
		return bb.array();
	}

	static byte[] depth(long seed) {
		Random rng = new Random(seed);
		ByteBuffer bb = ByteBuffer.allocate(W * H * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < W * H; i++) {
			bb.putFloat(0.995f + 0.0005f * rng.nextFloat());
		}
		return bb.array();
	}

	static byte[] frameBytes() {
		FrameUniforms fu = new FrameUniforms();
		ByteBuffer bb = ByteBuffer.allocate(FrameUniforms.size()).order(ByteOrder.LITTLE_ENDIAN);
		fu.write(bb);
		setFloat(bb, "viewWidth", W);
		setFloat(bb, "viewHeight", H);
		setFloat(bb, "aspectRatio", (float) W / H);
		setFloat(bb, "near", 0.05f);
		setFloat(bb, "far", 1024f);
		return bb.array();
	}

	private static void setFloat(ByteBuffer bb, String name, float v) {
		for (var f : FrameUniforms.schema(EngineSlots.FRAME).fields()) {
			if (f.name().equals(name)) {
				bb.putFloat(f.offset(), v);
				return;
			}
		}
		throw new IllegalStateException("no field " + name);
	}

	private static Path findUp(String rel) {
		Path dir = Path.of(System.getProperty("user.dir"));
		for (int i = 0; i < 8; i++) {
			if (Files.isRegularFile(dir.resolve(rel))) {
				return dir.resolve(rel);
			}
			dir = dir.getParent();
		}
		throw new IllegalStateException(rel + " not found");
	}
}
