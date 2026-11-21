// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.pipeline.BlendFunction;
import dev.mcms.shaders.pack.ShaderProperties;
import dev.mcms.shaders.pack.ProgramCatalog;
import dev.mcms.shaders.pack.ResolvedProgram;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The blend a pack buffer draws with (E4 and E5): a directive for the buffer, the program, or its serving program; without
 * one, the game pipeline's own blend for a gbuffers draw (the shadow pass passes none: its draws blend only by directive);
 * {@code off} is a directive that disables it.
 */
final class ShadowBlendTest {

	private static final ShaderProperties.BlendState OFF = new ShaderProperties.BlendState(true, "", "", "", "");
	private static final ShaderProperties.BlendState ADD = new ShaderProperties.BlendState(false, "SRC_COLOR", "ONE",
		"ONE", "ZERO");
	private static final List<String> SHADOW0 = List.of("shadowcolor0");
	private static final List<String> SHADOW1 = List.of("shadowcolor1");
	private static final List<String> COLOUR0 = List.of("colortex0");
	private static final List<String> COLOUR1 = List.of("colortex1");

	@Test
	void aShadowProgramTakesTheBlendOfItsOwnBuffer() {
		Map<String, ShaderProperties.BlendState> blends = Map.of("shadow_solid.shadowcolor0", ADD);
		assertSame(ADD, GbufferPlans.blendOf(blends, "shadow_solid", "shadow", SHADOW0, null));
		assertNull(GbufferPlans.blendOf(blends, "shadow_solid", "shadow", SHADOW1, null));
	}

	@Test
	void blendShadowServesEveryShadowProgramThatSetsNoBlendOfItsOwn() {
		Map<String, ShaderProperties.BlendState> blends = Map.of("shadow", OFF);
		// The serving program is the resolved program's servedBy (GbufferPlans' descriptor reads it so), not a name typed in.
		ResolvedProgram cutout = new ResolvedProgram("shadow_cutout", new ProgramCatalog.Program("shadow_cutout",
			ProgramCatalog.Phase.SHADOW, "shadow", false, false, false), ResolvedProgram.Source.FALLBACK,
			Optional.of("shadow"), Optional.empty(), List.of(), List.of(), Optional.empty());
		assertSame(OFF, GbufferPlans.blendOf(blends, cutout.name(), cutout.servedBy().orElse(cutout.name()), SHADOW0, null));
		assertSame(OFF, GbufferPlans.blendOf(blends, "shadow", "shadow", SHADOW0, null));
	}

	@Test
	void aProgramsOwnBlendOverridesTheServingProgramsBlend() {
		Map<String, ShaderProperties.BlendState> blends = Map.of("shadow", OFF, "shadow_solid", ADD);
		assertSame(ADD, GbufferPlans.blendOf(blends, "shadow_solid", "shadow", SHADOW0, null));
		assertSame(OFF, GbufferPlans.blendOf(blends, "shadow_cutout", "shadow", SHADOW0, null));
	}

	@Test
	void aBufferDirectiveOverridesTheWholeProgramForItsBufferOnly() {
		Map<String, ShaderProperties.BlendState> blends = Map.of("shadow", OFF, "shadow.shadowcolor0", ADD);
		assertSame(ADD, GbufferPlans.blendOf(blends, "shadow_solid", "shadow", SHADOW0, null));
		assertSame(OFF, GbufferPlans.blendOf(blends, "shadow_solid", "shadow", SHADOW1, null));
	}

	@Test
	void noDirectiveAndNoGameBlendMeansNoBlend() {
		assertNull(GbufferPlans.blendOf(Map.of(), "shadow_solid", "shadow", SHADOW0, null));
	}

	@Test
	void noDirectiveTakesTheGamePipelinesBlendOverEveryBuffer() {
		ShaderProperties.BlendState game = GbufferPlans.blendOf(Map.of(), "gbuffers_water", "gbuffers_water", COLOUR0,
			BlendFunction.TRANSLUCENT);
		assertFalse(game.off());
		assertEquals("SRC_ALPHA", game.src());
		assertEquals("ONE_MINUS_SRC_ALPHA", game.dst());
		assertEquals("ONE", game.srcAlpha());
		assertEquals("ONE_MINUS_SRC_ALPHA", game.dstAlpha());
		assertEquals(game.src(), GbufferPlans.blendOf(Map.of(), "gbuffers_water", "gbuffers_water", COLOUR1,
			BlendFunction.TRANSLUCENT).src());
	}

	@Test
	void aProgramDirectiveOffReplacesTheGamePipelinesBlend() {
		assertSame(OFF, GbufferPlans.blendOf(Map.of("gbuffers_water", OFF), "gbuffers_water", "gbuffers_water", COLOUR0,
			BlendFunction.TRANSLUCENT));
	}

	@Test
	void aPerBufferDirectiveReplacesTheGameBlendForItsBufferOnly() {
		Map<String, ShaderProperties.BlendState> blends = Map.of("gbuffers_water.colortex0", OFF);
		assertSame(OFF, GbufferPlans.blendOf(blends, "gbuffers_water", "gbuffers_water", COLOUR0,
			BlendFunction.TRANSLUCENT));
		ShaderProperties.BlendState other = GbufferPlans.blendOf(blends, "gbuffers_water", "gbuffers_water", COLOUR1,
			BlendFunction.TRANSLUCENT);
		assertTrue(other != null && !other.off(), "the other buffer keeps the game pipeline's blend");
	}
}
