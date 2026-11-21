// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import static dev.mcms.shaders.runtime.terrain.TerrainVertexType.A_ENTITY;
import static dev.mcms.shaders.runtime.terrain.TerrainVertexType.A_MID_BLOCK;
import static dev.mcms.shaders.runtime.terrain.TerrainVertexType.A_MID_TEXCOORD;
import static dev.mcms.shaders.runtime.terrain.TerrainVertexType.A_NORMAL;
import static dev.mcms.shaders.runtime.terrain.TerrainVertexType.A_TANGENT;

import net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics;
import net.caffeinemc.mods.sodium.api.util.ColorARGB;
import net.caffeinemc.mods.sodium.api.util.ColorU8;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;

/**
 * The encoder of {@link TerrainVertexType}: Sodium's 20 compact bytes, written bit-identically (the algorithm of
 * engine-sodium-terrain.md section 1, re-implemented; {@code TerrainEncoderTest} proves the bits against Sodium's own
 * encoder on every allowed jar), then the 24 extension bytes of {@link TerrainVertex}.
 *
 * <p>Position: {@code ((x + 8) / 32) * 2^20} truncated to 20 bits per component, split into a low word (bits 0-9 of
 * each component at shifts 0/10/20) and a high word (bits 10-19 at the same shifts). Texture: each component is
 * {@code round(v * 32768) + dir} masked to 15 bits, with {@code dir} (+1 below the quad mean, -1 otherwise) recorded
 * in bit 15. Light: each of the two packed components clamped to [8, 248] after +8. The fourth word packs light
 * (16 bits), material bits (byte 2) and the section index (byte 3).
 *
 * <p>The normal is the cross of the quad's diagonals, normalised ({@code (q2 - q0) x (q3 - q1)}); a degenerate quad
 * (diagonals parallel, for example a triangle whose fourth corner repeats the first) takes the cross of its first two
 * edges, and a quad with no area takes +Y, so the normal is never zero. The tangent follows the U direction of the
 * quad's atlas coordinates. Its handedness is {@code bitangent = cross(tangent, normal) * handedness} (the
 * shaders.properties rule for {@code at_tangent}): for an unmirrored face, where the atlas V axis runs against the
 * face's up direction, that is +1, which is {@code det < 0} for the determinant of the UV edges taken from corner 0.
 *
 * <p>{@code at_midBlock.xyz} is the offset from each vertex to the block centre the vertex stores, in 1/64 m, computed
 * here from the vertex's current position: a split quad moves its vertices, and its centre does not move with them.
 */
public final class TerrainEncoder implements ChunkVertexEncoder {
	/** A squared length below this counts as zero (block units, so 1e-6 m). */
	private static final float DEGENERATE_SQUARED = 1.0e-12f;

