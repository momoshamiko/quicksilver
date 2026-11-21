// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal.builtins;

import dev.mcms.glsl.msl.builtins.BuiltinCall;
import dev.mcms.glsl.msl.builtins.GlslTypes;
import dev.mcms.glsl.msl.builtins.Mapping;
import dev.mcms.glsl.msl.builtins.Prelude;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.HeadlessGpu.InputTexture;
import dev.mcms.shaders.metal.ShaderNative;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GPU tests of the texture and image mappings (W2-C, ADR 0006). Each case creates small textures, runs the table's
 * MSL for one GLSL call through a kernel that binds them, and compares the result bits with the texel values the GLSL
 * specification gives for nearest filtering at texel centres (exact). Texel (x, y, layer) of a float texture holds
 * the four channels (v, v+0.25, v+0.5, v+0.75) with v = x + 10y + 100 layer + 0.5; integer textures hold
 * (v, v+1, v+2, v+3) with v = x + 10y. Coordinates are texel centres: (i + 0.5) / size.
 */
@Tag("gpu")
final class BuiltinTextureGpuTest {
	private static final int RGBA32_FLOAT = HeadlessGpu.RGBA32_FLOAT, RGBA32_UINT = HeadlessGpu.RGBA32_UINT;
	private static final int R32_UINT = HeadlessGpu.R32_UINT, DEPTH32 = HeadlessGpu.DEPTH32_FLOAT;

	/** A texture under test: format, MTL type, size, the MSL texture class and its texels (bytes). */
	private record Tex(InputTexture input, String mslTexture) {
	}

	private static float texelF(int x, int y, int layer, int channel) {
		return (x + 10 * y + 100 * layer + 0.5f) + 0.25f * channel;
	}

