// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mcms.shaders.pack.IdMapFile;
import dev.mcms.shaders.pack.PropertiesText;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The {@code block.properties} matching against names with namespaces and state predicates (plan.md E4's test list;
 * the grammar of the OptiFine doc's properties_files.txt): a short name, a namespaced name, a name with one property
 * of several values, a name with two properties, and the cases that must not match — the wrong namespace, the wrong
 * property value, and a property the state does not have.
 */
class BlockIdMatcherTest {

	private static IdMapFile map(String text) {
		return IdMapFile.parse("block", PropertiesText.parse(text));
	}

	@Test
	void shortAndNamespacedNamesMatchEveryStateOfTheBlock() {
		BlockIdMatcher matcher = BlockIdMatcher.of(map("""
			block.3=stone
			block.7=minecraft:water
			"""));
		assertEquals(Optional.of(3), matcher.id("minecraft", "stone", Map.of("facing", "north")));
		assertEquals(Optional.of(7), matcher.id("minecraft", "water", Map.of("level", "0")));
		assertEquals(Optional.empty(), matcher.id("somemod", "stone", Map.of()));
	}

	@Test
	void statePredicatesMatchOnlyTheListedValues() {
		BlockIdMatcher matcher = BlockIdMatcher.of(map("""
			block.1=minecraft:oak_stairs:facing=east,west
			block.2=minecraft:oak_stairs:facing=north:half=top
			block.4=red_flower:type=white_tulip
			"""));
		assertEquals(Optional.of(1), matcher.id("minecraft", "oak_stairs", Map.of("facing", "east", "half", "bottom")));
		assertEquals(Optional.of(1), matcher.id("minecraft", "oak_stairs", Map.of("facing", "west")));
		assertEquals(Optional.empty(), matcher.id("minecraft", "oak_stairs", Map.of("facing", "south")));
		// Two properties: every named property must hold one of its values.
		assertEquals(Optional.of(2), matcher.id("minecraft", "oak_stairs", Map.of("facing", "north", "half", "top")));
		assertEquals(Optional.empty(), matcher.id("minecraft", "oak_stairs", Map.of("facing", "north", "half", "bottom")));
		// A property the state does not have fails the predicate.
		assertEquals(Optional.of(4), matcher.id("minecraft", "red_flower", Map.of("type", "white_tulip")));
		assertEquals(Optional.empty(), matcher.id("minecraft", "red_flower", Map.of()));
	}

	@Test
	void theFirstLineOfTheFileWinsWhenLinesOverlap() {
		BlockIdMatcher matcher = BlockIdMatcher.of(map("""
			block.5=minecraft:oak_stairs:facing=east,west
			block.6=minecraft:oak_stairs
			"""));
		assertEquals(Optional.of(5), matcher.id("minecraft", "oak_stairs", Map.of("facing", "east")));
		assertEquals(Optional.of(6), matcher.id("minecraft", "oak_stairs", Map.of("facing", "north")));
	}

	@Test
	void blocksThePackListsNowhereGetNoId() {
		BlockIdMatcher matcher = BlockIdMatcher.of(map("block.1=stone\n"));
		assertEquals(Optional.empty(), matcher.id("minecraft", "dirt", Map.of()));
	}
}
