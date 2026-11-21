// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.shadow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.shaders.pack.TargetUnion;
import dev.mcms.shaders.runtime.Targets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The redirector's shadow colours: the shadow layout's order, the shadowtex0 depth map, and the layout's bounds. */
class ShadowPassColoursTest {

	/** Hands out distinct non-zero handles, so a wrong attachment order shows as a different handle. */
	private static Targets.Textures textures() {
		return new Targets.Textures() {
			private long next = 100;

			@Override
			public long create(int format, int width, int height, int mips) {
				return next++;
			}

			@Override
			public void release(long handle) {
			}
		};
	}

	private static ShadowTargets targets() {
		TargetUnion union = new TargetUnion(Map.of(), Map.of(), Map.of(), Map.of(),
			Map.of(0, "RGBA8", 2, "RGBA16F"), Map.of(), List.of());
		ShadowTargets t = new ShadowTargets(union, false, true);
		assertTrue(t.create(textures()));
		return t;
	}

	@Test
	void coloursFollowTheLayoutNotTheIndexOrder() {
		ShadowTargets t = targets();
		ShadowPassColours colours = new ShadowPassColours(t, new int[] {2, 0});
		assertArrayEquals(new long[] {t.color(2), t.color(0)}, colours.colours(new long[8]));
		assertTrue(t.color(2) != t.color(0) && t.color(0) != 0L);
	}

	@Test
	void theDepthAttachmentIsShadowtex0AndTheResolutionIsTheTargets() {
		ShadowTargets t = targets();
		ShadowPassColours colours = new ShadowPassColours(t, new int[] {0});
		assertEquals(t.depth(0), colours.depth());
		assertEquals(t.resolution(), colours.resolution());
	}

	@Test
	void aLayoutNamingABufferTheTargetsLackIsRefused() {
		assertThrows(IllegalArgumentException.class, () -> new ShadowPassColours(targets(), new int[] {5}));
	}
}
