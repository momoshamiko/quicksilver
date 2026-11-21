// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.entities;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mcms.shaders.pack.IdEntry;
import dev.mcms.shaders.pack.IdMapFile;
import dev.mcms.shaders.pack.PropertiesText;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The id matching of entity.properties, block.properties and item.properties (engine-pack-format.md section 6; the
 * Iris docs of the three files and the id uniforms): namespaces default to {@code minecraft}, block state properties
 * match value lists, and the uniforms' documented unlisted (65535) and absent (0) values. Properties files hold one
 * {@code key=value} a line; a value lists many names space-separated.
 */
class EntityIdsTest {

	private static PropertiesText.Parsed parsed(String... lines) {
		int line = 1;
		List<PropertiesText.Entry> entries = new ArrayList<>();
		for (String text : lines) {
			String[] parts = text.split("=", 2);
			entries.add(new PropertiesText.Entry(parts[0], parts[1], line++));
		}
		return new PropertiesText.Parsed(entries, List.of());
	}

	private static IdMapFile map(String... lines) {
		String kind = lines[0];
		String[] rest = new String[lines.length - 1];
		System.arraycopy(lines, 1, rest, 0, rest.length);
		return IdMapFile.parse(kind, parsed(rest));
	}

	@Test
	void entityShortNameIsMinecraftAndLongNameKeepsItsNamespace() {
		EntityIds ids = EntityIds.of(map("entity", "entity.2000=sheep botania:pixie"),
			map("block"), map("item"));
		assertEquals(2000, ids.entity("minecraft", "sheep"));
		assertEquals(2000, ids.entity("botania", "pixie"));
		assertEquals(EntityIds.UNLISTED, ids.entity("minecraft", "cow"));
		assertEquals(EntityIds.NO_FILE, ids.defaultItem());
	}

	@Test
	void blockPropertiesMatchValueListsAndUnlistedPropertiesAreWildcards() {
		EntityIds ids = EntityIds.of(map("entity"), map("block",
			"block.33=minecraft:oak_door:facing=north,south:hinge=right", "block.34=stone"),
			map("item"));
		assertEquals(33, ids.blockEntity("minecraft", "oak_door", Map.of("facing", "north", "hinge", "right")));
		assertEquals(33, ids.blockEntity("minecraft", "oak_door",
			Map.of("facing", "south", "hinge", "right", "powered", "true")));
		assertEquals(EntityIds.UNLISTED, ids.blockEntity("minecraft", "oak_door", Map.of("facing", "east")));
		assertEquals(EntityIds.UNLISTED, ids.blockEntity("minecraft", "oak_door", Map.of("facing", "north",
			"hinge", "left")));
		assertEquals(34, ids.blockEntity("minecraft", "stone", Map.of()));
		assertEquals(34, ids.blockEntity("minecraft", "stone", null));
	}

	@Test
	void firstLineWinsWhenTwoEntriesOverlap() {
		EntityIds ids = EntityIds.of(map("entity"), map("block", "block.5=oak_door", "block.6=oak_door"),
			map("item"));
		assertEquals(5, ids.blockEntity("minecraft", "oak_door", Map.of()));
	}

	@Test
	void absentFilesReadZero() {
		EntityIds ids = EntityIds.of(map("entity"), map("block"), map("item"));
		assertEquals(EntityIds.NO_FILE, ids.entity("minecraft", "sheep"));
		assertEquals(EntityIds.NO_FILE, ids.blockEntity("minecraft", "chest", Map.of()));
		assertEquals(EntityIds.NO_FILE, ids.item("minecraft", "diamond_sword"));
	}

	@Test
	void itemsMatchByNamespace() {
		EntityIds ids = EntityIds.of(map("entity"), map("block"),
			map("item", "item.5001=diamond_sword botania:reeds"));
		assertEquals(5001, ids.item("minecraft", "diamond_sword"));
		assertEquals(5001, ids.item("botania", "reeds"));
		assertEquals(EntityIds.UNLISTED, ids.item("minecraft", "stick"));
	}

	@Test
	void entryParsingFollowsTheGrammar() {
		List<IdEntry> entries = IdMapFile.parse("block",
			parsed("block.7=minecraft:red_flower:type=white_tulip")).ids().get(7);
		assertEquals(1, entries.size());
		assertEquals("red_flower", entries.get(0).name());
		assertEquals("minecraft", entries.get(0).namespace());
		assertEquals(List.of("white_tulip"), entries.get(0).state().get("type"));
	}
}
