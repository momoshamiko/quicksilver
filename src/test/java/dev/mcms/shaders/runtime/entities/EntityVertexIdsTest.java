// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.entities;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * The range bookkeeping of the per-vertex ids (plan.md E4): builder requests open ranges on the staging buffer's
 * draws, the upload's last flush closes the final one, and {@link EntityCapture.Ranges#resolve} packs each draw's
 * ranges in order at the draw's base vertex ({@code vertexOffset / stride}, assigned by upload with
 * {@code Mth.roundToward}), the address its {@code [[vertex_id]]} reads (ProgramRequest.PerVertexIds).
 */
class EntityVertexIdsTest {

	private static final int DEF = EntityIds.NO_FILE;
	private static final int[] DEFAULTS = {DEF, DEF, DEF};
	private static final int[] IDS_A = {21, 200, 7};
	private static final int[] IDS_B = {42, 240, 9};
	private static final int[] IDS_C = {5, 6, 8};

	@Test
	void mergedDrawGivesEachEntityItsOwnIds() {
		// two submits of one stride merge into one draw: entity A's vertices then entity B's, no gap
		Object draw = new Object();
		EntityCapture.Ranges ranges = new EntityCapture.Ranges();
		ranges.builderRequested(0, draw, IDS_A);
		ranges.builderRequested(96, draw, IDS_B);
		ranges.lastBuilderFlushed(192);
		ranges.addUploadedDraw(draw, 24, 0);
		ranges.resolve(192, DEFAULTS);
		byte[] buffer = ranges.sideBuffer(24).bytes();
		assertArrayEquals(IDS_A, EntityVertexIds.idAt(buffer, 0));
		assertArrayEquals(IDS_A, EntityVertexIds.idAt(buffer, 3));
		assertArrayEquals(IDS_B, EntityVertexIds.idAt(buffer, 4));
		assertArrayEquals(IDS_B, EntityVertexIds.idAt(buffer, 7));
	}

	@Test
	void theBaseVertexComesFromTheUploadNotTheStagingOffset() {
		// the draw's vertexOffset is 5 vertices into the packed buffer: its records start there, not at byte 0
		Object draw = new Object();
		EntityCapture.Ranges ranges = new EntityCapture.Ranges();
		ranges.builderRequested(0, draw, IDS_A);
		ranges.lastBuilderFlushed(48);
		ranges.addUploadedDraw(draw, 24, 5);
		ranges.resolve(48, DEFAULTS);
		byte[] buffer = ranges.sideBuffer(24).bytes();
		assertArrayEquals(DEFAULTS, EntityVertexIds.idAt(buffer, 0));
		assertArrayEquals(IDS_A, EntityVertexIds.idAt(buffer, 5));
		assertArrayEquals(IDS_A, EntityVertexIds.idAt(buffer, 6));
	}

	@Test
	void interleavedDrawsEachKeepTheirOwnPackedRanges() {
		// three draws interleaved in one staging buffer: a 36-byte entity format, a 28-byte format, entity again.
		// Upload packs them in draw order with Mth.roundToward: vertexOffsets 0, 56 and 108 (base vertices 0, 2, 3)
		Object entities = new Object(), particles = new Object(), entitiesAgain = new Object();
		EntityCapture.Ranges ranges = new EntityCapture.Ranges();
		ranges.builderRequested(0, entities, IDS_A);
		ranges.builderRequested(36, particles, IDS_B);
		ranges.builderRequested(64, entitiesAgain, IDS_C);
		ranges.lastBuilderFlushed(100);
		ranges.addUploadedDraw(entities, 36, 0);
		ranges.addUploadedDraw(particles, 28, 2);
		ranges.addUploadedDraw(entitiesAgain, 36, 3);
		ranges.resolve(100, DEFAULTS);
		byte[] entityBuffer = ranges.sideBuffer(36).bytes();
		assertArrayEquals(IDS_A, EntityVertexIds.idAt(entityBuffer, 0));
		assertArrayEquals(IDS_C, EntityVertexIds.idAt(entityBuffer, 3));
		// the vertex the padding skipped keeps the defaults
		assertArrayEquals(DEFAULTS, EntityVertexIds.idAt(entityBuffer, 1));
		byte[] particleBuffer = ranges.sideBuffer(28).bytes();
		assertArrayEquals(IDS_B, EntityVertexIds.idAt(particleBuffer, 2));
		assertArrayEquals(DEFAULTS, EntityVertexIds.idAt(particleBuffer, 0));
	}