	@Override
	public long write(long ptr, int materialBits, Vertex[] quad, int sectionIndex) {
		boolean aoInAlpha = TerrainEngine.separateAo();
		// The quad's mean UV, as Sodium's own encoder computes it (sum * 0.25, float order kept).
		float meanU = 0.0f, meanV = 0.0f;
		for (Vertex v : quad) {
			meanU += v.u;
			meanV += v.v;
		}
		meanU *= 0.25f;
		meanV *= 0.25f;

		Vertex q0 = quad[0], q1 = quad[1], q2 = quad[2], q3 = quad[3];
		// Normal: the cross of the diagonals. Each step below is taken only when the previous one is degenerate.
		float nx = (q2.y - q0.y) * (q3.z - q1.z) - (q2.z - q0.z) * (q3.y - q1.y);
		float ny = (q2.z - q0.z) * (q3.x - q1.x) - (q2.x - q0.x) * (q3.z - q1.z);
		float nz = (q2.x - q0.x) * (q3.y - q1.y) - (q2.y - q0.y) * (q3.x - q1.x);
		if (nx * nx + ny * ny + nz * nz <= DEGENERATE_SQUARED) {
			nx = (q1.y - q0.y) * (q3.z - q0.z) - (q1.z - q0.z) * (q3.y - q0.y);
			ny = (q1.z - q0.z) * (q3.x - q0.x) - (q1.x - q0.x) * (q3.z - q0.z);
			nz = (q1.x - q0.x) * (q3.y - q0.y) - (q1.y - q0.y) * (q3.x - q0.x);
		}
		float nSq = nx * nx + ny * ny + nz * nz;
		if (nSq <= DEGENERATE_SQUARED) {
			nx = 0.0f;
			ny = 1.0f;
			nz = 0.0f;
		} else {
			float inv = (float) (1.0 / Math.sqrt(nSq));
			nx *= inv;
			ny *= inv;
			nz *= inv;
		}

		// Tangent: the U direction of the atlas coordinates, from the edges leaving corner 0 (q1 and q3 are the
		// neighbours of corner 0 in Sodium's quad order). The determinant of the UV edges gives the handedness.
		float d1x = q1.x - q0.x, d1y = q1.y - q0.y, d1z = q1.z - q0.z;
		float d2x = q3.x - q0.x, d2y = q3.y - q0.y, d2z = q3.z - q0.z;
		float du1 = q1.u - q0.u, dv1 = q1.v - q0.v;
		float du2 = q3.u - q0.u, dv2 = q3.v - q0.v;
		float det = du1 * dv2 - dv1 * du2;
		float tx, ty, tz, handedness;
		if (Math.abs(det) > 1.0e-9f) {
			float r = 1.0f / det;
			tx = (dv2 * d1x - dv1 * d2x) * r;
			ty = (dv2 * d1y - dv1 * d2y) * r;
			tz = (dv2 * d1z - dv1 * d2z) * r;
			// d1 x d2 = det * (T x B), so cross(T, N) . B has the sign of -det: an unmirrored face gives +1.
			handedness = det < 0.0f ? 1.0f : -1.0f;
			float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
			if (tl > 1.0e-6f) {
				tx /= tl;
				ty /= tl;
				tz /= tl;
			}
		} else {
			// No UV area: a tangent perpendicular to the normal, so the pack never reads a zero tangent.
			if (Math.abs(nx) < 0.9f) {
				tx = 0.0f;
				ty = nz;
				tz = -ny;
			} else {
				tx = -nz;
				ty = 0.0f;
				tz = nx;
			}
			float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
			if (tl > 1.0e-6f) {
				tx /= tl;
				ty /= tl;
				tz /= tl;
			}
			handedness = 1.0f;
		}

		for (int i = 0; i < 4; i++) {
			Vertex v = quad[i];
			int qx = quantizePosition(v.x), qy = quantizePosition(v.y), qz = quantizePosition(v.z);
			MemoryIntrinsics.putInt(ptr, packPositionHi(qx, qy, qz));
			MemoryIntrinsics.putInt(ptr + 4, packPositionLo(qx, qy, qz));
			MemoryIntrinsics.putInt(ptr + 8, colour(v.color, v.ao, aoInAlpha));
			MemoryIntrinsics.putInt(ptr + 12, packTexture(encodeTexture(meanU, v.u), encodeTexture(meanV, v.v)));
			MemoryIntrinsics.putInt(ptr + 16, packLightAndData(encodeLight(v.light), materialBits, sectionIndex));
			if (v instanceof TerrainVertex t) {
				MemoryIntrinsics.putShort(ptr + A_ENTITY, (short) (t.blockId + 1));
				MemoryIntrinsics.putShort(ptr + A_ENTITY + 2, (short) (t.fluid ? 1 : 0));
				MemoryIntrinsics.putByte(ptr + A_TANGENT, snorm(tx));
				MemoryIntrinsics.putByte(ptr + A_TANGENT + 1, snorm(ty));
				MemoryIntrinsics.putByte(ptr + A_TANGENT + 2, snorm(tz));
				MemoryIntrinsics.putByte(ptr + A_TANGENT + 3, snorm(handedness));
				MemoryIntrinsics.putByte(ptr + A_NORMAL, snorm(nx));
				MemoryIntrinsics.putByte(ptr + A_NORMAL + 1, snorm(ny));
				MemoryIntrinsics.putByte(ptr + A_NORMAL + 2, snorm(nz));
				MemoryIntrinsics.putByte(ptr + A_NORMAL + 3, (byte) 0);
				MemoryIntrinsics.putByte(ptr + A_MID_BLOCK, midBlockOffset(t.centreX, v.x));
				MemoryIntrinsics.putByte(ptr + A_MID_BLOCK + 1, midBlockOffset(t.centreY, v.y));
				MemoryIntrinsics.putByte(ptr + A_MID_BLOCK + 2, midBlockOffset(t.centreZ, v.z));
				MemoryIntrinsics.putByte(ptr + A_MID_BLOCK + 3, i8(t.emission));
				MemoryIntrinsics.putFloat(ptr + A_MID_TEXCOORD, t.midU);
				MemoryIntrinsics.putFloat(ptr + A_MID_TEXCOORD + 4, t.midV);
			} else {
				// A producer that did not come through the engine's factory: the extension stays zeroed, which
				// decodes to mc_Entity.x = -1, mc_Entity.y = -1, no tangent, no normal and no emission.
				MemoryIntrinsics.putInt(ptr + A_ENTITY, 0);
				MemoryIntrinsics.putInt(ptr + A_TANGENT, 0);
				MemoryIntrinsics.putInt(ptr + A_NORMAL, 0);
				MemoryIntrinsics.putInt(ptr + A_MID_BLOCK, 0);
				MemoryIntrinsics.putFloat(ptr + A_MID_TEXCOORD, 0.0f);
				MemoryIntrinsics.putFloat(ptr + A_MID_TEXCOORD + 4, 0.0f);
			}
			ptr += TerrainVertexType.STRIDE;
		}
		return ptr;
	}

