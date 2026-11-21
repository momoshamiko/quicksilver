// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.shaders.pack.ProgramCatalog;
import dev.mcms.shaders.pack.ResolvedProgram;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.gbuffers.GbufferMapping.Row;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The Distant Horizons rows of the mapping (plan.md E7): DH's opaque terrain draws in the DISTANT phase through {@code dh_terrain}, its
 * transparent terrain in the TRANSLUCENT phase through {@code dh_water}; neither in a shadow pass or a hand pass.
 */
class GbufferDistantMappingTest {

	@Test
	void theOpaqueLocationDrawsInTheDistantPhaseAndTheTransparentOneInTheTranslucentPhase() {
		assertArrayEquals(new GbufferPhase[] {GbufferPhase.DISTANT}, GbufferMapping.phases(PassKind.GBUFFERS, "opaque_terrain"));
		assertArrayEquals(new GbufferPhase[] {GbufferPhase.TRANSLUCENT},
			GbufferMapping.phases(PassKind.GBUFFERS, "transparent_terrain"));
	}

	@Test
	void theSolidTerrainIsNotTheDistantPhase() {
		assertArrayEquals(new GbufferPhase[] {GbufferPhase.OPAQUE}, GbufferMapping.phases(PassKind.GBUFFERS, "solid_terrain"));
	}

	@Test
	void aHandPassDrawsNoDistantTerrain() {
		assertEquals(0, GbufferMapping.phases(PassKind.HAND, "opaque_terrain").length);
		assertEquals(0, GbufferMapping.phases(PassKind.HAND, "transparent_terrain").length);
	}

	@Test
	void theDistantRowsNameTheDistantHorizonsPrograms() {
		Optional<Row> opaque = GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.DISTANT, "opaque_terrain");
		Optional<Row> transparent = GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.TRANSLUCENT, "transparent_terrain");
		assertEquals(List.of("dh_terrain"), opaque.orElseThrow().chain());
		assertEquals(List.of("dh_water"), transparent.orElseThrow().chain());
	}

	@Test
	void theDistantLocationsHaveNoShadowRow() {
		assertFalse(GbufferMapping.row(PassKind.SHADOW, GbufferPhase.OPAQUE, "opaque_terrain").isPresent());
		assertFalse(GbufferMapping.row(PassKind.SHADOW, GbufferPhase.OPAQUE, "transparent_terrain").isPresent());
	}

	@Test
	void theDistantLocationsAreRecognised() {
		assertTrue(GbufferMapping.isDistant("opaque_terrain"));
		assertTrue(GbufferMapping.isDistant("transparent_terrain"));
		assertFalse(GbufferMapping.isDistant("solid_terrain"));
		assertFalse(GbufferMapping.isDistant("translucent_terrain"));
	}

	@Test
	void theDistantProgramsAndPipelinesAreRecognised() {
		assertTrue(DistantPipelines.isProgram("dh_terrain"));
		assertTrue(DistantPipelines.isProgram("dh_water"));
		assertFalse(DistantPipelines.isProgram("gbuffers_terrain"));
		assertTrue(DistantPipelines.isPipeline("distanthorizons:opaque_terrain"));
		assertTrue(DistantPipelines.isPipeline("distanthorizons:transparent_terrain"));
		assertFalse(DistantPipelines.isPipeline("minecraft:solid"));
		assertFalse(DistantPipelines.isPipeline("distanthorizons:other_pipeline"));
	}

	@Test
	void theDistantPipelinesArePlannedOnlyWhenDistantHorizonsDrawsThroughThePack() {
		ResolvedProgram dhTerrain = new ResolvedProgram("dh_terrain", ProgramCatalog.all().stream()
			.filter(p -> p.name().equals("dh_terrain")).findFirst().orElseThrow(), ResolvedProgram.Source.values()[0],
			Optional.empty(), Optional.empty(), List.of(), List.of(), Optional.empty());
		Map<String, ResolvedProgram> withDh = Map.of("dh_terrain", dhTerrain);
		assertTrue(GbufferPlans.plansDistant(StandardMacros.engine(true), withDh));
		assertFalse(GbufferPlans.plansDistant(StandardMacros.engine(false), withDh), "no DH define: no DH plans");
		assertFalse(GbufferPlans.plansDistant(StandardMacros.engine(true), Map.of()), "no dh_ program: no DH plans");
	}
}