	@Test
	void oneDrawRevisitedAcrossOtherBuildersIsPackedInItsOwnOrder() {
		// the draw is requested again after another draw's builder (getOrAddDraw revisits): its two ranges are
		// packed one after the other, so its vertices 0-1 carry A and 2-3 carry B, whatever the staging bytes between
		Object draw = new Object(), other = new Object();
		EntityCapture.Ranges ranges = new EntityCapture.Ranges();
		ranges.builderRequested(0, draw, IDS_A);
		ranges.builderRequested(48, other, DEFAULTS);
		ranges.builderRequested(76, draw, IDS_B);
		ranges.lastBuilderFlushed(124);
		ranges.addUploadedDraw(draw, 24, 0);
		ranges.addUploadedDraw(other, 28, 2);
		ranges.resolve(124, DEFAULTS);
		byte[] buffer = ranges.sideBuffer(24).bytes();
		assertArrayEquals(IDS_A, EntityVertexIds.idAt(buffer, 0));
		assertArrayEquals(IDS_A, EntityVertexIds.idAt(buffer, 1));
		assertArrayEquals(IDS_B, EntityVertexIds.idAt(buffer, 2));
		assertArrayEquals(IDS_B, EntityVertexIds.idAt(buffer, 3));
	}

	@Test
	void unclaimedVerticesCarryTheDefaultIds() {
		int[] unlisted = {EntityIds.UNLISTED, EntityIds.UNLISTED, EntityIds.UNLISTED};
		Object draw = new Object();
		EntityCapture.Ranges ranges = new EntityCapture.Ranges();
		ranges.builderRequested(0, draw, unlisted);
		ranges.builderRequested(48, draw, IDS_A);
		ranges.lastBuilderFlushed(144);
		ranges.addUploadedDraw(draw, 24, 0);
		ranges.resolve(144, unlisted);
		byte[] buffer = ranges.sideBuffer(24).bytes();
		assertArrayEquals(unlisted, EntityVertexIds.idAt(buffer, 0));
		assertArrayEquals(unlisted, EntityVertexIds.idAt(buffer, 1));
		assertArrayEquals(IDS_A, EntityVertexIds.idAt(buffer, 2));
		assertArrayEquals(IDS_A, EntityVertexIds.idAt(buffer, 5));
	}

	@Test
	void aDrawMissingFromTheUploadClaimsNoPackedVertices() {
		// the first draw's bytes are gone (it was empty at upload): the kept draw packs from vertex 0 on its own
		Object gone = new Object(), kept = new Object();
		EntityCapture.Ranges ranges = new EntityCapture.Ranges();
		ranges.builderRequested(0, gone, IDS_A);
		ranges.builderRequested(48, kept, IDS_B);
		ranges.lastBuilderFlushed(96);
		ranges.addUploadedDraw(kept, 24, 0);
		ranges.resolve(96, DEFAULTS);
		byte[] buffer = ranges.sideBuffer(24).bytes();
		assertArrayEquals(IDS_B, EntityVertexIds.idAt(buffer, 0));
		assertArrayEquals(IDS_B, EntityVertexIds.idAt(buffer, 1));
	}

	@Test
	void aStrideWithNoRangeHasNoSideBuffer() {
		Object draw = new Object();
		EntityCapture.Ranges ranges = new EntityCapture.Ranges();
		ranges.builderRequested(0, draw, DEFAULTS);
		ranges.lastBuilderFlushed(24);
		ranges.addUploadedDraw(draw, 24, 0);
		ranges.resolve(24, DEFAULTS);
		assertNull(ranges.sideBuffer(28));
	}

	@Test
	void resetStartsAFrameEmpty() {
		Object draw = new Object();
		EntityCapture.Ranges ranges = new EntityCapture.Ranges();
		ranges.builderRequested(0, draw, IDS_A);
		ranges.lastBuilderFlushed(24);
		assertEquals(1, ranges.rangeCount());
		ranges.reset();
		assertEquals(0, ranges.rangeCount());
	}

	@Test
	void theBufferRejectsNegativeVerticesAndNonPositiveStrides() {
		EntityVertexIds ids = new EntityVertexIds(24, DEF, DEF, DEF);
		assertThrows(IllegalArgumentException.class, () -> ids.vertex(-1, 1, 1, 1));
		assertThrows(IllegalArgumentException.class, () -> new EntityVertexIds(0, DEF, DEF, DEF));
		ids.build(1);
		assertEquals(EntityVertexIds.BYTES_PER_VERTEX, ids.byteCount()); // rounds up to one vertex
	}
}
