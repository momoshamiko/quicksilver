// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.entities;

import java.util.Arrays;

/**
 * One vertex stride's side buffer of per-vertex ids (plan.md E4): one record of three ints (entityId,
 * blockEntityId, currentRenderedItemId, little-endian) per vertex of the uploaded vertex buffer, at the address a
 * draw's {@code [[vertex_id]]} reads (ProgramRequest.PerVertexIds): the draw's base vertex plus the vertex's offset
 * inside the draw. {@link EntityCapture.Ranges} computes those addresses; this class writes records at absolute
 * vertex indexes and keeps unclaimed vertices at the defaults. The arrays are reused across frames (reset), so the
 * frame path allocates nothing once the buffers have grown to the scene's size.
 */
public final class EntityVertexIds {

	/** The side buffer's record size in bytes (ProgramRequest.PerVertexIds: three ints). */
	public static final int BYTES_PER_VERTEX = 12;

	private int stride;
	private int defaultEntity;
	private int defaultBlockEntity;
	private int defaultItem;
	private int[] ids = new int[3 * 16];
	private int vertices;
	private byte[] bytes = new byte[0];
	private int byteCount;

	public EntityVertexIds(int stride, int entityId, int blockEntityId, int itemId) {
		reset(stride, entityId, blockEntityId, itemId);
	}

	/**
	 * Starts a new frame for this stride: no vertex is claimed, unclaimed vertices read the given defaults (the id
	 * maps' unlisted or absent values).
	 */
	public void reset(int stride, int entityId, int blockEntityId, int itemId) {
		if (stride <= 0) {
			throw new IllegalArgumentException("stride " + stride);
		}
		this.stride = stride;
		this.defaultEntity = entityId;
		this.defaultBlockEntity = blockEntityId;
		this.defaultItem = itemId;
		this.vertices = 0;
	}

	/** The vertex size this buffer's records belong to. */
	public int stride() {
		return stride;
	}

	/** Writes one vertex's record at an absolute vertex index (growing the buffer as needed). */
	public void vertex(int index, int entityId, int blockEntityId, int itemId) {
		if (index < 0) {
			throw new IllegalArgumentException("vertex " + index);
		}
		ensure(index + 1);
		ids[index * 3] = entityId;
		ids[index * 3 + 1] = blockEntityId;
		ids[index * 3 + 2] = itemId;
	}

	private void ensure(int count) {
		if (count <= vertices) {
			return;
		}
		int capacity = ids.length / 3;
		if (count > capacity) {
			int grown = capacity;
			while (grown < count) {
				grown *= 2;
			}
			ids = Arrays.copyOf(ids, grown * 3);
			fillDefaults(capacity, grown);
		}
		fillDefaults(vertices, count);
		vertices = count;
	}

	private void fillDefaults(int from, int to) {
		for (int v = from; v < to; v++) {
			ids[v * 3] = defaultEntity;
			ids[v * 3 + 1] = defaultBlockEntity;
			ids[v * 3 + 2] = defaultItem;
		}
	}

	/**
	 * Packs the records into {@link #bytes()} for an upload of {@code totalBytes} staging bytes. The upload pads each
	 * draw's start to its own stride, so the packed buffer can address more vertices than the staging bytes hold: the
	 * side buffer covers every vertex a range wrote, and the staging size otherwise.
	 */
	public void build(long totalBytes) {
		int count = Math.max(vertices, (int) ((totalBytes + stride - 1) / stride));
		ensure(count);
		int size = count * BYTES_PER_VERTEX;
		if (bytes.length < size) {
			bytes = new byte[Math.max(size, bytes.length * 2)];
		}
		for (int i = 0; i < count * 3; i++) {
			int value = ids[i];
			int at = i * 4;
			bytes[at] = (byte) value;
			bytes[at + 1] = (byte) (value >>> 8);
			bytes[at + 2] = (byte) (value >>> 16);
			bytes[at + 3] = (byte) (value >>> 24);
		}
		byteCount = size;
	}

	/** The packed side-buffer bytes of the last {@link #build}; the first {@link #byteCount()} are valid. */
	public byte[] bytes() {
		return bytes;
	}

	/** The number of valid bytes in {@link #bytes()} after the last build. */
	public int byteCount() {
		return byteCount;
	}

	/** The ids of one vertex of a packed side buffer, for tests and the engine's CPU-side checks. */
	public static int[] idAt(byte[] buffer, int vertexIndex) {
		int at = vertexIndex * BYTES_PER_VERTEX;
		return new int[] {readInt(buffer, at), readInt(buffer, at + 4), readInt(buffer, at + 8)};
	}

	private static int readInt(byte[] b, int at) {
		return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8 | (b[at + 2] & 0xff) << 16 | (b[at + 3] & 0xff) << 24;
	}
}
