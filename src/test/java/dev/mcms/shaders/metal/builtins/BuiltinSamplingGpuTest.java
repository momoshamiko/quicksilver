// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal.builtins;

import dev.mcms.glsl.msl.builtins.BuiltinCall;
import dev.mcms.glsl.msl.builtins.Mapping;
import dev.mcms.glsl.msl.builtins.Prelude;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GPU tests of texture sampling on mipmapped, cube and cube-array textures (W2-C, ADR 0006), through the table's MSL.
 * Textures are RGBA32F. A mip level's texel (x, y) channel c holds 1000 level + x + 10 y + 0.25 c + 0.5, and a cube
 * slice's texel holds 100 cube + 10 face + 0.25 c + 0.5 (the same value for every texel of the face), so each stored
 * value names its level, texel, cube and face and is exact in a float. Nearest filtering at texel centres must return
 * the stored bits exactly. textureQueryLod is compared within 1e-3 (the logarithm is a fast-math approximation). It is
 * fragment-only, so it runs in a full-screen pass over an 8 by 8 target with a constant footprint: every pixel covers
 * the same number of texels, so the level is known exactly. Compute kernels cover the explicit-level and
 * explicit-gradient forms (GLSL 4.60 8.9.2, 8.9.3), the size and level queries, and cube and cube-array faces.
 */
@Tag("gpu")
final class BuiltinSamplingGpuTest {
	private static final int RGBA32_FLOAT = HeadlessGpu.RGBA32_FLOAT;
	/** MTLTextureType values (Metal.framework): 2D, cube and cube array. */
	private static final int TYPE_2D = 2, TYPE_CUBE = 5, TYPE_CUBE_ARRAY = 6;
	/** MTLTextureUsageShaderRead (1) with shader write; MTLStorageModePrivate (2). */
	private static final int USAGE_READ_WRITE = ShaderNative.USAGE_SHADER_WRITE | 1, STORAGE_PRIVATE = 2;
	private static final int BYTES_PER_TEXEL = 16;
	private static final int TARGET = 8;
	private static final float LOD_TOLERANCE = 1e-3f;

	private static float mipTexel(int level, int x, int y, int c) {
		return 1000f * level + x + 10f * y + 0.25f * c + 0.5f;
	}

	private static float faceTexel(int cube, int face, int c) {
		return 100f * cube + 10f * face + 0.25f * c + 0.5f;
	}

	private static int fbits(float v) {
		return Float.floatToRawIntBits(v);
	}

	/** A 2D texture of levels mip levels (each half the size of the one above), every level filled. */
	private static long mipTexture(long ctx, int levels, int size) {
		long tex = create(ctx, TYPE_2D, size, size, 1, levels);
		for (int m = 0; m < levels; m++) {
			int s = Math.max(1, size >> m);
			ByteBuffer b = ByteBuffer.allocate(s * s * BYTES_PER_TEXEL).order(ByteOrder.LITTLE_ENDIAN);
			for (int y = 0; y < s; y++)
				for (int x = 0; x < s; x++)
					for (int c = 0; c < 4; c++) b.putFloat(mipTexel(m, x, y, c));
			upload(ctx, tex, b.array(), (long) s * BYTES_PER_TEXEL, 0, m, s, s);
		}
		return tex;
	}

	/** A cube (type TYPE_CUBE) or a cube array of cubes cubes (type TYPE_CUBE_ARRAY): slice 6 * cube + face. */
	private static long cube(long ctx, int type, int size, int cubes) {
		long tex = create(ctx, type, size, size, type == TYPE_CUBE ? 1 : cubes, 1);
		for (int cube = 0; cube < cubes; cube++) {
			for (int face = 0; face < 6; face++) {
				ByteBuffer b = ByteBuffer.allocate(size * size * BYTES_PER_TEXEL).order(ByteOrder.LITTLE_ENDIAN);
				for (int i = 0; i < size * size; i++)
					for (int c = 0; c < 4; c++) b.putFloat(faceTexel(cube, face, c));
				upload(ctx, tex, b.array(), (long) size * BYTES_PER_TEXEL, 6 * cube + face, 0, size, size);
			}
		}
		return tex;
	}

	private static long create(long ctx, int type, int w, int h, int depth, int levels) {
		long t = ShaderNative.textureNew(ctx, RGBA32_FLOAT, type, w, h, depth, levels, USAGE_READ_WRITE, STORAGE_PRIVATE);
		assertTrue(t != 0, "the device rejected the texture (type " + type + ")");
		return t;
	}

