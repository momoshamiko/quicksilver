// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W1-C: the context-struct trial of ADR 0006 §2, the translator's plan for GLSL globals. One per-invocation struct holds a
 * constant struct reference (a uniform block), a device buffer pointer, texture2d, depth2d and read-write texture3d
 * members, a threadgroup pointer, an array and mutable fields; its member functions use constexpr samplers (including a
 * compare sampler on the depth texture) and take a thread float& parameter (GLSL inout). A kernel and a fragment function
 * both build the struct and call its methods; every value they produce is exact in float, so the checks are bit-exact.
 */
@Tag("gpu")
final class ContextStructGpuTest {
	private static final int TEX = 4, VOL = 4, THREADS = 64, GROUP = 16;
	/** Exact channel values {0, 0.25, 0.75, 1.0} as floats, for the noise texture and expected colours. */
	private static final float[] CH = {0.0f, 0.25f, 0.75f, 1.0f};
	private static final int[] CH_BYTES = {0, 64, 191, 255};

	private static final String MSL = """
		#include <metal_stdlib>
		using namespace metal;

		// The per-frame uniform block (a constant struct reference member).
		struct Frame { float time; float4 tint; };

		// GLSL globals become members, program functions become methods (ADR 0006 §2).
		struct Prog {
			constant Frame &frame;                       // uniform block
			device float *cells;                        // storage buffer
			texture2d<float> noise;                     // sampler2D
			depth2d<float> shadow;                      // sampler2DShadow
			texture3d<float, access::read_write> volume;  // image3D
			threadgroup float *scratch;                 // shared memory (kernels; null elsewhere)
			float acc;                                   // mutable global variable
			array<float, 3> weights;                    // global array

			Prog(constant Frame &f, device float *c, texture2d<float> n, depth2d<float> s,
					texture3d<float, access::read_write> v, threadgroup float *t)
				: frame(f), cells(c), noise(n), shadow(s), volume(v), scratch(t), acc(0.0f), weights{0.5f, 0.25f, 0.125f} {}

			float noiseAt(float2 uv) const {
				constexpr sampler s(filter::nearest, address::clamp_to_edge);
				return noise.sample(s, uv).r;
			}
			float lit(float2 uv, float refDepth) const {
				constexpr sampler cs(filter::nearest, compare_func::less_equal);
				return shadow.sample_compare(cs, uv, refDepth);
			}
			void bump(thread float &v) { acc += v; v = acc; }   // GLSL inout parameter
			float volumeAt(uint3 at) const { return volume.read(at).r; }
			void storeVolume(uint3 at, float v) { volume.write(float4(v), at); }
		};

		// The test device's full-screen triangle (one vertex entry both tests share).
		struct VOut { float4 pos [[position]]; };
		vertex VOut vs_full(uint vid [[vertex_id]]) {
			float2 p = float2((vid << 1) & 2, vid & 2);
			VOut o;
			o.pos = float4(p * 2.0f - 1.0f, 0.0f, 1.0f);
			return o;
		}

		kernel void cs_kernel(constant Frame &frame [[buffer(0)]], device float *cells [[buffer(1)]],
				texture2d<float> noise [[texture(0)]], depth2d<float> shadow [[texture(1)]],
				texture3d<float, access::read_write> volume [[texture(2)]], device float4 *out [[buffer(2)]],
				uint gid [[thread_position_in_grid]], uint lid [[thread_position_in_threadgroup]],
				uint lsize [[threads_per_threadgroup]]) {
			threadgroup float tg[16];
			Prog p(frame, cells, noise, shadow, volume, tg);
			uint3 vox = uint3(gid & 3u, (gid >> 2) & 3u, gid >> 4u);
			p.scratch[lid] = (float) gid + frame.time;
			threadgroup_barrier(mem_flags::mem_threadgroup);
			float shared = 0.0f;
			for (uint i = 0; i < lsize; i++) shared += p.scratch[i];   // integers: any summation order is exact
			float v = p.cells[gid] * p.weights[gid % 3u];  // device read through the struct, array member index
			p.bump(v);                                     // inout through a method
			p.storeVolume(vox, v);                         // read-write 3D image write
			// Measured on this GPU (W1-C): an image write is not visible to later reads in the same dispatch until a
			// mem_texture barrier; GLSL imageStore/imageLoad coherence needs it, so the emitter must insert one.
			threadgroup_barrier(mem_flags::mem_texture);
			float back = p.volumeAt(vox);                  // and read back
			float n = p.noiseAt((float2(vox.xy) + 0.5f) / 4.0f);
			out[gid] = float4(v, back, shared, p.frame.tint.r * n * 4.0f);
		}

		fragment float4 cs_fragment(VOut v [[stage_in]], constant Frame &frame [[buffer(0)]],
				device float *cells [[buffer(1)]], texture2d<float> noise [[texture(0)]],
				depth2d<float> shadow [[texture(1)]], texture3d<float, access::read_write> volume [[texture(2)]]) {
			Prog p(frame, cells, noise, shadow, volume, (threadgroup float *) nullptr);   // no threadgroup memory here
			uint2 pix = uint2(v.pos.xy);
			float n = p.noiseAt((float2(pix) + 0.5f) / 4.0f);
			float lit = p.lit(float2(pix) / 4.0f, 0.5f);
			float cell = p.cells[0];
			p.bump(cell);
			return float4(n, lit, cell, p.volumeAt(uint3(pix, 0)) * 4.0f);   // the 3D image read in a fragment too
		}
		""";

