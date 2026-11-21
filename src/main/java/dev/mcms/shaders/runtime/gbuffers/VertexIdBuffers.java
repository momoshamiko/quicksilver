// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.runtime.entities.EntityCapture;
import dev.mcms.shaders.runtime.entities.EntityVertexIds;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The per-vertex id side buffers the vertex stage reads at {@code [[vertex_id]]} (plan.md E4, ProgramRequest.PerVertexIds).
 *
 * <p>Each staged vertex buffer's upload publishes its ranges' ids ({@link #publish}): one device buffer per stride the
 * upload packed, keyed by the vertex buffer object, so a draw finds the records of the upload that wrote its vertices.
 * A publish replaces everything the same vertex buffer served before, because the game pools vertex buffers and a
 * reused one holds the new frame's vertices. An entry serves its vertex buffer only while that buffer is open: the
 * game may close a pooled buffer and hand its address to a new one, and the new buffer must not read the old records.
 * The bytes go through the game's command encoder, which orders each write against the GPU's reads of the buffer's
 * previous contents: every bind marks its side buffer used ({@link MetalBridge#renderBuffer}).
 *
 * <p>A draw no upload covers (Sodium's terrain, the sky, the clouds, the hand without records) binds one shared
 * defaults record instead ({@link #bind} returns false), made on the first such bind; the QsDraw flag tells the prologue
 * to read record 0.
 *
 * <p>Allocation: an entry's device buffer and staging memory grow only when a frame needs more than they hold; the
 * steady-state publish and bind allocate nothing on the Java heap.
 */
final class VertexIdBuffers {

	/** The usage of the side buffers: read by the vertex stage, written by the engine's copies. */
	private static final int USAGE = GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST;

	/** One (vertex buffer, stride) side buffer: its device buffer and the staging memory its bytes are copied through. */
	private static final class Entry {
		/** The vertex buffer this side buffer serves; null while the entry is free. */
		@Nullable GpuBuffer vertex;
		int stride;
		@Nullable GpuBuffer buffer;
		/** The side buffer's whole extent, made with the buffer; a write copies the record bytes into its start. */
		@Nullable GpuBufferSlice whole;
		ByteBuffer staging = ByteBuffer.allocateDirect(0);
	}

	private Entry[] entries = new Entry[0];
	private int entryCount;
	/** The defaults record: one record of the id maps' defaults, read by a draw with no records (made on the first bind). */
	private @Nullable GpuBuffer defaults;

	/**
	 * Replaces what vertex buffer {@code vertex} serves with the side buffers of one upload: {@code ranges} (already
	 * resolved for the upload) gives one side buffer per stride the upload packed. Entries of closed vertex buffers are
	 * released here too, so no entry keeps a buffer the game dropped.
	 */
	void publish(GpuBuffer vertex, EntityCapture.Ranges ranges) {
		for (int i = 0; i < entryCount; i++) {
			Entry e = entries[i];
			if (e.vertex != null && (e.vertex == vertex || e.vertex.isClosed())) {
				e.vertex = null;
			}
		}
		GpuDevice device = RenderSystem.getDevice();
		CommandEncoder encoder = device.createCommandEncoder();
		for (int p = 0; p < ranges.sideBufferCount(); p++) {
			EntityVertexIds ids = ranges.sideBufferAt(p);
			if (ids.byteCount() == 0) {
				continue;
			}
			write(device, encoder, acquire(vertex, ids.stride()), ids);
		}
	}

	/**
	 * Binds the side buffer of vertex buffer {@code vertex} (its native handle) at {@code stride} to {@code slot}, or the
	 * defaults record when no open upload served that stride. Returns true when the draw's own records bound, false when
	 * the defaults did. {@code defaultIds} are the id maps' defaults; they are read only by the bind that makes the record.
	 */
	boolean bind(long enc, int slot, long vertex, int stride, int[] defaultIds) {
		for (int i = 0; i < entryCount; i++) {
			Entry e = entries[i];
			if (e.vertex != null && e.buffer != null && e.stride == stride && !e.vertex.isClosed()
					&& MetalBridge.bufferHandle(e.vertex) == vertex) {
				MetalBridge.renderBuffer(enc, slot, e.buffer, 0);
				return true;
			}
		}
		if (defaults == null) {
			defaults = defaultsBuffer(defaultIds);
		}
		MetalBridge.renderBuffer(enc, slot, defaults, 0);
		return false;
	}

	/** A free entry of this stride, else any free entry, else a new one; claimed for {@code vertex}. */
	private Entry acquire(GpuBuffer vertex, int stride) {
		Entry any = null;
		for (int i = 0; i < entryCount; i++) {
			Entry e = entries[i];
			if (e.vertex != null) {
				continue;
			}
			if (e.stride == stride) {
				any = e;
				break;
			}
			if (any == null) {
				any = e;
			}
		}
		if (any == null) {
			if (entryCount == entries.length) {
				entries = Arrays.copyOf(entries, Math.max(4, entries.length * 2));
			}
			any = new Entry();
			entries[entryCount++] = any;
		}
		any.vertex = vertex;
		any.stride = stride;
		return any;
	}

	/** Copies one side buffer's bytes into the entry's device buffer (grown when the bytes do not fit). */
	private static void write(GpuDevice device, CommandEncoder encoder, Entry e, EntityVertexIds ids) {
		int bytes = ids.byteCount();
		if (e.buffer == null || e.buffer.size() < bytes) {
			long size = e.buffer == null ? bytes : Math.max(bytes, 2 * e.buffer.size());
			if (e.buffer != null) {
				e.buffer.close();
			}
			GpuBuffer buffer = device.createBuffer(() -> "mcms per-vertex ids", USAGE, size);
			e.buffer = buffer;
			e.whole = buffer.slice(0, size);
		}
		if (e.staging.capacity() < bytes) {
			e.staging = ByteBuffer.allocateDirect(Math.max(bytes, 2 * e.staging.capacity()));
		}
		e.staging.clear();
		e.staging.put(0, ids.bytes(), 0, bytes);
		e.staging.limit(bytes);
		encoder.writeToBuffer(Objects.requireNonNull(e.whole), e.staging);
	}

	/** The defaults record: the id maps' defaults, little-endian, as {@link EntityVertexIds} packs them. */
	private static GpuBuffer defaultsBuffer(int[] ids) {
		ByteBuffer value = ByteBuffer.allocateDirect(EntityVertexIds.BYTES_PER_VERTEX).order(ByteOrder.LITTLE_ENDIAN);
		value.putInt(0, ids[0]).putInt(4, ids[1]).putInt(8, ids[2]);
		return RenderSystem.getDevice().createBuffer(() -> "mcms default per-vertex ids", USAGE, value);
	}

	/** Releases every device buffer. The registry binds nothing after it. */
	void close() {
		for (int i = 0; i < entryCount; i++) {
			Entry e = entries[i];
			if (e.buffer != null) {
				e.buffer.close();
			}
			e.buffer = null;
			e.whole = null;
			e.vertex = null;
		}
		entryCount = 0;
		if (defaults != null) {
			defaults.close();
			defaults = null;
		}
	}
}
