// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.buffers;

import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.pack.buffers.StorageBufferTable;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The GPU storage buffers of a set (E6-B), one per {@code bufferObject} index. A buffer is created on the render thread at
 * install, zeroed here (the device's allocation is not zeroed, so {@link #allocate} fills it), then filled from the pack's
 * initial file when it names one. A buffer keeps its contents across frames (the pack's SSBOs persist; Iris's do too).
 *
 * <p>A buffer the device cannot hold (larger than {@code maxBufferLength}) is a problem line and no buffer; a device that
 * refuses an allocation fails the whole set ({@link #create} and {@link #resize} return null or false).
 *
 * <p>On a resize, only the screen-sized buffers whose byte size changed are rebuilt (their contents restart, as Iris's
 * do); a fixed buffer keeps its contents.
 */
public final class StorageBuffers implements AutoCloseable {

	/** Indices 0 to {@link StorageBufferTable#MAX_INDEX}. */
	private final long[] handles = new long[StorageBufferTable.MAX_INDEX + 1];
	private final long[] sizes = new long[StorageBufferTable.MAX_INDEX + 1];
	private final List<String> problems = new ArrayList<>();

	private final StorageBufferTable table;
	/** The device's largest buffer in bytes. */
	private final long maxBytes;

	private StorageBuffers(StorageBufferTable table, long maxBytes) {
		this.table = table;
		this.maxBytes = maxBytes;
	}

	/**
	 * Creates every declared buffer for a view of {@code width} x {@code height} pixels. Returns null when the device refuses
	 * one of them (the caller releases nothing: this object holds no handles then).
	 */
	public static @Nullable StorageBuffers create(long ctx, StorageBufferTable table, int width, int height) {
		StorageBuffers set = new StorageBuffers(table, MetalBridge.maxBufferLength(ctx));
		for (StorageBufferTable.Spec spec : table.specs()) {
			if (!set.allocate(ctx, spec, width, height)) {
				set.close();
				return null;
			}
		}
		return set;
	}

	/**
	 * Rebuilds the screen-sized buffers whose size changed for a new view size; every other buffer (the fixed ones, and a
	 * screen-sized one whose size stays) keeps its handle and contents. Returns false when the device refuses one; the
	 * buffers are then all released.
	 */
	public boolean resize(long ctx, int width, int height) {
		this.problems.clear();
		for (StorageBufferTable.Spec spec : this.table.specs()) {
			if (!spec.relative()) {
				continue;
			}
			long size = spec.sizeFor(width, height);
			if (this.handles[spec.index()] != 0 && this.sizes[spec.index()] == size) {
				continue;
			}
			release(spec.index());
			if (!allocate(ctx, spec, width, height)) {
				close();
				return false;
			}
		}
		return true;
	}

	/**
	 * Creates one declared buffer for the view. A size the device cannot hold is a problem and no buffer (true is returned,
	 * since the device did not refuse anything); false only when the device refuses an allocation.
	 */
	private boolean allocate(long ctx, StorageBufferTable.Spec spec, int width, int height) {
		long size = spec.sizeFor(width, height);
		if (size <= 0 || size > this.maxBytes) {
			this.problems.add("bufferObject." + spec.index() + ": " + size + " bytes at this size, the device's largest buffer is "
				+ this.maxBytes + " bytes");
			return true;
		}
		long handle = MetalBridge.newBuffer(ctx, size);
		if (handle == 0) {
			return false;
		}
		// Metal does not zero a new buffer: the allocator can hand out memory another buffer held, so the contents start at zero
		// here, and the pack's initial file (if any) replaces the first bytes.
		MemorySegment contents = MemorySegment.ofAddress(MetalBridge.bufferContents(handle)).reinterpret(size);
		contents.fill((byte) 0);
		if (spec.initial() != null) {
			int copyLen = (int) Math.min(spec.initial().length, size);
			if (copyLen > 0) {
				MemorySegment.copy(spec.initial(), 0, contents, ValueLayout.JAVA_BYTE, 0, copyLen);
			}
		}
		this.handles[spec.index()] = handle;
		this.sizes[spec.index()] = size;
		return true;
	}

	private void release(int index) {
		if (this.handles[index] != 0) {
			MetalBridge.release(this.handles[index]);
			this.handles[index] = 0;
			this.sizes[index] = 0;
		}
	}

	/** The buffer of an index (its Metal buffer handle), or 0 when the pack declares none there or it was refused. */
	public long handle(int index) {
		return index < 0 || index >= this.handles.length ? 0L : this.handles[index];
	}

	/** The size of an index's buffer in bytes, or 0. */
	public long size(int index) {
		return index < 0 || index >= this.sizes.length ? 0L : this.sizes[index];
	}

	/** Why a declared buffer was not created (a size the device cannot hold), one line each, for the latest create or resize. */
	public List<String> problems() {
		return List.copyOf(this.problems);
	}

	/** Releases every buffer. */
	@Override
	public void close() {
		for (int i = 0; i < this.handles.length; i++) {
			release(i);
		}
	}
}
