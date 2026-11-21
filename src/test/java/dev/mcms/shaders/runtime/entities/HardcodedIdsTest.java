// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.entities;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mcms.shaders.pack.IdMapFile;
import dev.mcms.shaders.pack.PropertiesText;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The hardcoded ids Iris defines (entity.properties "Hardcoded IDs": name tags, shadows, fire, converting zombie
 * villagers, the current player, end crystal beams; item.properties "Hardcoded IDs": trims, capes) and the held-item
 * values of the ID uniforms page. A pack names them like any other id, so they match by namespace; the held values
 * follow the documented 0 (no file) and -1 (file present, item not listed or hand empty) rules.
 */
class HardcodedIdsTest {

	private static PropertiesText.Parsed parsed(String... lines) {
		int line = 1;
		List<PropertiesText.Entry> entries = new ArrayList<>();
		for (String text : lines) {
			String[] parts = text.split("=", 2);
			entries.add(new PropertiesText.Entry(parts[0], parts[1], line++));
		}
		return new PropertiesText.Parsed(entries, List.of());
	}

	private static IdMapFile map(String kind, String... lines) {
		return IdMapFile.parse(kind, parsed(lines));
	}

	@Test
	void entityHardcodedNamesMatchTheirIds() {
		EntityIds ids = EntityIds.of(map("entity",
			"entity.1=minecraft:name_tag",
			"entity.2=minecraft:entity_shadow minecraft:entity_flame",
			"entity.3=minecraft:zombie_villager_converting",
			"entity.4=minecraft:current_player",
			"entity.5=minecraft:end_crystal_beam"), map("block"), map("item"));
		assertEquals(1, ids.entity("minecraft", "name_tag"));
		assertEquals(2, ids.entity("minecraft", "entity_shadow"));
		assertEquals(2, ids.entity("minecraft", "entity_flame"));
		assertEquals(3, ids.entity("minecraft", "zombie_villager_converting"));
		assertEquals(4, ids.entity("minecraft", "current_player"));
		assertEquals(5, ids.entity("minecraft", "end_crystal_beam"));
	}

	@Test
	void aFileThatListsOnlyOtherEntitiesLeavesTheHardcodedNamesUnlisted() {
		EntityIds ids = EntityIds.of(map("entity", "entity.9=minecraft:sheep"), map("block"), map("item"));
		assertEquals(EntityIds.UNLISTED, ids.entity("minecraft", "name_tag"));
		assertEquals(EntityIds.UNLISTED, ids.entity("minecraft", "end_crystal_beam"));
	}

	@Test
	void noEntityFileReadsZeroForHardcodedNames() {
		EntityIds ids = EntityIds.of(map("entity"), map("block"), map("item"));
		assertEquals(EntityIds.NO_FILE, ids.entity("minecraft", "current_player"));
	}

	@Test
	void itemHardcodedIdsMatchTrimMaterialsAndCapes() {
		EntityIds ids = EntityIds.of(map("entity"), map("block"), map("item",
			"item.10=minecraft:trim_emerald", "item.11=minecraft:player_cape", "item.12=minecraft:elytra_with_cape"));
		assertEquals(10, ids.item("minecraft", "trim_emerald"));
		assertEquals(11, ids.item("minecraft", "player_cape"));
		assertEquals(12, ids.item("minecraft", "elytra_with_cape"));
		assertEquals(EntityIds.UNLISTED, ids.item("minecraft", "trim_quartz"));
	}

	@Test
	void heldItemValuesFollowTheIrisRules() {
		EntityIds withFile = EntityIds.of(map("entity"), map("block"), map("item", "item.7=minecraft:diamond_sword"));
		assertEquals(7, EntityIds.held(withFile.item("minecraft", "diamond_sword")));
		assertEquals(-1, EntityIds.held(withFile.item("minecraft", "stick")));
		assertEquals(-1, withFile.heldEmpty());
		EntityIds withoutFile = EntityIds.of(map("entity"), map("block"), map("item"));
		assertEquals(0, EntityIds.held(withoutFile.item("minecraft", "stick")));
		assertEquals(0, withoutFile.heldEmpty());
	}
}
