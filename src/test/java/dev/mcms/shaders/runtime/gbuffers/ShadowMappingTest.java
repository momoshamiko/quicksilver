// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The shadow kind's mapping (plan.md E5, "The pass"; engine-pack-format.md sections 2 and 16): a shadow draw of each
 * kind of geometry goes to the documented shadow program with its fallback {@code shadow}, and the locations the shadow
 * pass does not draw have no row, so the game's own shader never draws into the shadow targets.
 */
class ShadowMappingTest {

	private static List<String> chain(String location, GbufferMapping.Domain domain) {
		Optional<GbufferMapping.Row> row = GbufferMapping.row(PassKind.SHADOW, GbufferPhase.OPAQUE, location, domain,
			false);
		assertTrue(row.isPresent(), location + " should draw in the shadow pass");
		return row.get().chain();
	}

	private static boolean hasShadowRow(String location, GbufferMapping.Domain domain) {
		return GbufferMapping.row(PassKind.SHADOW, GbufferPhase.OPAQUE, location, domain, false).isPresent();
	}

	@Test
	void terrainTakesItsOwnShadowProgramThenShadow() {
		assertEquals(List.of("shadow_solid", "shadow"), chain("solid_terrain", GbufferMapping.Domain.ENTITY));
		assertEquals(List.of("shadow_cutout", "shadow"), chain("cutout_terrain", GbufferMapping.Domain.ENTITY));
		assertEquals(List.of("shadow_water", "shadow"), chain("translucent_terrain", GbufferMapping.Domain.ENTITY));
	}

	@Test
	void cutoutTerrainTakesShadowCutoutWithTheSolidStage() {
		// Iris 1.11.7 (RIL-570, mcms-alpharef's shadow view): Sodium's cutout terrain reports TERRAIN_SOLID in the shadow
		// pass as well; the solid stage must not move it onto shadow_solid.
		GbufferMapping.Row cutout = GbufferMapping.row(PassKind.SHADOW, GbufferPhase.OPAQUE, "cutout_terrain",
			GbufferMapping.Domain.ENTITY, false).orElseThrow();
		assertEquals(List.of("shadow_cutout", "shadow"), cutout.chain());
		assertEquals("TERRAIN_SOLID", cutout.renderStage());
	}

	@Test
	void entitiesAndTheBeaconBeamTakeShadowEntities() {
		assertEquals(List.of("shadow_entities", "shadow"), chain("entity_solid", GbufferMapping.Domain.ENTITY));
		assertEquals(List.of("shadow_entities", "shadow"), chain("entity_translucent", GbufferMapping.Domain.ENTITY));
		assertEquals(List.of("shadow_entities", "shadow"), chain("item_cutout", GbufferMapping.Domain.ENTITY));
		assertEquals(List.of("shadow_entities", "shadow"), chain("beacon_beam_opaque", GbufferMapping.Domain.BLOCK_ENTITY));
	}

	@Test
	void blockEntitiesTakeShadowBlock() {
		assertEquals(List.of("shadow_block", "shadow"), chain("solid_block", GbufferMapping.Domain.BLOCK_ENTITY));
		assertEquals(List.of("shadow_block", "shadow"), chain("translucent_block", GbufferMapping.Domain.BLOCK_ENTITY));
		// An entity-pipeline draw submitted by a block-entity renderer (a chest's model) is block-entity geometry.
		assertEquals(List.of("shadow_block", "shadow"), chain("entity_solid", GbufferMapping.Domain.BLOCK_ENTITY));
	}

	@Test
	void theEntityDomainNeverTakesShadowBlock() {
		// The routing (ShadowEntityDispatch.route) draws the entities inside the ENTITY window: their draws never map to shadow_block.
		assertEquals(List.of("shadow_entities", "shadow"), chain("entity_solid", GbufferMapping.Domain.ENTITY));
		assertEquals(List.of("shadow_entities", "shadow"), chain("entity_cutout", GbufferMapping.Domain.ENTITY));
	}

	@Test
	void everyShadowChainEndsAtShadowAndHasNoTexturedFallback() {
		for (String location : new String[] {"solid_terrain", "cutout_terrain", "translucent_terrain", "entity_solid",
			"solid_block", "beacon_beam_opaque"}) {
			List<String> chain = chain(location, GbufferMapping.Domain.ENTITY);
			assertEquals("shadow", chain.get(chain.size() - 1), location);
			assertFalse(chain.contains("textured"), location);
			assertFalse(chain.contains("basic"), location);
		}
	}

	@Test
	void geometryTheShadowPassDoesNotDrawHasNoRow() {
		for (String location : new String[] {"sky", "stars", "clouds", "weather", "world_border", "opaque_particle",
			"translucent_particle", "lines", "crumbling", "text", "text_see_through", "eyes_missing_location"}) {
			assertFalse(hasShadowRow(location, GbufferMapping.Domain.ENTITY), location);
		}
	}

	@Test
	void theBlobShadowGlintAndEyesCastNothing() {
		// Nit 25 (E5): the docs name entities and the beacon beam for shadow_entities; these three are overlays or decals.
		for (String location : new String[] {"entity_shadow", "eyes", "glint", "glint_special", "entity_solid_glint",
			"armor_cutout_no_cull_glint", "item_translucent_glint"}) {
			assertFalse(hasShadowRow(location, GbufferMapping.Domain.ENTITY), location);
			assertFalse(hasShadowRow(location, GbufferMapping.Domain.BLOCK_ENTITY), location);
		}
	}

	@Test
	void theGameRowsOfTheOverlaysAreUnchanged() {
		// Only the shadow kind drops them: the gbuffers and hand rows keep the overlays' own programs.
		assertTrue(GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.OPAQUE, "eyes").isPresent());
		assertTrue(GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.OPAQUE, "glint").isPresent());
	}

	@Test
	void gbuffersRowsAreUnchanged() {
		assertEquals(List.of("terrain_solid", "terrain", "textured_lit", "textured", "basic"),
			GbufferMapping.row(PassKind.GBUFFERS, GbufferPhase.OPAQUE, "solid_terrain", GbufferMapping.Domain.ENTITY,
				false).orElseThrow().chain());
	}
}
