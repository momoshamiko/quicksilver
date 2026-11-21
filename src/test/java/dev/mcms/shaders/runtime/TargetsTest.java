// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.shaders.pack.ConstValue;
import dev.mcms.shaders.pack.ShaderProperties.BufferSize;
import dev.mcms.shaders.pack.TargetUnion;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The bookkeeping of the engine's render targets without a device (the {@link Targets.Textures} seam). */
class TargetsTest {

	/** A fake factory: sequential handles, and the create calls it saw. */
	private static class Fake implements Targets.Textures {
		long next;
		final List<String> calls = new java.util.ArrayList<>();
		final List<Long> released = new java.util.ArrayList<>();

		@Override
		public long create(int format, int width, int height, int mips) {
			this.calls.add(format + " " + width + "x" + height + " mips " + mips);
			return ++this.next;
		}

		@Override
		public void release(long handle) {
			this.released.add(handle);
		}
	}

	private static TargetUnion union(Map<Integer, String> formats, Map<Integer, Boolean> clears,
			Map<Integer, ConstValue> colors, Map<Integer, Boolean> mips) {
		return new TargetUnion(formats, clears, colors, mips, Map.of(), Map.of(), List.of());
	}

	private static ConstValue color(double... values) {
		return new ConstValue("vec4", java.util.Arrays.stream(values).boxed().toList());
	}

	@Test
	void countComesFromTheUsedIndexesAndNeverDropsBelowFour() {
		Fake textures = new Fake();
		assertEquals(4, new Targets(union(Map.of(), Map.of(), Map.of(), Map.of()), Map.of(), Set.of(), false, false,
			64, 48, textures, null).count());
		assertEquals(6, new Targets(union(Map.of(), Map.of(), Map.of(), Map.of()), Map.of(), Set.of(0, 1, 2, 5),
			false, false, 64, 48, textures, null).count());
	}

	@Test
	void onlyUsedTargetsAndTheFirstFourGetTextures() {
		// Targets 0, 1 and 13 are used; 2 and 3 are the documented minimum. Targets 4 to 12 no program uses hold no texture.
		Fake textures = new Fake();
		Targets targets = new Targets(union(Map.of(), Map.of(), Map.of(), Map.of()), Map.of(), Set.of(0, 1, 13), false,
			false, 64, 48, textures, null);
		assertTrue(targets.create());
		assertEquals(0, targets.read(9));
		assertEquals(0, targets.write(9));
		assertTrue(targets.read(13) != 0);
		// Five colour targets, two textures each, and the live depth texture (depthtex0 is always wanted).
		assertEquals(2 * 5 + 1, textures.calls.size());
	}

	@Test
	void flipSwapsTheHandlesAndBumpsTheGeneration() {
		Fake textures = new Fake();
		Targets targets = new Targets(union(Map.of(), Map.of(), Map.of(), Map.of()), Map.of(), Set.of(0), false,
			false, 8, 8, textures, null);
		assertTrue(targets.create());
		long firstRead = targets.read(0);
		long firstWrite = targets.write(0);
		assertEquals(firstRead + 1, firstWrite);  // the factory hands the read texture out first
		assertFalse(targets.mipsStale(0));        // no program asked for mips of target 0
		targets.flip(0);
		assertEquals(firstWrite, targets.read(0));
		assertEquals(firstRead, targets.write(0));
	}

	@Test
	void bothTexturesOfATargetAreCreatedWithTheMipChainOnlyWhenAProgramAsks() {
		Fake textures = new Fake();
		TargetUnion union = union(Map.of(1, "R32F"), Map.of(), Map.of(), Map.of(1, true));
		Targets targets = new Targets(union, Map.of(), Set.of(0, 1), false, false, 1920, 1080, textures, null);
		assertTrue(targets.create());
		// Target 0: no MipmapEnabled anywhere, one level. Target 1: mips enabled, full chain of 1920x1080 (11 levels).
		// Targets 0 and 1 as configured, targets 2 and 3 from the documented minimum of four targets.
		assertEquals(List.of("70 1920x1080 mips 1", "70 1920x1080 mips 1", "55 1920x1080 mips 11", "55 1920x1080 mips 11",
			"70 1920x1080 mips 1", "70 1920x1080 mips 1", "70 1920x1080 mips 1", "70 1920x1080 mips 1",
			"252 1920x1080 mips 1"),
			textures.calls);
		assertEquals(11, Targets.mipLevels(1920, 1080));
		assertEquals(1, Targets.mipLevels(1, 1));
		assertEquals(2, Targets.mipLevels(2, 1));
	}

	@Test
	void clearColoursComeFromTheConstantsAndTheDocumentedDefaults() {
		TargetUnion union = union(Map.of(), Map.of(2, false), Map.of(2, color(0.25, 0.5, 0.75, 1.0)), Map.of());
		Targets targets = new Targets(union, Map.of(), Set.of(0, 1, 2, 3), false, false, 8, 8, new Fake(),
			new float[] {0.2f, 0.4f, 0.6f, 1f});
		assertArrayEquals(new float[] {0.2f, 0.4f, 0.6f, 1f}, targets.clearColor(0), 1e-6f);  // colortex0: the fog colour
		assertArrayEquals(new float[] {1f, 1f, 1f, 1f}, targets.clearColor(1), 1e-6f);       // colortex1: white
		assertArrayEquals(new float[] {0.25f, 0.5f, 0.75f, 1f}, targets.clearColor(2), 1e-6f);
		assertArrayEquals(new float[] {0f, 0f, 0f, 0f}, targets.clearColor(3), 1e-6f);       // the rest: black, alpha 0
		// Without a fog colour the documented default of colortex0 is the fog constant or black with alpha 1.
		Targets noFog = new Targets(union, Map.of(), Set.of(0), false, false, 8, 8, new Fake(), null);
		assertArrayEquals(new float[] {0f, 0f, 0f, 1f}, noFog.clearColor(0), 1e-6f);
	}