	private static void upload(long ctx, long tex, byte[] bytes, long bytesPerRow, int slice, int mip, int w, int h) {
		assertTrue(ShaderNative.textureUpload(ctx, tex, bytes, bytesPerRow, 0, slice, mip, 0, 0, 0, w, h, 1),
			"texture upload failed (slice " + slice + ", mip " + mip + ")");
	}

	/** The table's MSL for one call; names are the MSL arguments with the texture first (named tex, sampler smp). */
	private static Mapping.Mapped mapped(String name, String result, List<String> argTypes, List<String> names) {
		Mapping m = BuiltinKernel.TABLE.map(new BuiltinCall(name, argTypes, result, names,
			new BuiltinCall.Resource("tex", "smp")));
		assertTrue(m instanceof Mapping.Mapped, () -> name + " is not mapped: " + m);
		return (Mapping.Mapped) m;
	}

	/** Compute statements: each call's result is stored at the next words of out; expected holds those words. */
	private static final class Calls {
		private final StringBuilder body = new StringBuilder();
		private final List<Integer> expected = new ArrayList<>();
		private final Set<String> helpers = new LinkedHashSet<>();
		private int at = 0;

		void add(String name, String result, List<String> argTypes, List<String> argMsl, int... words) {
			List<String> names = new ArrayList<>();
			names.add("tex");
			names.addAll(argMsl);
			Mapping.Mapped m = mapped(name, result, argTypes, names);
			helpers.addAll(m.helpers());
			body.append("\t{\n\t\tauto r = ").append(m.msl()).append(";\n")
				.append(BuiltinKernel.store(result, "r", at)).append("\t}\n");
			at += BuiltinKernel.words(result);
			for (int w : words) expected.add(w);
		}
	}

	/** Runs a compute kernel that samples tex (a texture of the given MSL type) and returns the stored words. */
	private static int[] compute(String texType, long tex, Calls calls) {
		String msl = "#include <metal_stdlib>\nusing namespace metal;\n" + Prelude.render(calls.helpers)
			+ "kernel void k(" + texType + " tex [[texture(0)]], device uint *out [[buffer(0)]]) {\n"
			+ "\tconstexpr sampler smp(filter::nearest, mip_filter::nearest, address::clamp_to_edge, coord::normalized);\n"
			+ calls.body + "}\n";
		int words = calls.at;
		try (HeadlessGpu gpu = new HeadlessGpu().with(ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true)) {
			byte[][] out = gpu.runKernelOn(msl, "k", new long[]{tex}, new byte[0][], new int[]{Math.max(16, words * 4)},
				1, 1, 1, 1, 1, 1);
			int[] got = new int[words];
			ByteBuffer.wrap(out[0]).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(got);
			return got;
		}
	}

	/** Reports every word that differs from the expected word, by its index. */
	private static List<String> diffWords(int[] got, Calls calls) {
		List<String> failures = new ArrayList<>();
		for (int i = 0; i < got.length; i++)
			if (got[i] != calls.expected.get(i))
				failures.add("word " + i + " got 0x" + Integer.toHexString(got[i]) + " expected 0x"
					+ Integer.toHexString(calls.expected.get(i)));
		return failures;
	}

