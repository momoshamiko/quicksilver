// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.metal.MetalHooks;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The shadow branch of {@link EngineRedirector}: a shadow pass is drawn into the shadow targets and never into the main
 * targets (the main colour and depth handles are set below and must not appear in the redirect).
 */
final class EngineRedirectorShadowTest {

	private static final long MAIN_COLOUR = 5, MAIN_DEPTH = 9;
	private static final long SHADOW_DEPTH = 21;

	/** A shadow target set whose handles the test sets. */
	private static final class Shadow implements EngineRedirector.ShadowColours {
		long[] colours = new long[0];
		long depth = SHADOW_DEPTH;
		int resolution = 2048;
		/** The clears the next pass takes (taken once, as the targets' armed clear is). */
		float @Nullable [][] armed;

		@Override
		public long[] colours(long[] out) {
			return this.colours;
		}

		@Override
		public long depth() {
			return this.depth;
		}

		@Override
		public int resolution() {
			return this.resolution;
		}

		@Override
		public float @Nullable [][] takeClears() {
			float[][] clears = this.armed;
			this.armed = null;
			return clears;
		}
	}

	private static EngineRedirector redirector(@Nullable Shadow shadow) {
		GbufferDelegate delegate = new GbufferDelegate(GbufferPlans.forTest(Map.of()), null);
		EngineRedirector redirector = new EngineRedirector((phase, out) -> null, MAIN_DEPTH, 0, 1920, 1080, shadow, delegate);
		redirector.mainColour(MAIN_COLOUR);
		return redirector;
	}

	@Test
	void shadowPassDrawsIntoTheShadowColoursAndDepthAtTheShadowSize() {
		Shadow shadow = new Shadow();
		shadow.colours = new long[] {31, 32};
		MetalHooks.Redirect redirect = redirector(shadow).shadowRedirect();
		assertArrayEquals(new long[] {31, 32}, redirect.colors());
		assertEquals(SHADOW_DEPTH, redirect.depth());
		assertEquals(2048, redirect.width());
		assertEquals(2048, redirect.height());
	}

	@Test
	void shadowPassNeverCarriesTheMainTargets() {
		Shadow shadow = new Shadow();
		shadow.colours = new long[] {31};
		MetalHooks.Redirect redirect = redirector(shadow).shadowRedirect();
		for (long colour : redirect.colors()) {
			assertFalse(colour == MAIN_COLOUR, "a shadow colour is the main colour");
		}
		assertFalse(redirect.depth() == MAIN_DEPTH, "the shadow depth is the main depth");
		assertFalse(redirect.depth() == 0, "a zero depth keeps the pass's own, which is the main target's");
	}

	@Test
	void shadowPassWithNoShadowColourIsDepthOnly() {
		MetalHooks.Redirect redirect = redirector(new Shadow()).shadowRedirect();
		assertEquals(0, redirect.colors().length);
		assertEquals(SHADOW_DEPTH, redirect.depth());
	}

	@Test
	void shadowRedirectIsReusedWhileTheTargetsStand() {
		EngineRedirector redirector = redirector(new Shadow());
		assertSame(redirector.shadowRedirect(), redirector.shadowRedirect());
	}

	@Test
	void firstShadowPassClearsItsAttachmentsAndTheReopenLoads() {
		Shadow shadow = new Shadow();
		shadow.colours = new long[] {31, 32};
		float[] white = {1f, 1f, 1f, 1f};
		shadow.armed = new float[][] {white, null};
		EngineRedirector redirector = redirector(shadow);
		MetalHooks.Redirect first = redirector.shadowRedirect();
		assertSame(white, first.clears()[0], "the cleared colour attachment takes its clear colour");
		assertEquals(null, first.clears()[1], "an attachment the pack does not clear loads");
		assertEquals(1f, first.depthClear(), "shadowtex0 clears to 1.0 in the same pass");
		// The reopen after the shadowtex1 copy must load: a clear there would drop the opaque casters already drawn.
		MetalHooks.Redirect reopen = redirector.shadowRedirect();
		assertEquals(null, reopen.clears());
		assertTrue(Float.isNaN(reopen.depthClear()), "the reopen loads shadowtex0");
		assertArrayEquals(new long[] {31, 32}, reopen.colors());
		assertEquals(SHADOW_DEPTH, reopen.depth());
	}

	@Test
	void shadowPassWithoutShadowTargetsIsRefusedNotDrawnIntoTheMainTargets() {
		assertThrows(IllegalStateException.class, () -> redirector(null).shadowRedirect());
	}

	@Test
	void shadowPassWithRefusedDepthMapIsRefused() {
		Shadow shadow = new Shadow();
		shadow.depth = 0;
		assertThrows(IllegalStateException.class, () -> redirector(shadow).shadowRedirect());
	}
}
