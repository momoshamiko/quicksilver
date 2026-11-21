// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl.builtins;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The helper prelude of the built-in table (W2-C, ADR 0006): the MSL functions a mapping may need, in namespace
 * {@code qs} so pack code cannot collide with them. The emitter renders exactly the helpers the mapped expressions
 * name; every function here is written from the GLSL specification's definition of the function it replaces (the
 * GLSL clause is cited per helper) or from the MSL member functions measured on this device.
 */
public final class Prelude {
	private static final String COMMON = """
		// Generated prelude: GLSL built-in helpers for the Quicksilver translator (namespace qs).
		""";

	private static final Map<String, String> HELPERS = buildHelpers();

	private static Map<String, String> buildHelpers() {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("normalize", """
			// GLSL 4.60 8.5 normalize: x / length(x). The zero vector gives NaN, as OpenGL gives it on this device (Iris
			// 1.11.7, measured with an instrumented Bliss 2.1.2, E7); MSL's normalize gives 0 there under fast math
			// (MathModeGpuTest), and Bliss's sky average reads the lookup of a zero vector (OpenGL's NaN coordinate reads 0).
			// The vector forms multiply by rsqrt(dot(x, x)) (E9: three IEEE divides per call cost the heavy packs' fragment
			// stages): rsqrt(0) is +inf and 0 * inf is NaN under the packs' relaxed math, which honours infinities and NaNs
			// (ShaderNative.MATH_PACKS). The scalar keeps the IEEE 0 / 0.
			static inline float normalize(float x) { return precise::divide(x, fabs(x)); }
			static inline float2 normalize(float2 x) { return x * rsqrt(dot(x, x)); }
			static inline float3 normalize(float3 x) { return x * rsqrt(dot(x, x)); }
			static inline float4 normalize(float4 x) { return x * rsqrt(dot(x, x)); }""");
		m.put("mod", """
			// GLSL 4.60 8.3 mod: x - y * floor(x / y) (floored, unlike C fmod). Vector/scalar broadcast included.
			// The quotient is an IEEE division even under fast math: a reciprocal multiply can move floor() across an
			// integer (GPU test: mod(-1e-3, 1e-4) under MTLMathModeFast gave ~0, not 1e-4).
			static inline float mod(float x, float y) { return x - y * floor(precise::divide(x, y)); }
			static inline float2 mod(float2 x, float2 y) { return x - y * floor(precise::divide(x, y)); }
			static inline float3 mod(float3 x, float3 y) { return x - y * floor(precise::divide(x, y)); }
			static inline float4 mod(float4 x, float4 y) { return x - y * floor(precise::divide(x, y)); }
			static inline float2 mod(float2 x, float y) { return x - y * floor(precise::divide(x, y)); }
			static inline float3 mod(float3 x, float y) { return x - y * floor(precise::divide(x, y)); }
			static inline float4 mod(float4 x, float y) { return x - y * floor(precise::divide(x, y)); }""");
		m.put("matrixCompMult", """
			// GLSL 4.60 8.6 matrixCompMult: component-wise product, column by column.
			template <typename T, int C, int R>
			static inline matrix<T, C, R> matrixCompMult(matrix<T, C, R> x, matrix<T, C, R> y) {
				matrix<T, C, R> r;
				for (int i = 0; i < C; i++) r[i] = x[i] * y[i];
				return r;
			}""");
		m.put("outerProduct", """
			// GLSL 4.60 8.6 outerProduct: matCxR with C = components of r, column j = c * r[j].
			template <typename T, int C, int R>
			static inline matrix<T, C, R> outerProduct(vec<T, R> c, vec<T, C> r) {
				matrix<T, C, R> m;
				for (int i = 0; i < C; i++) m[i] = c * r[i];
				return m;
			}""");
		m.put("inverse", """
			// GLSL 4.60 8.6 inverse: adjugate over determinant (a_rc is row r, column c; MSL m[c][r]).
			static inline float2x2 inverse(float2x2 m) {
				float a = m[0][0], b = m[1][0], c = m[0][1], d = m[1][1];
				float invDet = 1.0f / (a * d - b * c);
				float2x2 r;
				r[0] = float2(d, -c) * invDet;
				r[1] = float2(-b, a) * invDet;
				return r;
			}
			static inline float3x3 inverse(float3x3 m) {
				float a00 = m[0][0], a01 = m[1][0], a02 = m[2][0];
				float a10 = m[0][1], a11 = m[1][1], a12 = m[2][1];
				float a20 = m[0][2], a21 = m[1][2], a22 = m[2][2];
				float invDet = 1.0f / (a00 * (a11 * a22 - a12 * a21) - a01 * (a10 * a22 - a12 * a20)
					+ a02 * (a10 * a21 - a11 * a20));
				float3x3 r;
				r[0] = float3(a11 * a22 - a12 * a21, a12 * a20 - a10 * a22, a10 * a21 - a11 * a20) * invDet;
				r[1] = float3(a02 * a21 - a01 * a22, a00 * a22 - a02 * a20, a01 * a20 - a00 * a21) * invDet;
				r[2] = float3(a01 * a12 - a02 * a11, a02 * a10 - a00 * a12, a00 * a11 - a01 * a10) * invDet;
				return r;
			}
			static inline float4x4 inverse(float4x4 m) {
				float a00 = m[0][0], a01 = m[1][0], a02 = m[2][0], a03 = m[3][0];
				float a10 = m[0][1], a11 = m[1][1], a12 = m[2][1], a13 = m[3][1];
				float a20 = m[0][2], a21 = m[1][2], a22 = m[2][2], a23 = m[3][2];
				float a30 = m[0][3], a31 = m[1][3], a32 = m[2][3], a33 = m[3][3];
				float s0 = a00 * a11 - a10 * a01, s1 = a00 * a12 - a10 * a02, s2 = a00 * a13 - a10 * a03;
				float s3 = a01 * a12 - a11 * a02, s4 = a01 * a13 - a11 * a03, s5 = a02 * a13 - a12 * a03;
				float c0 = a20 * a31 - a30 * a21, c1 = a20 * a32 - a30 * a22, c2 = a20 * a33 - a30 * a23;
				float c3 = a21 * a32 - a31 * a22, c4 = a21 * a33 - a31 * a23, c5 = a22 * a33 - a32 * a23;
				float invDet = 1.0f / (s0 * c5 - s1 * c4 + s2 * c3 + s3 * c2 - s4 * c1 + s5 * c0);
				float4x4 r;
				r[0] = float4(a11 * c5 - a12 * c4 + a13 * c3, -a10 * c5 + a12 * c2 - a13 * c1,
					a10 * c4 - a11 * c2 + a13 * c0, -a10 * c3 + a11 * c1 - a12 * c0) * invDet;
				r[1] = float4(-a01 * c5 + a02 * c4 - a03 * c3, a00 * c5 - a02 * c2 + a03 * c1,
					-a00 * c4 + a01 * c2 - a03 * c0, a00 * c3 - a01 * c1 + a02 * c0) * invDet;
				r[2] = float4(a31 * s5 - a32 * s4 + a33 * s3, -a30 * s5 + a32 * s2 - a33 * s1,
					a30 * s4 - a31 * s2 + a33 * s0, -a30 * s3 + a31 * s1 - a32 * s0) * invDet;
				r[3] = float4(-a21 * s5 + a22 * s4 - a23 * s3, a20 * s5 - a22 * s2 + a23 * s1,
					-a20 * s4 + a21 * s2 - a23 * s0, a20 * s3 - a21 * s1 + a22 * s0) * invDet;
				return r;
			}""");
		m.put("findLSB", """
			// GLSL 4.60 8.8 findLSB: index of the least significant one bit, -1 when the value is zero (MSL ctz is
			// undefined at zero, so the zero case is selected explicitly). Same rule signed and unsigned.
			static inline int findLSB(uint v) { return v == 0u ? -1 : (int) ctz(v); }
			static inline int findLSB(int v) { return findLSB((uint) v); }
			static inline int2 findLSB(uint2 v) { return select(int2(-1), int2(ctz(v)), v != uint2(0)); }
			static inline int2 findLSB(int2 v) { return findLSB((uint2) v); }
			static inline int3 findLSB(uint3 v) { return select(int3(-1), int3(ctz(v)), v != uint3(0)); }
			static inline int3 findLSB(int3 v) { return findLSB((uint3) v); }
			static inline int4 findLSB(uint4 v) { return select(int4(-1), int4(ctz(v)), v != uint4(0)); }
			static inline int4 findLSB(int4 v) { return findLSB((uint4) v); }""");
		m.put("findMSB", """
			// GLSL 4.60 8.8 findMSB: unsigned - index of the most significant one bit (31..0); signed - index of
			// the most significant zero bit; -1 for zero and for -1. 32-bit desktop GLSL integers.
			static inline int findMSB(uint v) { return v == 0u ? -1 : 31 - (int) clz(v); }
			static inline int findMSB(int v) { return v >= 0 ? findMSB((uint) v) : findMSB((uint) ~v); }
			static inline int2 findMSB(uint2 v) {
				return select(int2(-1), int2(31) - int2(clz(v)), v != uint2(0));
			}
			static inline int2 findMSB(int2 v) {
				uint2 u = (uint2) v, flipped = ~u;
				int2 ones = select(int2(-1), int2(31) - int2(clz(u)), u != uint2(0));
				int2 zeros = select(int2(-1), int2(31) - int2(clz(flipped)), flipped != uint2(0));
				return select(zeros, ones, v >= int2(0));
			}
			static inline int3 findMSB(uint3 v) {
				return select(int3(-1), int3(31) - int3(clz(v)), v != uint3(0));
			}
			static inline int3 findMSB(int3 v) {
				uint3 u = (uint3) v, flipped = ~u;
				int3 ones = select(int3(-1), int3(31) - int3(clz(u)), u != uint3(0));
				int3 zeros = select(int3(-1), int3(31) - int3(clz(flipped)), flipped != uint3(0));
				return select(zeros, ones, v >= int3(0));
			}
			static inline int4 findMSB(uint4 v) {
				return select(int4(-1), int4(31) - int4(clz(v)), v != uint4(0));
			}
			static inline int4 findMSB(int4 v) {
				uint4 u = (uint4) v, flipped = ~u;
				int4 ones = select(int4(-1), int4(31) - int4(clz(u)), u != uint4(0));
				int4 zeros = select(int4(-1), int4(31) - int4(clz(flipped)), flipped != uint4(0));
				return select(zeros, ones, v >= int4(0));
			}""");
		m.put("uaddCarry", """
			// GLSL 4.60 8.8 uaddCarry: sum modulo 2^32, carry 1 when the true sum reached 2^32.
			static inline uint uaddCarry(uint x, uint y, thread uint &carry) {
				uint s = x + y;
				carry = s < x ? 1u : 0u;
				return s;
			}
			template <typename T>
			static inline vec<T, 2> uaddCarry(vec<T, 2> x, vec<T, 2> y, thread vec<T, 2> &carry) {
				vec<T, 2> s = x + y;
				carry = select(vec<T, 2>(0), vec<T, 2>(1), s < x);
				return s;
			}
			template <typename T>
			static inline vec<T, 3> uaddCarry(vec<T, 3> x, vec<T, 3> y, thread vec<T, 3> &carry) {
				vec<T, 3> s = x + y;
				carry = select(vec<T, 3>(0), vec<T, 3>(1), s < x);
				return s;
			}
			template <typename T>
			static inline vec<T, 4> uaddCarry(vec<T, 4> x, vec<T, 4> y, thread vec<T, 4> &carry) {
				vec<T, 4> s = x + y;
				carry = select(vec<T, 4>(0), vec<T, 4>(1), s < x);
				return s;
			}""");
		m.put("usubBorrow", """
			// GLSL 4.60 8.8 usubBorrow: difference modulo 2^32, borrow 1 when y was greater than x.
			static inline uint usubBorrow(uint x, uint y, thread uint &borrow) {
				uint d = x - y;
				borrow = x < y ? 1u : 0u;
				return d;
			}
			template <typename T>
			static inline vec<T, 2> usubBorrow(vec<T, 2> x, vec<T, 2> y, thread vec<T, 2> &borrow) {
				vec<T, 2> d = x - y;
				borrow = select(vec<T, 2>(0), vec<T, 2>(1), x < y);
				return d;
			}
			template <typename T>
			static inline vec<T, 3> usubBorrow(vec<T, 3> x, vec<T, 3> y, thread vec<T, 3> &borrow) {
				vec<T, 3> d = x - y;
				borrow = select(vec<T, 3>(0), vec<T, 3>(1), x < y);
				return d;
			}
			template <typename T>
			static inline vec<T, 4> usubBorrow(vec<T, 4> x, vec<T, 4> y, thread vec<T, 4> &borrow) {
				vec<T, 4> d = x - y;
				borrow = select(vec<T, 4>(0), vec<T, 4>(1), x < y);
				return d;
			}""");
		m.put("umulExtended", """
			// GLSL 4.60 8.8 umulExtended: 64-bit product of 32-bit unsigned values, msb and lsb halves.
			static inline void umulExtended(uint x, uint y, thread uint &msb, thread uint &lsb) {
				ulong p = (ulong) x * (ulong) y;
				lsb = (uint) p;
				msb = (uint) (p >> 32);
			}
			static inline void umulExtended(uint2 x, uint2 y, thread uint2 &msb, thread uint2 &lsb) {
				ulong p0 = (ulong) x[0] * (ulong) y[0], p1 = (ulong) x[1] * (ulong) y[1];
				lsb = uint2((uint) p0, (uint) p1);
				msb = uint2((uint) (p0 >> 32), (uint) (p1 >> 32));
			}
			static inline void umulExtended(uint3 x, uint3 y, thread uint3 &msb, thread uint3 &lsb) {
				ulong p0 = (ulong) x[0] * (ulong) y[0], p1 = (ulong) x[1] * (ulong) y[1];
				ulong p2 = (ulong) x[2] * (ulong) y[2];
				lsb = uint3((uint) p0, (uint) p1, (uint) p2);
				msb = uint3((uint) (p0 >> 32), (uint) (p1 >> 32), (uint) (p2 >> 32));
			}
			static inline void umulExtended(uint4 x, uint4 y, thread uint4 &msb, thread uint4 &lsb) {
				ulong p0 = (ulong) x[0] * (ulong) y[0], p1 = (ulong) x[1] * (ulong) y[1];
				ulong p2 = (ulong) x[2] * (ulong) y[2], p3 = (ulong) x[3] * (ulong) y[3];
				lsb = uint4((uint) p0, (uint) p1, (uint) p2, (uint) p3);
				msb = uint4((uint) (p0 >> 32), (uint) (p1 >> 32), (uint) (p2 >> 32), (uint) (p3 >> 32));
			}""");
		m.put("imulExtended", """
			// GLSL 4.60 8.8 imulExtended: 64-bit product of 32-bit signed values, msb and lsb halves.
			static inline void imulExtended(int x, int y, thread int &msb, thread int &lsb) {
				long p = (long) x * (long) y;
				lsb = (int) p;
				msb = (int) (p >> 32);
			}
			static inline void imulExtended(int2 x, int2 y, thread int2 &msb, thread int2 &lsb) {
				long p0 = (long) x[0] * (long) y[0], p1 = (long) x[1] * (long) y[1];
				lsb = int2((int) p0, (int) p1);
				msb = int2((int) (p0 >> 32), (int) (p1 >> 32));
			}
			static inline void imulExtended(int3 x, int3 y, thread int3 &msb, thread int3 &lsb) {
				long p0 = (long) x[0] * (long) y[0], p1 = (long) x[1] * (long) y[1];
				long p2 = (long) x[2] * (long) y[2];
				lsb = int3((int) p0, (int) p1, (int) p2);
				msb = int3((int) (p0 >> 32), (int) (p1 >> 32), (int) (p2 >> 32));
			}
			static inline void imulExtended(int4 x, int4 y, thread int4 &msb, thread int4 &lsb) {
				long p0 = (long) x[0] * (long) y[0], p1 = (long) x[1] * (long) y[1];
				long p2 = (long) x[2] * (long) y[2], p3 = (long) x[3] * (long) y[3];
				lsb = int4((int) p0, (int) p1, (int) p2, (int) p3);
				msb = int4((int) (p0 >> 32), (int) (p1 >> 32), (int) (p2 >> 32), (int) (p3 >> 32));
			}""");
		m.put("atomicCompSwap", """
			// GLSL 4.60 8.11 atomicCompSwap returns the ORIGINAL value; MSL's compare_exchange writes the original
			// into expected on failure, so expected holds it in every case. GLSL is sequentially consistent; MSL has
			// no seq_cst, this is relaxed and the engine's barriers (mem_device/mem_threadgroup) order the memory.
			static inline int atomicCompSwap(device atomic_int *p, int compare, int data) {
				int expected = compare;
				atomic_compare_exchange_weak_explicit(p, &expected, data,
					memory_order_relaxed, memory_order_relaxed);
				return expected;
			}
			static inline uint atomicCompSwap(device atomic_uint *p, uint compare, uint data) {
				uint expected = compare;
				atomic_compare_exchange_weak_explicit(p, &expected, data,
					memory_order_relaxed, memory_order_relaxed);
				return expected;
			}
			static inline int atomicCompSwap(threadgroup atomic_int *p, int compare, int data) {
				int expected = compare;
				atomic_compare_exchange_weak_explicit(p, &expected, data,
					memory_order_relaxed, memory_order_relaxed);
				return expected;
			}
			static inline uint atomicCompSwap(threadgroup atomic_uint *p, uint compare, uint data) {
				uint expected = compare;
				atomic_compare_exchange_weak_explicit(p, &expected, data,
					memory_order_relaxed, memory_order_relaxed);
				return expected;
			}""");
		m.put("textureSize", """
			// GLSL 4.60 8.9.1 textureSize for every MSL texture class the engine binds (template over the element,
			// explicit overloads for the depth classes). Array forms append the layer count (get_array_size).
			template <typename T>
			static inline int2 textureSize(texture2d<T> t, int lod) {
				return int2((int) t.get_width((uint) lod), (int) t.get_height((uint) lod));
			}
			static inline int2 textureSize(depth2d<float> t, int lod) {
				return int2((int) t.get_width((uint) lod), (int) t.get_height((uint) lod));
			}
			template <typename T>
			static inline int3 textureSize(texture3d<T> t, int lod) {
				return int3((int) t.get_width((uint) lod), (int) t.get_height((uint) lod), (int) t.get_depth((uint) lod));
			}
			template <typename T>
			static inline int2 textureSize(texturecube<T> t, int lod) {
				return int2((int) t.get_width((uint) lod), (int) t.get_height((uint) lod));
			}
			static inline int2 textureSize(depthcube<float> t, int lod) {
				return int2((int) t.get_width((uint) lod), (int) t.get_height((uint) lod));
			}
			template <typename T>
			static inline int3 textureSize(texture2d_array<T> t, int lod) {
				return int3((int) t.get_width((uint) lod), (int) t.get_height((uint) lod), (int) t.get_array_size());
			}
			static inline int3 textureSize(depth2d_array<float> t, int lod) {
				return int3((int) t.get_width((uint) lod), (int) t.get_height((uint) lod), (int) t.get_array_size());
			}
			template <typename T>
			static inline int3 textureSize(texturecube_array<T> t, int lod) {
				return int3((int) t.get_width((uint) lod), (int) t.get_height((uint) lod), (int) t.get_array_size());
			}
			static inline int3 textureSize(depthcube_array<float> t, int lod) {
				return int3((int) t.get_width((uint) lod), (int) t.get_height((uint) lod), (int) t.get_array_size());
			}
			template <typename T>
			static inline int2 textureSize(texture2d<T> t) {
				return int2((int) t.get_width(), (int) t.get_height());
			}
			static inline int2 textureSize(depth2d<float> t) {
				return int2((int) t.get_width(), (int) t.get_height());
			}
			template <typename T>
			static inline int2 textureSize(texture2d_ms<T> t) {
				return int2((int) t.get_width(), (int) t.get_height());
			}
			template <typename T>
			static inline int3 textureSize(texture2d_ms_array<T> t) {
				return int3((int) t.get_width(), (int) t.get_height(), (int) t.get_array_size());
			}""");
		m.put("imageSize", """
			// GLSL 4.60 8.12 imageSize for the image kinds the engine binds, over element and access template
			// parameters.
			template <typename T, access A>
			static inline int2 imageSize(texture2d<T, A> t) {
				return int2((int) t.get_width(), (int) t.get_height());
			}
			template <typename T, access A>
			static inline int3 imageSize(texture3d<T, A> t) {
				return int3((int) t.get_width(), (int) t.get_height(), (int) t.get_depth());
			}
			template <typename T, access A>
			static inline int2 imageSize(texturecube<T, A> t) {
				return int2((int) t.get_width(), (int) t.get_height());
			}
			template <typename T, access A>
			static inline int3 imageSize(texture2d_array<T, A> t) {
				return int3((int) t.get_width(), (int) t.get_height(), (int) t.get_array_size());
			}
			template <typename T, access A>
			static inline int3 imageSize(texturecube_array<T, A> t) {
				return int3((int) t.get_width(), (int) t.get_height(), (int) t.get_array_size());
			}
			template <typename T, access A>
			static inline int2 imageSize(texture2d_ms<T, A> t) {
				return int2((int) t.get_width(), (int) t.get_height());
			}
			template <typename T, access A>
			static inline int3 imageSize(texture2d_ms_array<T, A> t) {
				return int3((int) t.get_width(), (int) t.get_height(), (int) t.get_array_size());
			}""");
		m.put("imageCompSwap", """
			// GLSL 4.60 8.12 imageAtomicCompSwap returns the ORIGINAL texel value; MSL's compare_exchange writes the
			// original into expected, so expected.x holds it in every case (relaxed ordering, like the other image
			// atomics).
			template <typename T, access A>
			static inline T imageCompSwap(texture2d<T, A> t, uint2 c, T compare, T data) {
				vec<T, 4> expected(compare);
				t.atomic_compare_exchange_weak(c, &expected, vec<T, 4>(data));
				return expected.x;
			}
			template <typename T, access A>
			static inline T imageCompSwap(texture3d<T, A> t, uint3 c, T compare, T data) {
				vec<T, 4> expected(compare);
				t.atomic_compare_exchange_weak(c, &expected, vec<T, 4>(data));
				return expected.x;
			}
			template <typename T, access A>
			static inline T imageCompSwap(texture2d_array<T, A> t, uint2 c, uint layer, T compare, T data) {
				vec<T, 4> expected(compare);
				t.atomic_compare_exchange_weak(c, layer, &expected, vec<T, 4>(data));
				return expected.x;
			}
			template <typename T, access A>
			static inline T imageCompSwap(texture_buffer<T, A> t, uint c, T compare, T data) {
				vec<T, 4> expected(compare);
				t.atomic_compare_exchange_weak(c, &expected, vec<T, 4>(data));
				return expected.x;
			}""");
		return Map.copyOf(m);
	}

	/** Every helper key a mapping can name. */
	public static Set<String> helpers() {
		return HELPERS.keySet();
	}

	/** The MSL source for exactly the named helpers, wrapped in namespace qs; empty text for an empty set. */
	public static String render(Set<String> names) {
		StringBuilder sb = new StringBuilder();
		for (String name : names) {
			String body = HELPERS.get(name);
			if (body == null) throw new IllegalArgumentException("no prelude helper named " + name);
			sb.append(body).append("\n");
		}
		if (sb.isEmpty()) return "";
		return COMMON + "namespace qs {\n" + sb + "} // namespace qs\n";
	}

	private Prelude() {
	}
}
