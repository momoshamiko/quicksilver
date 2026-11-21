// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.textures;

import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * A pack's texture path that names no valid resource (upper case, spaces) is no resource: the read answers null without
 * reaching the game, so one pack typo never crashes the frame that loads the set (review item 10).
 */
final class GameResourcesTest {

	@Test
	void aPathWithUpperCaseOrSpacesIsNoResourceAndDoesNotThrow() {
		assertNull(GameResources.read("minecraft:textures/Bad Path.png"));
		assertNull(GameResources.read("minecraft:UPPER/Case.png"));
	}
}