	/**
	 * A full-screen pass: the fragment body runs per pixel of an 8 by 8 RGBA32F target with pos (pixel centres) and the
	 * uniform u; it must return a float4. Returns the target's floats, row-major, four per pixel.
	 */
	private static float[] fragment(String sampler, long tex, float[] uniform, String body) {
		String msl = "#include <metal_stdlib>\nusing namespace metal;\n"
			+ "vertex float4 vs(uint vid [[vertex_id]]) {\n"
			+ "\tfloat2 p = float2((vid << 1) & 2, vid & 2);\n\treturn float4(p * 2.0 - 1.0, 0.0, 1.0);\n}\n"
			+ "fragment float4 fs(texture2d<float> tex [[texture(0)]], constant float4 &u [[buffer(0)]], "
			+ "float4 pos [[position]]) {\n"
			+ "\tconstexpr sampler smp(" + sampler + ");\n" + body + "}\n";
		ByteBuffer ub = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
		for (float f : uniform) ub.putFloat(f);
		try (HeadlessGpu gpu = new HeadlessGpu().with(ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true)) {
			byte[][] px = gpu.renderFullscreenOn(msl, "vs", "fs", new int[]{RGBA32_FLOAT}, TARGET, TARGET,
				new long[]{tex}, ub.array(), null);
			float[] out = new float[TARGET * TARGET * 4];
			ByteBuffer.wrap(px[0]).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);
			return out;
		}
	}

	/**
	 * textureQueryLod (GLSL 4.60 8.9.1): x is the level accessed (clamped to the texture's levels), y the level computed
	 * before clamping. A footprint of s texels per pixel gives log2(s) on every pixel, so the levels are known exactly.
	 */
	@Test
	void textureQueryLodReportsTheAccessedLevelAndTheUnclampedLevel() {
		String msl = mapped("textureQueryLod", "vec2", List.of("sampler2D", "vec2"), List.of("tex", "uv")).msl();
		String body = "\tfloat2 uv = pos.xy * u.xy;\n\tfloat2 r = " + msl + ";\n\treturn float4(r.x, r.y, 0.0, 1.0);\n";
		String sampler = "filter::linear, mip_filter::linear, address::clamp_to_edge, coord::normalized";
		float log3 = (float) (Math.log(3) / Math.log(2));
		// {texels per pixel, accessed level, computed level}. The texture has 5 levels (16 down to 1), so the highest
		// accessible level is 4; a footprint of 64 texels per pixel computes level 6.
		float[][] cases = {{4f, 2f, 2f}, {64f, 4f, 6f}, {0.25f, 0f, -2f}, {3f, log3, log3}};
		List<String> failures = new ArrayList<>();
		long tex = mipTexture(new HeadlessGpu().context(), 5, 16);
		try {
			for (float[] c : cases) {
				float k = c[0] / 16f;  // uv per pixel = texels per pixel / texture width
				float[] px = fragment(sampler, tex, new float[]{k, k, 0f, 0f}, body);
				for (int i = 0; i < px.length; i += 4)
					if (Math.abs(px[i] - c[1]) > LOD_TOLERANCE || Math.abs(px[i + 1] - c[2]) > LOD_TOLERANCE)
						failures.add("footprint " + c[0] + " pixel " + i / 4 + ": got (" + px[i] + ", " + px[i + 1]
							+ "), expected (" + c[1] + ", " + c[2] + ")");
			}
		} finally {
			MetalBridge.release(tex);
		}
		assertTrue(failures.isEmpty(), () -> String.join("\n", failures.subList(0, Math.min(8, failures.size()))));
	}

	/**
	 * Explicit levels and gradients on a three-level texture, and the size and level queries. Texel (0, 1) of level 1 is
	 * at (0.25, 0.75). A gradient of 0.5 (in texture coordinates) per pixel along x and y is 2 texels per pixel on level
	 * 0, so lod 1.
	 */
	@Test
	void explicitLevelsGradientsAndSizeQueriesOnAMipmappedTexture() {
		long tex = mipTexture(new HeadlessGpu().context(), 3, 4);
		try {
			Calls c = new Calls();
			c.add("textureLod", "vec4", List.of("sampler2D", "vec2", "float"), List.of("float2(0.25, 0.75)", "1.0"),
				fbits(mipTexel(1, 0, 1, 0)), fbits(mipTexel(1, 0, 1, 1)), fbits(mipTexel(1, 0, 1, 2)),
				fbits(mipTexel(1, 0, 1, 3)));
			// level 2 is 1 by 1: every texel coordinate is texel (0, 0).
			c.add("textureLod", "vec4", List.of("sampler2D", "vec2", "float"), List.of("float2(0.25, 0.25)", "2.0"),
				fbits(mipTexel(2, 0, 0, 0)), fbits(mipTexel(2, 0, 0, 1)), fbits(mipTexel(2, 0, 0, 2)),
				fbits(mipTexel(2, 0, 0, 3)));
			// (0.75, 0.25) at lod 1 is texel (1, 0) of level 1 (nearest mip filter, lod 1 is level 1).
			c.add("textureGrad", "vec4", List.of("sampler2D", "vec2", "vec2", "vec2"),
				List.of("float2(0.75, 0.25)", "float2(0.5, 0.0)", "float2(0.0, 0.5)"),
				fbits(mipTexel(1, 1, 0, 0)), fbits(mipTexel(1, 1, 0, 1)), fbits(mipTexel(1, 1, 0, 2)),
				fbits(mipTexel(1, 1, 0, 3)));
			c.add("textureSize", "ivec2", List.of("sampler2D", "int"), List.of("1"), 2, 2);
			c.add("textureSize", "ivec2", List.of("sampler2D", "int"), List.of("0"), 4, 4);
			c.add("textureQueryLevels", "int", List.of("sampler2D"), List.of(), 3);
			List<String> failures = diffWords(compute("texture2d<float>", tex, c), c);
			assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
		} finally {
			MetalBridge.release(tex);
		}
	}

	/**
	 * Projective lookup (GLSL 4.60 8.9.2): P = (2 x, 2 y, 2) with x and y advancing 0.5 per pixel, so the projected
	 * coordinate advances 0.5 per pixel (divided by w = 2) and the footprint is 2 texels: lod 1, level 1 with nearest
	 * mip filtering. Pixel (x, y) reads level 1 texel (min(x, 1), min(y, 1)).
	 */
	@Test
	void textureProjSelectsTheLevelOfItsFootprintAndDividesByW() {
		long tex = mipTexture(new HeadlessGpu().context(), 3, 4);
		try {
			String msl = mapped("textureProj", "vec4", List.of("sampler2D", "vec3"), List.of("tex", "P")).msl();
			String body = "\tfloat3 P = float3(pos.x * u.x * 2.0, pos.y * u.y * 2.0, 2.0);\n\treturn " + msl + ";\n";
			float[] px = fragment("filter::nearest, mip_filter::nearest, address::clamp_to_edge, coord::normalized", tex,
				new float[]{0.5f, 0.5f, 0f, 0f}, body);
			List<String> failures = new ArrayList<>();
			for (int y = 0; y < TARGET; y++)
				for (int x = 0; x < TARGET; x++)
					for (int c = 0; c < 4; c++) {
						float want = mipTexel(1, Math.min(x, 1), Math.min(y, 1), c);
						float got = px[((y * TARGET) + x) * 4 + c];
						if (got != want) failures.add("pixel (" + x + ", " + y + ") channel " + c + " got " + got
							+ " expected " + want);
					}
			assertTrue(failures.isEmpty(), () -> String.join("\n", failures.subList(0, Math.min(8, failures.size()))));
		} finally {
			MetalBridge.release(tex);
		}
	}

	/** Each face of a cube is selected by the major axis of the direction (GLSL 4.60 8.9.2, Metal's face order). */
	@Test
	void cubeSamplingSelectsTheFaceAlongEachAxis() {
		long tex = cube(new HeadlessGpu().context(), TYPE_CUBE, 2, 1);
		try {
			String[] dirs = {"float3(1.0, 0.2, 0.3)", "float3(-1.0, 0.2, 0.3)", "float3(0.2, 1.0, 0.3)",
				"float3(0.2, -1.0, 0.3)", "float3(0.2, 0.3, 1.0)", "float3(0.2, 0.3, -1.0)"};
			Calls c = new Calls();
			for (int f = 0; f < 6; f++) {
				int[] words = new int[4];
				for (int ch = 0; ch < 4; ch++) words[ch] = fbits(faceTexel(0, f, ch));
				c.add("texture", "vec4", List.of("samplerCube", "vec3"), List.of(dirs[f]), words);
			}
			List<String> failures = diffWords(compute("texturecube<float>", tex, c), c);
			assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
		} finally {
			MetalBridge.release(tex);
		}
	}

	/**
	 * A cube array of two cubes: the fourth component of P selects the cube (layer 0 or 1) and the direction the face
	 * (GLSL 4.60 8.9.2: the layer is the last component of P for cube arrays).
	 */
	@Test
	void cubeArraySamplingSelectsTheLayerAndTheFace() {
		long tex = cube(new HeadlessGpu().context(), TYPE_CUBE_ARRAY, 2, 2);
		try {
			String[] dirs = {"1.0, 0.2, 0.3", "-1.0, 0.2, 0.3", "0.2, 1.0, 0.3", "0.2, -1.0, 0.3", "0.2, 0.3, 1.0",
				"0.2, 0.3, -1.0"};
			Calls c = new Calls();
			for (int layer = 0; layer < 2; layer++) {
				for (int f = 0; f < 6; f++) {
					int[] words = new int[4];
					for (int ch = 0; ch < 4; ch++) words[ch] = fbits(faceTexel(layer, f, ch));
					c.add("texture", "vec4", List.of("samplerCubeArray", "vec4"),
						List.of("float4(" + dirs[f] + ", " + layer + ".0)"), words);
				}
			}
			List<String> failures = diffWords(compute("texturecube_array<float>", tex, c), c);
			assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
		} finally {
			MetalBridge.release(tex);
		}
	}
}
