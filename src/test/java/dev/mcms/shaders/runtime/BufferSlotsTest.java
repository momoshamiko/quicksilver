// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.msl.ProgramRequest;
import org.junit.jupiter.api.Test;

/**
 * The pack block slots (EngineSlots, decided 2026-11-14): a buffer block with {@code binding = N} takes index N, and the
 * other blocks take the lowest free index in declaration order. The corpus declares no uniform blocks and at most two
 * buffer blocks per stage (Complementary, bindings 0 and 3, the same numbers as its {@code bufferObject.0} and
 * {@code bufferObject.3}).
 */
final class BufferSlotsTest {

	private static final int LIMIT = ProgramRequest.MAX_BUFFER_BINDINGS;

	private static int slotOf(BufferSlots.Assignment a, String name) {
		return a.buffers().stream().filter(b -> b.name().equals(name)).findFirst().orElseThrow().slot();
	}

	@Test
	void complementaryShapeTakesEachBufferBindingNotTheDeclarationOrder() {
		// playerVerticesBuffer is declared first but binds at 3; blockDataBuffer binds at 0.
		String src = "layout(std430, binding = 3) SSBO_QUALIFIER buffer playerVerticesBuffer { vec4 v[]; };\n"
			+ "layout(std430, binding = 0) SSBO_QUALIFIER buffer blockDataBuffer { uint d[]; };\n";
		BufferSlots.Assignment a = BufferSlots.assign(src, LIMIT);
		assertNull(a.error());
		assertEquals(3, slotOf(a, "playerVerticesBuffer"));
		assertEquals(0, slotOf(a, "blockDataBuffer"));
	}

	@Test
	void uniformBlocksTakeTheLowestIndicesThatNoBufferBindingTakes() {
		// Bar takes 0, so Foo (declared first) takes 1 and Baz takes 2.
		String src = "uniform Foo { float x; };\n"
			+ "layout(std430, binding = 0) buffer Bar { uint d[]; };\n"
			+ "layout(std140) uniform Baz { float y; };\n";
		BufferSlots.Assignment a = BufferSlots.assign(src, LIMIT);
		assertNull(a.error());
		assertEquals(1, slotOf(a, "Foo"));
		assertEquals(0, slotOf(a, "Bar"));
		assertEquals(2, slotOf(a, "Baz"));
	}

	@Test
	void aBufferWithoutABindingTakesAFreeIndex() {
		String src = "buffer Loose { uint d[]; };\nlayout(std430, binding = 0) buffer Bound { uint d[]; };\n";
		BufferSlots.Assignment a = BufferSlots.assign(src, LIMIT);
		assertNull(a.error());
		assertEquals(0, slotOf(a, "Bound"));
		assertEquals(1, slotOf(a, "Loose"));
	}

	@Test
	void aUniformBlockWithItsOwnBindingDoesNotClaimThatIndex() {
		// Only buffer blocks honour binding: a uniform's binding number is not a buffer index.
		String src = "layout(std140, binding = 0) uniform Ubo { float x; };\n";
		BufferSlots.Assignment a = BufferSlots.assign(src, LIMIT);
		assertNull(a.error());
		assertEquals(0, slotOf(a, "Ubo"));
	}

	@Test
	void aBufferBindingAboveThePackRangeFailsNamingTheBlockAndTheBinding() {
		String src = "layout(std430, binding = 20) buffer Far { uint d[]; };\n";
		BufferSlots.Assignment a = BufferSlots.assign(src, LIMIT);
		assertNotNull(a.error());
		assertTrue(a.error().contains("Far") && a.error().contains("binding 20"), a.error());
		assertTrue(a.buffers().isEmpty());
	}

	@Test
	void aBufferBindingAtTheDistantHorizonsIndexIsRefusedAndTheLastPackIndexStillBinds() {
		// The QsDh block takes EngineSlots.DISTANT_HORIZONS (19) in every DH pack: a pack block must not claim it.
		assertEquals(ProgramRequest.MAX_BUFFER_BINDINGS, EngineSlots.PACK_BLOCKS);
		assertEquals(EngineSlots.DISTANT_HORIZONS, EngineSlots.PACK_BLOCKS);
		BufferSlots.Assignment refused = BufferSlots.assign("layout(std430, binding = 19) buffer Dh { uint d[]; };\n", LIMIT);
		assertNotNull(refused.error());
		assertTrue(refused.error().contains("Dh") && refused.error().contains("binding 19"), refused.error());
		BufferSlots.Assignment last = BufferSlots.assign("layout(std430, binding = 18) buffer Last { uint d[]; };\n", LIMIT);
		assertNull(last.error());
		assertEquals(18, slotOf(last, "Last"));
	}

	@Test
	void twoBufferBlocksWithTheSameBindingFail() {
		String src = "layout(std430, binding = 2) buffer First { uint d[]; };\n"
			+ "layout(std430, binding = 2) buffer Second { uint d[]; };\n";
		BufferSlots.Assignment a = BufferSlots.assign(src, LIMIT);
		assertNotNull(a.error());
		assertTrue(a.error().contains("First") && a.error().contains("Second"), a.error());
	}

	@Test
	void aBlockDeclaredTwiceTakesOneSlot() {
		String src = "uniform Foo { float x; };\nuniform Foo { float x; };\n";
		BufferSlots.Assignment a = BufferSlots.assign(src, LIMIT);
		assertNull(a.error());
		assertEquals(1, a.buffers().size());
	}

	@Test
	void moreBlocksThanTheRangeHoldFails() {
		StringBuilder src = new StringBuilder();
		for (int i = 0; i <= LIMIT; i++) {
			src.append("uniform B").append(i).append(" { float x; };\n");
		}
		BufferSlots.Assignment a = BufferSlots.assign(src, LIMIT);
		assertNotNull(a.error());
		assertTrue(a.buffers().isEmpty());
	}

	@Test
	void aSourceWithNoBlocksHasNoSlots() {
		BufferSlots.Assignment a = BufferSlots.assign("void main() { }\n", LIMIT);
		assertNull(a.error());
		assertTrue(a.buffers().isEmpty());
	}
}