	static Tex rgba2d(int w, int h) {
		ByteBuffer b = ByteBuffer.allocate(w * h * 16).order(ByteOrder.LITTLE_ENDIAN);
		for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) for (int c = 0; c < 4; c++) b.putFloat(texelF(x, y, 0, c));
		return new Tex(InputTexture.tex2d(RGBA32_FLOAT, w, h, b.array()), "texture2d<float>");
	}

	static Tex rgba2dArray(int w, int h, int layers) {
		ByteBuffer b = ByteBuffer.allocate(w * h * layers * 16).order(ByteOrder.LITTLE_ENDIAN);
		for (int l = 0; l < layers; l++) for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
			for (int c = 0; c < 4; c++) b.putFloat(texelF(x, y, l, c));
		return new Tex(new InputTexture(RGBA32_FLOAT, 3, w, h, layers, b.array()), "texture2d_array<float>");
	}

	static Tex rgba3d(int w, int h, int d) {
		ByteBuffer b = ByteBuffer.allocate(w * h * d * 16).order(ByteOrder.LITTLE_ENDIAN);
		for (int z = 0; z < d; z++) for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
			for (int c = 0; c < 4; c++) b.putFloat(texelF(x, y, z, c));
		return new Tex(InputTexture.tex3d(RGBA32_FLOAT, w, h, d, b.array()), "texture3d<float>");
	}

	static Tex depth2d(int w, int h, float value) {
		ByteBuffer b = ByteBuffer.allocate(w * h * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < w * h; i++) b.putFloat(value);
		return new Tex(InputTexture.tex2d(DEPTH32, w, h, b.array()), "depth2d<float>");
	}

	static Tex rgbaUint2d(int w, int h) {
		ByteBuffer b = ByteBuffer.allocate(w * h * 16).order(ByteOrder.LITTLE_ENDIAN);
		for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) for (int c = 0; c < 4; c++) b.putInt(x + 10 * y + c);
		return new Tex(InputTexture.tex2d(RGBA32_UINT, w, h, b.array()), "texture2d<uint>");
	}

	static Tex r32uImage(int w, int h) {
		ByteBuffer b = ByteBuffer.allocate(w * h * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) b.putInt(x + 10 * y);
		return new Tex(InputTexture.tex2d(R32_UINT, w, h, b.array()), "texture2d<uint, access::read_write>");
	}

	private static int fbits(float v) {
		return Float.floatToRawIntBits(v);
	}

	private static int[] vec4f(int x, int y, int layer) {
		int[] w = new int[4];
		for (int c = 0; c < 4; c++) w[c] = fbits(texelF(x, y, layer, c));
		return w;
	}

	/** Runs one texture call with no kernel code before the call. */
	static String run(String label, String function, List<String> argTypes, String result, List<String> argMsl,
					  Tex tex, boolean shadow, int[] expected) {
		return runWith("", label, function, argTypes, result, argMsl, tex, shadow, expected);
	}

	/**
	 * Runs one texture call. argTypes are the GLSL types (the first is the sampler or image); argMsl the MSL text of
	 * each argument after the first (the texture is named tex, its sampler smp); expected is the result words.
	 * preamble is kernel code placed before the call (declares a variable an argument names).
	 */
	static String runWith(String preamble, String label, String function, List<String> argTypes, String result,
						  List<String> argMsl, Tex tex, boolean shadow, int[] expected) {
		List<String> names = new ArrayList<>();
		names.add("tex");
		names.addAll(argMsl);
		BuiltinCall call = new BuiltinCall(function, argTypes, result, names, new BuiltinCall.Resource("tex", "smp"));
		Mapping mapping = BuiltinKernel.TABLE.map(call);
		if (!(mapping instanceof Mapping.Mapped mapped))
			return label + ": unsupported: " + ((Mapping.Unsupported) mapping).reason();
		String store = BuiltinKernel.store(result, "r", 0);
		Set<String> helpers = new LinkedHashSet<>(mapped.helpers());
		String sampler = shadow
			? "constexpr sampler smp(filter::nearest, address::clamp_to_edge, coord::normalized, compare_func::less_equal);"
			: "constexpr sampler smp(filter::nearest, address::clamp_to_edge, coord::normalized);";
		String msl = "#include <metal_stdlib>\nusing namespace metal;\n" + Prelude.render(helpers)
			+ "kernel void k(" + tex.mslTexture() + " tex [[texture(0)]], device uint *out [[buffer(0)]]) {\n"
			+ "\t" + sampler + "\n" + preamble
			+ "\tauto r = " + mapped.msl() + ";\n" + store + "}\n";
		int words = expected.length;
		try (HeadlessGpu gpu = new HeadlessGpu().with(ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true)) {
			byte[][] out = gpu.runKernel(msl, "k", new InputTexture[]{tex.input()}, new byte[0][],
				new int[]{Math.max(16, words * 4)}, 1, 1, 1, 1, 1, 1);
			int[] got = new int[words];
			ByteBuffer.wrap(out[0]).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(got);
			for (int i = 0; i < words; i++)
				if (got[i] != expected[i])
					return label + ": word " + i + " got 0x" + Integer.toHexString(got[i]) + " expected 0x"
						+ Integer.toHexString(expected[i]) + " (msl: " + mapped.msl() + ")";
			return null;
		}
	}

	private static void check(List<String> failures, String failure) {
		if (failure != null) failures.add(failure);
	}

	@Test
	void sampling2DNearestAtTexelCentres() {
		Tex t = rgba2d(4, 4);
		List<String> f = new ArrayList<>();
		check(f, run("texture", "texture", List.of("sampler2D", "vec2"), "vec4", List.of("float2(0.375, 0.625)"), t, false,
			vec4f(1, 2, 0)));
		check(f, run("textureLod", "textureLod", List.of("sampler2D", "vec2", "float"), "vec4",
			List.of("float2(0.375, 0.625)", "0.0"), t, false, vec4f(1, 2, 0)));
		check(f, run("textureOffset", "textureOffset", List.of("sampler2D", "vec2", "ivec2"), "vec4",
			List.of("float2(0.375, 0.625)", "int2(1, -1)"), t, false, vec4f(2, 1, 0)));
		check(f, run("textureGrad", "textureGrad", List.of("sampler2D", "vec2", "vec2", "vec2"), "vec4",
			List.of("float2(0.375, 0.625)", "float2(0.0)", "float2(0.0)"), t, false, vec4f(1, 2, 0)));
		check(f, run("texelFetch", "texelFetch", List.of("sampler2D", "ivec2", "int"), "vec4",
			List.of("int2(3, 1)", "0"), t, false, vec4f(3, 1, 0)));
		check(f, run("textureSize", "textureSize", List.of("sampler2D", "int"), "ivec2", List.of("0"), t, false,
			new int[]{4, 4}));
		check(f, run("textureProj", "textureProj", List.of("sampler2D", "vec3"), "vec4",
			List.of("float3(0.375, 0.625, 1.0)"), t, false, vec4f(1, 2, 0)));
		check(f, run("textureProj(vec4)", "textureProj", List.of("sampler2D", "vec4"), "vec4",
			List.of("float4(0.1875, 0.3125, 0.0, 0.5)"), t, false, vec4f(1, 2, 0)));
		assertTrue(f.isEmpty(), () -> String.join("\n", f));
	}

	@Test
	void gatherReturnsTheFootprintInGlslOrder() {
		// GLSL 4.60 8.9.4: (i0 j1, i1 j1, i1 j0, i0 j0) of the bilinear footprint at P; P = (0.375, 0.375) has i0 = j0 = 1.
		// The x component of texel (x, y) is x + 10y + 0.5.
		Tex t = rgba2d(4, 4);
		int[] expected = {fbits(21.5f), fbits(22.5f), fbits(12.5f), fbits(11.5f)};
		String failure = run("textureGather", "textureGather", List.of("sampler2D", "vec2", "int"), "vec4",
			List.of("float2(0.375, 0.375)", "0"), t, false, expected);
		assertTrue(failure == null, failure);
	}

	/**
	 * textureGatherOffsets: component k is the i0j0 texel of the footprint at P + offsets[k] (GLSL 4.60 8.9.4). P = (0.375,
	 * 0.375) has i0 = j0 = 1 on this 4x4 texture, so offset (dx, dy) reads texel (1 + dx, 1 + dy). Component 3 (w) of texel
	 * (x, y) is x + 10y + 0.75 + 0.5.
	 */
	@Test
	void gatherOffsetsReturnsEachOffsetsI0J0Texel() {
		String offs = "\tint2 offs[4] = {int2(1, 0), int2(0, 1), int2(-1, 0), int2(0, -1)};\n";
		int[] expected = {fbits(13.25f), fbits(22.25f), fbits(11.25f), fbits(2.25f)};
		String failure = runWith(offs, "textureGatherOffsets", "textureGatherOffsets",
			List.of("sampler2D", "vec2", "ivec2[4]", "int"), "vec4", List.of("float2(0.375, 0.375)", "offs", "3"),
			rgba2d(4, 4), false, expected);
		assertTrue(failure == null, failure);
	}

	/** Shadow form: component k is the comparison of the reference with the i0j0 texel at P + offsets[k]. */
	@Test
	void shadowGatherOffsetsComparesEachOffsetsI0J0Texel() {
		String offs = "\tint2 offs[4] = {int2(1, 0), int2(0, 1), int2(-1, 0), int2(0, -1)};\n";
		// Depth texel (x, y) = (x + 4y + 0.5) / 16 gives 0.406 at (2, 1), 0.594 at (1, 2), 0.281 at (0, 1), 0.094 at (1, 0).
		// Reference 0.45 passes (0.45 <= texel) only at (1, 2).
		int[] expected = {fbits(0f), fbits(1f), fbits(0f), fbits(0f)};
		String failure = runWith(offs, "textureGatherOffsets", "textureGatherOffsets",
			List.of("sampler2DShadow", "vec2", "float", "ivec2[4]"), "vec4",
			List.of("float2(0.375, 0.375)", "0.45", "offs"), depthRamp4x4(), true, expected);
		assertTrue(failure == null, failure);
	}

	/** A 4x4 depth texture with texel (x, y) = (x + 4y + 0.5) / 16: distinct, increasing texels. */
	static Tex depthRamp4x4() {
		ByteBuffer b = ByteBuffer.allocate(16 * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) b.putFloat((x + 4 * y + 0.5f) / 16f);
		return new Tex(InputTexture.tex2d(DEPTH32, 4, 4, b.array()), "depth2d<float>");
	}

	/** A 1D depth texture is a depth2d of height 1 (the engine's contract): one row of the given depth values. */
	static Tex depthRow(float... values) {
		ByteBuffer b = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (float v : values) b.putFloat(v);
		return new Tex(InputTexture.tex2d(DEPTH32, values.length, 1, b.array()), "depth2d<float>");
	}

	/** The 1D forms run on height-1 2D textures: texel (x, 0) is at normalized (x + 0.5) / n, and y = 0.5 selects it. */
	@Test
	void oneDimensionalTexturesAreHeightOneTwoDimensionalTextures() {
		Tex row = rgba2d(4, 1);
		List<String> f = new ArrayList<>();
		check(f, run("1D texture", "texture", List.of("sampler1D", "float"), "vec4", List.of("0.625"), row, false,
			vec4f(2, 0, 0)));
		check(f, run("1D textureLod", "textureLod", List.of("sampler1D", "float", "float"), "vec4",
			List.of("0.625", "0.0"), row, false, vec4f(2, 0, 0)));
		check(f, run("1D textureGrad", "textureGrad", List.of("sampler1D", "float", "float", "float"), "vec4",
			List.of("0.625", "0.0", "0.0"), row, false, vec4f(2, 0, 0)));
		check(f, run("1D textureOffset", "textureOffset", List.of("sampler1D", "float", "int"), "vec4",
			List.of("0.375", "1"), row, false, vec4f(2, 0, 0)));
		check(f, run("1D texelFetch", "texelFetch", List.of("sampler1D", "int", "int"), "vec4", List.of("2", "0"),
			row, false, vec4f(2, 0, 0)));
		check(f, run("1D texelFetchOffset", "texelFetchOffset", List.of("sampler1D", "int", "int", "int"), "vec4",
			List.of("1", "0", "2"), row, false, vec4f(3, 0, 0)));
		check(f, run("1D textureSize", "textureSize", List.of("sampler1D", "int"), "int", List.of("0"), row, false,
			new int[]{4}));
		Tex arr = rgba2dArray(4, 1, 3);
		check(f, run("1DArray texture", "texture", List.of("sampler1DArray", "vec2"), "vec4",
			List.of("float2(0.625, 1.0)"), arr, false, vec4f(2, 0, 1)));
		check(f, run("1DArray texelFetch", "texelFetch", List.of("sampler1DArray", "ivec2", "int"), "vec4",
			List.of("int2(2, 2)", "0"), arr, false, vec4f(2, 0, 2)));
		Tex depth = depthRow(0.2f, 0.6f, 0.9f, 0.4f);
		check(f, run("1D shadow", "texture", List.of("sampler1DShadow", "vec3"), "float",
			List.of("float3(0.625, 0.0, 0.5)"), depth, true, new int[]{fbits(1f)}));
		check(f, run("1D shadow fails", "texture", List.of("sampler1DShadow", "vec3"), "float",
			List.of("float3(0.625, 0.0, 0.95)"), depth, true, new int[]{fbits(0f)}));
		check(f, run("1D shadow textureLod", "textureLod", List.of("sampler1DShadow", "vec3", "float"), "float",
			List.of("float3(0.375, 0.0, 0.5)", "0.0"), depth, true, new int[]{fbits(1f)}));
		assertTrue(f.isEmpty(), () -> String.join("\n", f));
	}

	/** A texel buffer of count RGBA32F texels: texel i holds texelF(i, 0, 0, c), the row of rgba2d(count, 1). */
	static Tex rgbaBuffer(int count) {
		ByteBuffer b = ByteBuffer.allocate(count * 16).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < count; i++) for (int c = 0; c < 4; c++) b.putFloat(texelF(i, 0, 0, c));
		return new Tex(InputTexture.texelBuffer(RGBA32_FLOAT, count, b.array()), "texture_buffer<float>");
	}

	/** samplerBuffer and imageBuffer (GLSL 4.60 8.9.1, 8.12): a texel buffer is read by index and sized by its texels. */
	@Test
	void texelBuffersAreReadByIndexAndSizedByTheirTexelCount() {
		Tex buf = rgbaBuffer(4);
		List<String> f = new ArrayList<>();
		check(f, run("texelFetch(samplerBuffer)", "texelFetch", List.of("samplerBuffer", "int"), "vec4", List.of("2"),
			buf, false, vec4f(2, 0, 0)));
		check(f, run("textureSize(samplerBuffer)", "textureSize", List.of("samplerBuffer"), "int", List.of(), buf, false,
			new int[]{4}));
		check(f, run("imageLoad(imageBuffer)", "imageLoad", List.of("imageBuffer(rgba32f)", "int"), "vec4",
			List.of("3"), buf, false, vec4f(3, 0, 0)));
		check(f, run("imageSize(imageBuffer)", "imageSize", List.of("imageBuffer(rgba32f)"), "int", List.of(), buf,
			false, new int[]{4}));
		assertTrue(f.isEmpty(), () -> String.join("\n", f));
	}

	/** imageStore into a texel buffer, then a load of the same texel after the texture barrier (the W1-C rule). */
	@Test
	void imageBufferStoreIsReadBackAfterATextureBarrier() {
		String store = ((Mapping.Mapped) BuiltinKernel.TABLE.map(new BuiltinCall("imageStore",
			List.of("imageBuffer(rgba32f)", "int", "vec4"), "void", List.of("tex", "1", "float4(5.0, 6.0, 7.0, 8.0)"),
			new BuiltinCall.Resource("tex", null)))).msl();
		String image = GlslTypes.mslImage(GlslTypes.parse("imageBuffer(rgba32f)").image());
		String msl = "#include <metal_stdlib>\nusing namespace metal;\n"
			+ "kernel void k(" + image + " tex [[texture(0)]], device float4 *out [[buffer(0)]]) {\n"
			+ "\t" + store + ";\n"
			+ "\tthreadgroup_barrier(mem_flags::mem_texture);\n"
			+ "\tout[0] = tex.read(uint(1));\n}\n";
		try (HeadlessGpu gpu = new HeadlessGpu().with(ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true)) {
			byte[][] out = gpu.runKernel(msl, "k", new InputTexture[]{rgbaBuffer(4).input()}, new byte[0][],
				new int[]{16}, 1, 1, 1, 1, 1, 1);
			int[] got = new int[4];
			ByteBuffer.wrap(out[0]).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(got);
			assertArrayEquals(new int[]{fbits(5f), fbits(6f), fbits(7f), fbits(8f)}, got);
		}
	}

	/**
	 * imageAtomicAdd and imageAtomicCompSwap on an r32ui texel buffer (texel i = i): the old values come back, the failing
	 * compare leaves texel 2 alone, the succeeding one stores 9, and the texture barrier orders the reads after them.
	 */
	@Test
	void imageBufferAtomicsReturnTheOldTexelAndStoreTheNewOne() {
		ByteBuffer b = ByteBuffer.allocate(4 * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < 4; i++) b.putInt(i);
		InputTexture texels = InputTexture.texelBuffer(R32_UINT, 4, b.array());
		BuiltinCall.Resource res = new BuiltinCall.Resource("tex", null);
		Mapping.Mapped add = (Mapping.Mapped) BuiltinKernel.TABLE.map(new BuiltinCall("imageAtomicAdd",
			List.of("uimageBuffer(r32ui)", "int", "uint"), "uint", List.of("tex", "1", "3u"), res));
		Mapping.Mapped failing = (Mapping.Mapped) BuiltinKernel.TABLE.map(new BuiltinCall("imageAtomicCompSwap",
			List.of("uimageBuffer(r32ui)", "int", "uint", "uint"), "uint", List.of("tex", "2", "7u", "9u"), res));
		Mapping.Mapped succeeding = (Mapping.Mapped) BuiltinKernel.TABLE.map(new BuiltinCall("imageAtomicCompSwap",
			List.of("uimageBuffer(r32ui)", "int", "uint", "uint"), "uint", List.of("tex", "2", "2u", "9u"), res));
		Set<String> helpers = new LinkedHashSet<>(failing.helpers());
		helpers.addAll(succeeding.helpers());
		String msl = "#include <metal_stdlib>\nusing namespace metal;\n" + Prelude.render(helpers)
			+ "kernel void k(texture_buffer<uint, access::read_write> tex [[texture(0)]], device uint *out [[buffer(0)]]) {\n"
			+ "\tauto r0 = " + add.msl() + ";\n"
			+ "\tauto r1 = " + failing.msl() + ";\n"
			+ "\tauto r2 = " + succeeding.msl() + ";\n"
			+ "\tthreadgroup_barrier(mem_flags::mem_texture);\n"
			+ "\tout[0] = r0;\n\tout[1] = r1;\n\tout[2] = r2;\n"
			+ "\tout[3] = tex.read(uint(1)).x;\n\tout[4] = tex.read(uint(2)).x;\n}\n";
		try (HeadlessGpu gpu = new HeadlessGpu().with(ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true)) {
			byte[][] out = gpu.runKernel(msl, "k", new InputTexture[]{texels}, new byte[0][], new int[]{20}, 1, 1, 1, 1, 1, 1);
			int[] got = new int[5];
			ByteBuffer.wrap(out[0]).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(got);
			assertArrayEquals(new int[]{1, 2, 2, 4, 9}, got);
		}
	}

	/**
	 * The texture buffer keeps its MTLBuffer (mcshaders.m refuses a texture whose MTLTexture.buffer is nil): the test has
	 * no Java reference to the buffer, churns other buffers and textures, releases them, then reads the texels in a kernel.
	 */
	@Test
	void textureBufferKeepsItsStorageAfterEveryOtherReferenceIsDropped() {
		ByteBuffer b = ByteBuffer.allocate(4 * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < 4; i++) b.putInt(10 + i);
		byte[] filler = new byte[16];
		java.util.Arrays.fill(filler, (byte) 0x55);
		int usage = ShaderNative.USAGE_SHADER_READ | ShaderNative.USAGE_SHADER_WRITE;
		String msl = "#include <metal_stdlib>\nusing namespace metal;\n"
			+ "kernel void k(texture_buffer<uint, access::read_write> tex [[texture(0)]], device uint *out [[buffer(0)]]) {\n"
			+ "\tfor (uint i = 0; i < 4; i++) out[i] = tex.read(i).x;\n}\n";
		try (HeadlessGpu gpu = new HeadlessGpu().with(ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true)) {
			long texture = ShaderNative.textureBufferNew(gpu.context(), R32_UINT, 4, b.array(), usage);
			assertTrue(texture != 0, "the device refused the texture buffer");
			for (int i = 0; i < 64; i++) {
				long other = ShaderNative.textureBufferNew(gpu.context(), R32_UINT, 4, filler, usage);
				if (other != 0) dev.mcms.metal.MetalBridge.release(other);
			}
			byte[][] out = gpu.runKernelOn(msl, "k", new long[]{texture}, new byte[0][], new int[]{16}, 1, 1, 1, 1, 1, 1);
			dev.mcms.metal.MetalBridge.release(texture);
			int[] got = new int[4];
			ByteBuffer.wrap(out[0]).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(got);
			assertArrayEquals(new int[]{10, 11, 12, 13}, got);
		}
	}

	/**
	 * imageCube and imageCubeArray (GLSL 4.60 8.12): the third coordinate is the face (cube), or face + 6 * layer (cube
	 * array). Each texel is stored, then loaded after the texture barrier. The cube array also reads the texel with Metal's
	 * (face, layer) arguments, which checks the split of z into z % 6 and z / 6.
	 */
	@Test
	void cubeImagesStoreAndLoadByFaceAndLayer() {
		BuiltinCall.Resource res = new BuiltinCall.Resource("tex", null);
		String value = "float4(5.0, 6.0, 7.0, 8.0)";
		Mapping.Mapped store = (Mapping.Mapped) BuiltinKernel.TABLE.map(new BuiltinCall("imageStore",
			List.of("imageCube(rgba32f)", "ivec3", "vec4"), "void", List.of("tex", "int3(1, 2, 3)", value), res));
		Mapping.Mapped load = (Mapping.Mapped) BuiltinKernel.TABLE.map(new BuiltinCall("imageLoad",
			List.of("imageCube(rgba32f)", "ivec3"), "vec4", List.of("tex", "int3(1, 2, 3)"), res));
		String cube = GlslTypes.mslImage(GlslTypes.parse("imageCube(rgba32f)").image());
		String msl = "#include <metal_stdlib>\nusing namespace metal;\n"
			+ "kernel void k(" + cube + " tex [[texture(0)]], device float4 *out [[buffer(0)]]) {\n"
			+ "\t" + store.msl() + ";\n\tthreadgroup_barrier(mem_flags::mem_texture);\n"
			+ "\tout[0] = " + load.msl() + ";\n}\n";
		try (HeadlessGpu gpu = new HeadlessGpu().with(ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true)) {
			byte[][] out = gpu.runKernel(msl, "k", new InputTexture[]{new InputTexture(RGBA32_FLOAT, 5, 4, 4, 1, null)},
				new byte[0][], new int[]{16}, 1, 1, 1, 1, 1, 1);
			int[] got = new int[4];
			ByteBuffer.wrap(out[0]).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(got);
			assertArrayEquals(new int[]{fbits(5f), fbits(6f), fbits(7f), fbits(8f)}, got);
		}

		Mapping.Mapped arrayStore = (Mapping.Mapped) BuiltinKernel.TABLE.map(new BuiltinCall("imageStore",
			List.of("imageCubeArray(rgba32f)", "ivec3", "vec4"), "void", List.of("tex", "int3(0, 1, 7)", value), res));
		Mapping.Mapped arrayLoad = (Mapping.Mapped) BuiltinKernel.TABLE.map(new BuiltinCall("imageLoad",
			List.of("imageCubeArray(rgba32f)", "ivec3"), "vec4", List.of("tex", "int3(0, 1, 7)"), res));
		String cubeArray = GlslTypes.mslImage(GlslTypes.parse("imageCubeArray(rgba32f)").image());
		String arrayMsl = "#include <metal_stdlib>\nusing namespace metal;\n"
			+ "kernel void k(" + cubeArray + " tex [[texture(0)]], device float4 *out [[buffer(0)]]) {\n"
			+ "\t" + arrayStore.msl() + ";\n\tthreadgroup_barrier(mem_flags::mem_texture);\n"
			+ "\tout[0] = " + arrayLoad.msl() + ";\n\tout[1] = tex.read(uint2(0, 1), uint(1), uint(1));\n}\n";
		try (HeadlessGpu gpu = new HeadlessGpu().with(ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true)) {
			// Two cubes: z = 7 is face 1 of layer 1.
			byte[][] out = gpu.runKernel(arrayMsl, "k", new InputTexture[]{new InputTexture(RGBA32_FLOAT, 6, 4, 4, 2, null)},
				new byte[0][], new int[]{32}, 1, 1, 1, 1, 1, 1);
			int[] got = new int[8];
			ByteBuffer.wrap(out[0]).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(got);
			int[] expected = {fbits(5f), fbits(6f), fbits(7f), fbits(8f), fbits(5f), fbits(6f), fbits(7f), fbits(8f)};
			assertArrayEquals(expected, got);
		}
	}

	@Test
	void arraysThreeDAndIntegerTextures() {
		List<String> f = new ArrayList<>();
		check(f, run("texture(sampler2DArray)", "texture", List.of("sampler2DArray", "vec3"), "vec4",
			List.of("float3(0.375, 0.625, 1.0)"), rgba2dArray(4, 4, 2), false, vec4f(1, 2, 1)));
		check(f, run("texture(sampler3D)", "texture", List.of("sampler3D", "vec3"), "vec4",
			List.of("float3(0.25, 0.75, 0.25)"), rgba3d(2, 2, 2), false, vec4f(0, 1, 0)));
		check(f, run("texelFetch(sampler3D)", "texelFetch", List.of("sampler3D", "ivec3", "int"), "vec4",
			List.of("int3(1, 0, 1)", "0"), rgba3d(2, 2, 2), false, vec4f(1, 0, 1)));
		check(f, run("texelFetch(usampler2D)", "texelFetch", List.of("usampler2D", "ivec2", "int"), "uvec4",
			List.of("int2(2, 1)", "0"), rgbaUint2d(4, 4), false, new int[]{12, 13, 14, 15}));
		assertTrue(f.isEmpty(), () -> String.join("\n", f));
	}

	@Test
	void shadowComparisonIsLessEqualAgainstTheStoredDepth() {
		Tex d = depth2d(4, 4, 0.25f);
		int one = fbits(1f), zero = fbits(0f);
		List<String> f = new ArrayList<>();
		// GLSL: the result is 1 when Dref <= the stored depth (LEQUAL), else 0.
		check(f, run("shadow texture (ref 0.2)", "texture", List.of("sampler2DShadow", "vec3"), "float",
			List.of("float3(0.375, 0.625, 0.2)"), d, true, new int[]{one}));
		check(f, run("shadow texture (ref 0.5)", "texture", List.of("sampler2DShadow", "vec3"), "float",
			List.of("float3(0.375, 0.625, 0.5)"), d, true, new int[]{zero}));
		assertTrue(f.isEmpty(), () -> String.join("\n", f));
	}

	@Test
	void imageLoadAndAtomicAddOnR32Uint() {
		Tex img = r32uImage(4, 4);
		List<String> f = new ArrayList<>();
		check(f, run("imageLoad", "imageLoad", List.of("uimage2D(r32ui)", "ivec2"), "uvec4",
			List.of("int2(2, 1)"), img, false, new int[]{12, 0, 0, 1}));
		check(f, run("imageSize", "imageSize", List.of("uimage2D(r32ui)"), "ivec2", List.of(), img, false,
			new int[]{4, 4}));
		// the old value of texel (1, 1) (= 11) is returned by imageAtomicAdd
		check(f, run("imageAtomicAdd", "imageAtomicAdd", List.of("uimage2D(r32ui)", "ivec2", "uint"), "uint",
			List.of("int2(1, 1)", "3u"), img, false, new int[]{11}));
		assertTrue(f.isEmpty(), () -> String.join("\n", f));
	}

	/** Device atomics: atomicAdd on a device atomic_uint, the old value is returned and the memory updated. */
	@Test
	void deviceAtomicAddReturnsTheOldValue() {
		String expr = BuiltinKernel.TABLE.map(new BuiltinCall("atomicAdd", List.of("uint", "uint"), "uint",
			List.of("a0", "a1"), null)) instanceof Mapping.Mapped m ? m.msl() : "";
		String msl = "#include <metal_stdlib>\nusing namespace metal;\n"
			+ "kernel void k(device atomic_uint *buf [[buffer(0)]], device uint *out [[buffer(1)]]) {\n"
			+ "\tdevice atomic_uint &a0 = buf[0];\n\tuint a1 = 3u;\n"
			+ "\tauto r = " + expr + ";\n\tout[0] = r;\n\tout[1] = atomic_load_explicit(&a0, memory_order_relaxed);\n}\n";
		try (HeadlessGpu gpu = new HeadlessGpu().with(ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true)) {
			byte[] initial = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(10).array();
			byte[][] out = gpu.runKernel(msl, "k", null, new byte[][]{initial}, new int[]{16}, 1, 1, 1, 1, 1, 1);
			int[] got = new int[2];
			ByteBuffer.wrap(out[0]).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(got);
			assertEquals(10, got[0], "old value");
			assertEquals(13, got[1], "new value");
		}
	}

	static void assertNoStaleFailure(String failure) {
		assertEquals(null, failure);
	}
}
