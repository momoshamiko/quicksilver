// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.shaders.pack.ConstValue;
import dev.mcms.shaders.pack.TargetUnion;
import dev.mcms.shaders.runtime.Targets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The shadow colour flip (the shadowcolor page's "Flipping": a composite-style write goes to the alt buffer and the pair
 * swaps when the flip is on), the depth maps' mip rule (Metal cannot mip a depth format; E5 review item 10), and the
 * layout check a set makes before it installs (E5 review item 5).
 */
class ShadowFlipAndMipTest {

	/** Records each texture's dimensions and mip count, and hands out distinct handles. */
	private static final class Recording implements Targets.Textures {
		final List<int[]> created = new ArrayList<>();
		long next = 100;

		@Override
		public long create(int format, int width, int height, int mips) {
			created.add(new int[] {format, width, height, mips});
			return next++;
		}

		@Override
		public void release(long handle) {
		}
	}

	private static ConstValue flag(boolean value) {
		return new ConstValue("bool", List.of(value ? 1.0 : 0.0));
	}

	private static TargetUnion union(Map<String, ConstValue> constants) {
		return new TargetUnion(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), constants, List.of());
	}

	@Test
	void aWriteToAFlippingBufferGoesToItsAltTextureAndTheFlipSwapsThePair() {
		ShadowTargets t = new ShadowTargets(union(Map.of()), false, true, 0, true);
		assertTrue(t.create(new Recording()));
		long main = t.color(0);
		long alt = t.colorWrite(0);
		assertTrue(alt != 0L && alt != main, "a flipping buffer holds two textures");

		t.wroteColor(0, false);  // flip off: the write stays in the alt texture, which later passes do not read
		assertEquals(main, t.color(0));
		assertEquals(alt, t.colorWrite(0));

		t.wroteColor(0, true);  // flip on: the write is now the texture later passes read
		assertEquals(alt, t.color(0));
		assertEquals(main, t.colorWrite(0));
	}

	@Test
	void aWriteRecordsNewContentSoTheMipChainIsStale() {
		ShadowTargets t = new ShadowTargets(union(Map.of("generateShadowColorMipmap", flag(true))), false, true, 0, true);
		assertTrue(t.create(new Recording()));
		int key = ShadowTargets.keyOfColor(0);
		t.mipsFilled(key);
		assertFalse(t.mipsStale(key));
		t.wroteColor(0, false);
		assertTrue(t.mipsStale(key), "a write, flipped or not, is new content for the chain");
	}

	@Test
	void aSetWithoutFlippingWritesTheBufferItself() {
		ShadowTargets t = new ShadowTargets(union(Map.of()), false, true);
		assertTrue(t.create(new Recording()));
		assertEquals(t.color(0), t.colorWrite(0));
		assertEquals(0L, t.colorWrite(7), "a buffer the set does not hold is not written");
	}

	@Test
	void theFlipBufferIsAllocatedForEveryColourBuffer() {
		Recording textures = new Recording();
		ShadowTargets t = new ShadowTargets(union(Map.of()), false, true, 3, true);
		assertTrue(t.create(textures));
		// two depth maps, then a main and an alt texture for each of the three colour buffers
		assertEquals(2 + 2 * 3, textures.created.size());
		assertNotEquals(t.color(1), t.colorWrite(1));
	}

	@Test
	void aDepthMapHasNoMipChainEvenWhenThePackAsksForOne() {
		Map<String, ConstValue> constants = Map.of("generateShadowMipmap", flag(true), "shadowtex0Mipmap", flag(true));
		ShadowTargets t = new ShadowTargets(union(constants), false, true);
		assertFalse(t.mipmappedDepth(0));
		assertFalse(t.mipmapped(ShadowTargets.keyOfDepth(0)));
		Recording textures = new Recording();
		assertTrue(t.create(textures));
		assertEquals(1, textures.created.get(0)[3], "the depth map keeps one mip level");
		assertEquals(1, textures.created.get(1)[3]);
	}

	@Test
	void aColourBufferMipChainIsTheFullChainWhenRequested() {
		ShadowTargets t = new ShadowTargets(union(Map.of("shadowcolor0Mipmap", flag(true))), false, true);
		Recording textures = new Recording();
		assertTrue(t.create(textures));
		assertEquals(11, textures.created.get(2)[3], "a 1024 map has eleven levels");
		assertEquals(1, textures.created.get(3)[3]);
	}

	@Test
	void fitsRefusesAnIndexBeyondTheColourCount() {
		ShadowTargets t = new ShadowTargets(union(Map.of()), false, true);
		assertTrue(t.fits(new int[] {0, 1}));
		assertFalse(t.fits(new int[] {0, 2}), "two buffers hold indices 0 and 1 only");
		assertFalse(t.fits(new int[] {-1}));
	}
}
