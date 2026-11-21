// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.mcms.metal.MetalHooks;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The Distant Horizons branch of {@link EngineRedirector} (plan.md E7): DH's opaque terrain pass draws into the DISTANT colours, its
 * transparent pass into the TRANSLUCENT colours, both with the engine's DH depth. A pass whose phase the set does not serve, or the engine
 * has no DH depth or colours for, is DH's own (the redirect is null).
 */
final class EngineRedirectorDistantTest {

	private static final long MAIN_COLOUR = 5, MAIN_DEPTH = 9, DISTANT_DEPTH = 17;
	private static final long[] DISTANT_COLOURS = {31, 32};
	private static final long[] TRANSLUCENT_COLOURS = {41, 42};

	/**
	 * The phase colours of the test's set: the DISTANT phase (dh_terrain) and the TRANSLUCENT phase (dh_water) each serve or not, and have
	 * colours or not.
	 */
	private static EngineRedirector.PhaseColours set(boolean servesOpaque, boolean servesWater, boolean hasColours) {
		return new EngineRedirector.PhaseColours() {
			@Override
			public long @Nullable [] colours(GbufferPhase phase, int group) {
				if (!hasColours || group != 0) {
					return null;
				}
				return switch (phase) {
					case DISTANT -> DISTANT_COLOURS;
					case TRANSLUCENT -> TRANSLUCENT_COLOURS;
					default -> null;
				};
			}

			@Override
			public boolean serves(GbufferPhase phase) {
				return switch (phase) {
					case DISTANT -> servesOpaque;
					case TRANSLUCENT -> servesWater;
					default -> false;
				};
			}
		};
	}

	private static EngineRedirector redirector(EngineRedirector.PhaseColours colours, long distantDepth) {
		GbufferDelegate delegate = new GbufferDelegate(GbufferPlans.forTest(Map.of()), null);
		EngineRedirector redirector = new EngineRedirector(colours, MAIN_DEPTH, distantDepth, 1920, 1080, null, delegate);
		redirector.mainColour(MAIN_COLOUR);
		return redirector;
	}

	@Test
	void theOpaquePassDrawsIntoTheDistantColoursAndTheEngineDistantDepth() {
		EngineRedirector redirector = redirector(set(true, true, true), DISTANT_DEPTH);
		redirector.distantPass(false);
		MetalHooks.Redirect redirect = redirector.distantRedirect();
		assertNotNull(redirect);
		assertArrayEquals(DISTANT_COLOURS, redirect.colors());
		assertEquals(DISTANT_DEPTH, redirect.depth());
		assertEquals(1920, redirect.width());
		assertEquals(1080, redirect.height());
	}

	@Test
	void theTransparentPassDrawsIntoTheTranslucentColoursAndTheEngineDistantDepth() {
		EngineRedirector redirector = redirector(set(true, true, true), DISTANT_DEPTH);
		redirector.distantPass(true);
		MetalHooks.Redirect redirect = redirector.distantRedirect();
		assertNotNull(redirect);
		assertArrayEquals(TRANSLUCENT_COLOURS, redirect.colors());
		assertEquals(DISTANT_DEPTH, redirect.depth(), "the water tests against the DH depth, not the solid depth");
	}

	@Test
	void eachPhasesRedirectIsReusedWhileItsColoursStand() {
		EngineRedirector redirector = redirector(set(true, true, true), DISTANT_DEPTH);
		redirector.distantPass(false);
		MetalHooks.Redirect opaque = redirector.distantRedirect();
		assertSame(opaque, redirector.distantRedirect());
		redirector.distantPass(true);
		MetalHooks.Redirect transparent = redirector.distantRedirect();
		assertSame(transparent, redirector.distantRedirect());
		assertFalse(opaque == transparent, "the two passes have their own redirects");
	}

	@Test
	void withoutDhTerrainInThePackTheOpaquePassIsDhsOwn() {
		EngineRedirector redirector = redirector(set(false, true, true), DISTANT_DEPTH);
		redirector.distantPass(false);
		assertNull(redirector.distantRedirect());
	}

	@Test
	void withoutDhWaterInThePackTheTransparentPassIsDhsOwn() {
		EngineRedirector redirector = redirector(set(true, false, true), DISTANT_DEPTH);
		redirector.distantPass(true);
		assertNull(redirector.distantRedirect());
	}

	@Test
	void withoutTheEngineDistantDepthThePassIsDhsOwn() {
		EngineRedirector redirector = redirector(set(true, true, true), 0);
		redirector.distantPass(false);
		assertNull(redirector.distantRedirect());
		redirector.distantPass(true);
		assertNull(redirector.distantRedirect());
	}

	@Test
	void withoutThePhaseColoursThePassIsDhsOwn() {
		EngineRedirector redirector = redirector(set(true, true, false), DISTANT_DEPTH);
		redirector.distantPass(false);
		assertNull(redirector.distantRedirect());
	}
}