	@Test
	void mipsRefillOnlyWhenTheReadTextureChangedSinceTheLastFill() {
		Fake textures = new Fake();
		TargetUnion union = union(Map.of(), Map.of(), Map.of(), Map.of(3, true));
		Targets targets = new Targets(union, Map.of(), Set.of(3), false, false, 64, 64, textures, null);
		assertTrue(targets.create());
		assertTrue(targets.mipsStale(3));           // at creation the chain has never been filled
		targets.mipsFilled(3);
		assertFalse(targets.mipsStale(3));
		targets.bump(3);                            // the engine cleared or copied into it
		assertTrue(targets.mipsStale(3));
		targets.mipsFilled(3);
		assertFalse(targets.mipsStale(3));
		targets.flip(3);                            // a pass wrote the target and the flip made it the read texture
		assertTrue(targets.mipsStale(3));
		targets.mipsFilled(3);
		assertFalse(targets.mipsStale(3));
		// A second fill with no change in between does not become stale again.
		targets.mipsFilled(3);
		assertFalse(targets.mipsStale(3));
	}

	@Test
	void sizeBufferScalesAndFixesTargetSizes() {
		Fake textures = new Fake();
		Map<String, BufferSize> sizes = Map.of(
			"colortex1", new BufferSize(0.5, 0.25, true),
			"colortex2", new BufferSize(320, 200, false));
		Targets targets = new Targets(union(Map.of(), Map.of(), Map.of(), Map.of()), sizes, Set.of(0, 1, 2), false,
			false, 1920, 1080, textures, null);
		assertEquals(1920, targets.width(0));
		assertEquals(1080, targets.height(0));
		assertEquals(960, targets.width(1));
		assertEquals(270, targets.height(1));
		assertEquals(320, targets.width(2));
		assertEquals(200, targets.height(2));
		// The legacy name of a target finds its size too, and a tiny relative size never rounds to zero.
		Map<String, BufferSize> legacy = Map.of("gaux1", new BufferSize(0.001, 0.001, true));
		Targets tiny = new Targets(union(Map.of(), Map.of(), Map.of(), Map.of()), legacy, Set.of(4), false, false,
			64, 64, new Fake(), null);
		assertEquals(1, tiny.width(4));
		assertEquals(1, tiny.height(4));
	}

	@Test
	void releaseFreesEveryTextureAndAFailedCreateFreesWhatItMade() {
		Fake textures = new Fake();
		Targets targets = new Targets(union(Map.of(), Map.of(), Map.of(), Map.of()), Map.of(), Set.of(0, 1), false,
			false, 8, 8, textures, null);
		assertTrue(targets.create());
		targets.release();
		assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L), textures.released);  // four targets, two textures each, then depthtex0

		Fake refusing = new Fake() {
			@Override
			public long create(int format, int width, int height, int mips) {
				return this.next >= 3 ? 0 : super.create(format, width, height, mips);  // the second texture of target 1
			}
		};
		Targets half = new Targets(union(Map.of(), Map.of(), Map.of(), Map.of()), Map.of(), Set.of(0, 1), false,
			false, 8, 8, refusing, null);
		assertFalse(half.create());
		assertEquals(List.of(1L, 2L, 3L), refusing.released);  // exactly the handles made before the refusal
	}

	@Test
	void theDistantHorizonsDepthTargetsAreMadeOnlyWhenWanted() {
		Fake textures = new Fake();
		Targets targets = new Targets(union(Map.of(), Map.of(), Map.of(), Map.of()), Map.of(), Set.of(), false, false,
			64, 48, textures, null);
		assertEquals(0, targets.distantDepth(0));
		assertFalse(targets.distantDepthWanted());
		targets.wantDistantDepth(true);
		assertTrue(targets.create());
		long all = targets.distantDepth(0);
		long opaque = targets.distantDepth(1);
		assertTrue(all != 0 && opaque != 0 && all != opaque, "the two DH depth targets: " + all + ", " + opaque);
		// depthtex0 is always made, so the Depth32Float textures are it and the two DH ones, at the main target's size.
		assertEquals(3, java.util.Collections.frequency(textures.calls, TargetFormats.DEPTH32_FLOAT + " 64x48 mips 1"));
		targets.release();
		assertTrue(textures.released.containsAll(List.of(all, opaque)));
		assertEquals(0, targets.distantDepth(0));
	}

	@Test
	void aRefusedDistantDepthFailsTheSetAndReleasesWhatWasMade() {
		Fake refusing = new Fake() {
			@Override
			public long create(int format, int width, int height, int mips) {
				return format == TargetFormats.DEPTH32_FLOAT ? 0 : super.create(format, width, height, mips);
			}
		};
		Targets targets = new Targets(union(Map.of(), Map.of(), Map.of(), Map.of()), Map.of(), Set.of(0), false, false,
			8, 8, refusing, null);
		targets.wantDistantDepth(true);
		assertFalse(targets.create());
		assertEquals(0, targets.distantDepth(0));
		assertEquals(refusing.next, refusing.released.size(), "every texture made before the refusal is released");
	}
}