	@Test
	void kernelBuildsTheStructAndProducesKnownValues() {
		// Noise: r = CH[x] per column; shadow: 0.25 where (x+y) is even, else 0.75; volume starts zeroed.
		byte[] noise = new byte[TEX * TEX * 4];
		byte[] shadow = new byte[TEX * TEX * 4];
		byte[] volume = new byte[VOL * VOL * VOL * 4];
		for (int y = 0; y < TEX; y++) {
			for (int x = 0; x < TEX; x++) {
				ByteBuffer.wrap(noise).order(ByteOrder.LITTLE_ENDIAN).putFloat((y * TEX + x) * 4, CH[x]);
				ByteBuffer.wrap(shadow).order(ByteOrder.LITTLE_ENDIAN)
					.putFloat((y * TEX + x) * 4, (x + y) % 2 == 0 ? 0.25f : 0.75f);
			}
		}
		byte[] frame = new byte[32];
		ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).putFloat(2.0f).putFloat(16, 0.5f);  // time, tint.r
		float[] cells = new float[THREADS];
		byte[] cellBytes = new byte[THREADS * 4];
		for (int gid = 0; gid < THREADS; gid++) {
			cells[gid] = gid / 64.0f;
			ByteBuffer.wrap(cellBytes).order(ByteOrder.LITTLE_ENDIAN).putFloat(gid * 4, cells[gid]);
		}

		try (HeadlessGpu gpu = new HeadlessGpu()) {
			byte[][] out = gpu.runKernel(MSL, "cs_kernel",
				new HeadlessGpu.InputTexture[]{
					HeadlessGpu.InputTexture.tex2d(HeadlessGpu.R32_FLOAT, TEX, TEX, noise),
					HeadlessGpu.InputTexture.tex2d(HeadlessGpu.DEPTH32_FLOAT, TEX, TEX, shadow),
					HeadlessGpu.InputTexture.tex3d(HeadlessGpu.R32_FLOAT, VOL, VOL, VOL, volume)},
				new byte[][]{frame, cellBytes}, new int[]{THREADS * 16}, THREADS / GROUP, 1, 1, GROUP, 1, 1);
			float[] result = new float[THREADS * 4];
			ByteBuffer.wrap(out[0]).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(result);

			for (int gid = 0; gid < THREADS; gid++) {
				float v = cells[gid] * (gid % 3 == 0 ? 0.5f : gid % 3 == 1 ? 0.25f : 0.125f);
				int group = gid / GROUP, lid = gid % GROUP;
				float first = 2.0f + group * GROUP, last = 2.0f + group * GROUP + (GROUP - 1);
				float shared = (first + last) * GROUP / 2;   // exact: 16 consecutive integers
				float n = CH[gid & 3];
				float[] expected = {v, v, shared, 0.5f * n * 4.0f};
				for (int c = 0; c < 4; c++) {
					int at = gid * 4 + c;
					assertTrue(result[at] == expected[c], "thread " + gid + " channel " + c + ": " + result[at]
						+ ", expected " + expected[c]);
				}
			}
		}
	}

	@Test
	void fragmentBuildsTheStructAndProducesKnownValues() {
		byte[] noise = new byte[TEX * TEX * 4];
		byte[] shadow = new byte[TEX * TEX * 4];
		byte[] volume = new byte[VOL * VOL * VOL * 4];  // stays zero: the fragment reads 0 back
		for (int y = 0; y < TEX; y++) {
			for (int x = 0; x < TEX; x++) {
				ByteBuffer.wrap(noise).order(ByteOrder.LITTLE_ENDIAN).putFloat((y * TEX + x) * 4, CH[(x * TEX + y) % 4]);
				ByteBuffer.wrap(shadow).order(ByteOrder.LITTLE_ENDIAN)
					.putFloat((y * TEX + x) * 4, (x + y) % 2 == 0 ? 0.25f : 0.75f);
			}
		}
		byte[] frame = new byte[32];
		ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).putFloat(2.0f).putFloat(16, 0.5f);
		byte[] cells = new byte[16];
		ByteBuffer.wrap(cells).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.25f);

		try (HeadlessGpu gpu = new HeadlessGpu()) {
			byte[] pixels = gpu.renderFullscreen(MSL, "vs_full", "cs_fragment", new int[]{HeadlessGpu.RGBA8_UNORM},
				TEX, TEX,
				new HeadlessGpu.InputTexture[]{
					HeadlessGpu.InputTexture.tex2d(HeadlessGpu.R32_FLOAT, TEX, TEX, noise),
					HeadlessGpu.InputTexture.tex2d(HeadlessGpu.DEPTH32_FLOAT, TEX, TEX, shadow),
					HeadlessGpu.InputTexture.tex3d(HeadlessGpu.R32_FLOAT, VOL, VOL, VOL, volume)},
				frame, new byte[][]{cells})[0];
			for (int y = 0; y < TEX; y++) {
				for (int x = 0; x < TEX; x++) {
					int at = (y * TEX + x) * 4;
					int n = CH_BYTES[(x * TEX + y) % 4];
					int lit = (x + y) % 2 == 0 ? 0 : 255;   // sample_compare passes when ref <= texel: 0.5 <= 0.75, not 0.25
					byte[] expected = {(byte) n, (byte) lit, 64, 0};   // cells[0] = 0.25, volume 0 * 4 = 0
					for (int c = 0; c < 4; c++) {
						int actual = pixels[at + c] & 0xff, exp = expected[c] & 0xff;
						assertTrue(actual == exp, "pixel (" + x + "," + y + ") channel " + c + ": " + actual
							+ ", expected " + exp);
					}
				}
			}
		}
	}
}