	/**
	 * The vertex colour: Sodium's ({@code ColorARGB.mulRGB}, red, green and blue scaled by the brightness, alpha kept),
	 * or under the pack's {@code separateAo} the brightness in alpha with red, green and blue unscaled. The brightness
	 * byte is Sodium's own conversion ({@code ColorU8.normalizedFloatToByte}) in both forms.
	 */
	static int colour(int argb, float brightness, boolean aoInAlpha) {
		return aoInAlpha ? ColorARGB.withAlpha(argb, ColorU8.normalizedFloatToByte(brightness))
			: ColorARGB.mulRGB(argb, brightness);
	}

	/** The offset from a vertex coordinate to the block centre, in 1/64 m, as a signed-normalised byte. */
	static byte midBlockOffset(float centre, float coordinate) {
		return i8(Math.round((centre - coordinate) * 64.0f));
	}

	/** {@code ((value + 8) / 32) * 2^20}, truncated to an int and masked to the 20 position bits. */
	static int quantizePosition(float value) {
		float normalized = (8.0f + value) / 32.0f;
		return (int) (normalized * 1048576.0f) & 0xFFFFF;
	}

	static int packPositionHi(int x, int y, int z) {
		return (x >>> 10 & 0x3FF) | (y >>> 10 & 0x3FF) << 10 | (z >>> 10 & 0x3FF) << 20;
	}

	static int packPositionLo(int x, int y, int z) {
		return x & 0x3FF | (y & 0x3FF) << 10 | (z & 0x3FF) << 20;
	}

	/** {@code round(v * 32768) + dir} in 15 bits, with dir in bit 15 (+1 when v is below the mean, -1 otherwise). */
	static int encodeTexture(float mean, float v) {
		int dir = v < mean ? 1 : -1;
		int q = Math.round(v * 32768.0f) + dir;
		return q & 0x7FFF | (dir >>> 31) << 15;
	}

	static int packTexture(int u, int v) {
		return u & 0xFFFF | (v & 0xFFFF) << 16;
	}

	static int packLightAndData(int light, int materialBits, int sectionIndex) {
		return light & 0xFFFF | (materialBits & 0xFF) << 16 | (sectionIndex & 0xFF) << 24;
	}

	/** Each packed light component clamped to [8, 248] after the +8 bias (block in byte 1, sky in byte 0). */
	static int encodeLight(int light) {
		int block = Math.max(8, Math.min(248, (light >>> 16 & 0xFF) + 8));
		int sky = Math.max(8, Math.min(248, (light & 0xFF) + 8));
		return sky & 0xFFFF | block << 8;
	}

	/** A float in [-1, 1] as a signed-normalised byte: round(value * 127) clamped to [-127, 127]. */
	static byte snorm(float value) {
		return i8(Math.round(value * 127.0f));
	}

	private static byte i8(int value) {
		return (byte) Math.max(-127, Math.min(127, value));
	}
}
