/*
 * Part of MC Metal Shaders (mcms-metal), 2026-10-12 (M4). Adapted from Vitrail 0.12.0-beta,
 * common/src/main/java/dev/vitrail/render/storage/StorageBuffers.java (ensure, allocate, rebind, zero, aligned, Allocated),
 * Copyright the Vitrail authors, licensed LGPL-3.0-only. This file is therefore LGPL-3.0-only (see mod/mcms-metal/README.md, licences).
 */
package dev.mcms.metal.vitrail;

import dev.mcms.metal.MetalStorage;
import dev.vitrail.Vitrail;
import dev.vitrail.pack.model.BufferObject;
import dev.vitrail.render.storage.StorageBuffers;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One Vitrail StorageBuffers set (a pack's bufferObject. directives) held on Metal: private MTLBuffers, zeroed whole once per allocation
 * as Vitrail does it on Vulkan (Complementary reads cells it never wrote and expects zero). mixin.VitrailStorageBuffersMixin calls it and
 * puts what bindings() answers into Vitrail's own map, so Vitrail's lookup answers Metal buffers.
 */
public final class MetalStorageBuffers {
	private final BufferObject.Reading declared;
	private final List<Allocated> allocated = new ArrayList<>();
	private int lastWidth;
	private int lastHeight;

	public MetalStorageBuffers(BufferObject.Reading declared) {
		this.declared = declared;
	}

	/** StorageBuffers.ensure from its device check on, without the placeholder (the mixin keeps Vitrail's own). */
	public boolean ensure(int screenWidth, int screenHeight) {
		if (this.declared.buffers().isEmpty()) return false;
		boolean first = this.allocated.isEmpty();
		boolean resized = screenWidth != this.lastWidth || screenHeight != this.lastHeight;
		if (!first && !resized) return false;
		if (first) {
			for (BufferObject buffer : this.declared.buffers()) {
				if (buffer.relative()) continue;
				this.allocated.add(new Allocated(buffer, false, aligned(buffer.size())));
				Vitrail.logger().info("storage buffer {}", buffer.describe());
			}
		}

		List<Allocated> kept = new ArrayList<>();
		for (Allocated buffer : this.allocated) {
			if (buffer.relative) buffer.release();
			else kept.add(buffer);
		}

		this.allocated.clear();
		this.allocated.addAll(kept);
		for (BufferObject buffer : this.declared.buffers()) {
			if (!buffer.relative()) continue;
			long bytes = aligned((long) (screenWidth * buffer.scaleX()) * (long) (screenHeight * buffer.scaleY()) * buffer.size());
			this.allocated.add(new Allocated(buffer, true, Math.max(bytes, 4L)));
			Vitrail.logger().info("storage buffer {} at {} bytes", buffer.describe(), bytes);
		}

		this.lastWidth = screenWidth;
		this.lastHeight = screenHeight;
		zero();
		return true;
	}

	/** Each allocation under the index the pack declared it at (StorageBuffers.rebind), its MTLBuffer as the buffer. */
	public Map<Integer, StorageBuffers.Bound> bindings() {
		Map<Integer, StorageBuffers.Bound> bound = new HashMap<>();
		for (Allocated buffer : this.allocated) bound.putIfAbsent(buffer.declared.index(), new StorageBuffers.Bound(buffer.buffer, buffer.bytes));
		return Map.copyOf(bound);
	}

	/** StorageBuffers.zero: every buffer made since the last call, zeroed whole, on the frame that made it. */
	private void zero() {
		boolean ended = false;
		for (Allocated buffer : this.allocated) {
			if (buffer.zeroed) continue;
			if (!ended) {
				MetalStorage.endPass();
				ended = true;
			}
			MetalStorage.zeroBuffer(buffer.buffer, buffer.bytes);
			buffer.zeroed = true;
			Vitrail.logger().info("storage buffer {} zeroed whole at {} bytes", buffer.declared.describe(), buffer.bytes);
		}
	}

	private static long aligned(long size) {
		return Math.max(4L, (size + 3L) & ~3L);
	}

	/** The device half of StorageBuffers.close. */
	public void close() {
		this.allocated.forEach(Allocated::release);
		this.allocated.clear();
		this.lastWidth = 0;
		this.lastHeight = 0;
	}

	private static final class Allocated {
		final BufferObject declared;
		final boolean relative;
		final long bytes;
		final long buffer;
		boolean zeroed;

		Allocated(BufferObject declared, boolean relative, long bytes) {
			this.declared = declared;
			this.relative = relative;
			this.bytes = bytes;
			this.buffer = MetalStorage.newBuffer(bytes);
		}

		void release() {
			MetalStorage.releaseAfterGpu(this.buffer);
		}
	}
}
