// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.shaders.pack.DimensionPrograms;
import dev.mcms.shaders.pack.ProgramCatalog.Phase;
import dev.mcms.shaders.pack.ProgramCatalog.Program;
import dev.mcms.shaders.pack.PropertiesText;
import dev.mcms.shaders.pack.ShaderProperties;
import dev.mcms.shaders.pack.ResolvedProgram;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The frame's pass ordering and the {@code flip.<program>.<target>} overrides, without a device. */
class FrameOrderTest {

	private static ResolvedProgram program(String name, Phase phase, ResolvedProgram.Source source) {
		return new ResolvedProgram(name, new Program(name, phase, null, true, false, false), source, Optional.empty(),
			Optional.empty(), List.of(), List.of(), Optional.empty());
	}

	@Test
	void runOrderIsPrepareThenDeferredThenCompositeThenFinalWhateverTheCatalogOrder() {
		DimensionPrograms scrambled = new DimensionPrograms("shaders/ root", Optional.empty(), Optional.of(""),
			"", List.of(
				program("composite2", Phase.COMPOSITE, ResolvedProgram.Source.DIRECT),
				program("final", Phase.FINAL, ResolvedProgram.Source.DIRECT),
				program("composite", Phase.COMPOSITE, ResolvedProgram.Source.DIRECT),
				program("deferred", Phase.DEFERRED, ResolvedProgram.Source.DIRECT),
				program("prepare", Phase.PREPARE, ResolvedProgram.Source.DIRECT)),
			List.of());
		// The phases in run order; inside a phase the dimension's own program order is kept (the pack model lists
		// catalog order), so composite2, listed before composite here, stays before it.
		assertEquals(List.of("prepare", "deferred", "composite2", "composite", "final"),
			DimensionSet.inRunOrder(scrambled).stream().map(ResolvedProgram::name).toList());
	}

	@Test
	void runOrderSkipsPhasesE3DoesNotRunAndProgramsThatDoNotRun() {
		DimensionPrograms mixed = new DimensionPrograms("shaders/ root", Optional.empty(), Optional.of(""),
			"", List.of(
				program("gbuffers_basic", Phase.OPAQUE, ResolvedProgram.Source.DIRECT),      // E4
				program("shadow", Phase.SHADOW, ResolvedProgram.Source.DIRECT),              // E5
				program("composite_disabled", Phase.COMPOSITE, ResolvedProgram.Source.DISABLED),
				program("deferred_absent", Phase.DEFERRED, ResolvedProgram.Source.ABSENT),
				program("composite_pre", Phase.COMPOSITE, ResolvedProgram.Source.VIRTUAL),
				program("composite_by_fallback", Phase.COMPOSITE, ResolvedProgram.Source.FALLBACK)),
			List.of());
		assertEquals(List.of("composite_by_fallback"),
			DimensionSet.inRunOrder(mixed).stream().map(ResolvedProgram::name).toList());
	}

	@Test
	void flipOverridesSkipOnePassAndTargetAndDefaultToFlipping() {
		// Through the model's own parser: the keys are the pack's target names (flip.<program>.<buffer>), aliases
		// included (OptiFine shaders.properties), never "program.index".
		ShaderProperties properties = ShaderProperties.from(PropertiesText.parse("""
			flip.composite.colortex4=false
			flip.composite1.gaux1=true
			flip.deferred3.gcolor=false
			"""));
		Map<String, Boolean> flips = properties.flips();
		assertFalse(EngineFrame.flipsTarget(flips, "composite", 4), "colortex4 by its own name");
		assertFalse(EngineFrame.flipsTarget(flips, "deferred3", 0), "gcolor is colortex0's alias");
		assertTrue(EngineFrame.flipsTarget(flips, "composite", 0), "another target of the same pass flips");
		assertTrue(EngineFrame.flipsTarget(flips, "composite1", 4), "an explicit true flips");
		assertTrue(EngineFrame.flipsTarget(flips, "composite2", 4), "another pass reading the same target flips");
		assertTrue(EngineFrame.flipsTarget(Map.of(), "deferred", 7), "no overrides: everything flips");
	}
}
