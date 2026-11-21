// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/** The clouds directive of shaders.properties: only clouds=off turns the game's vanilla clouds off (plan E6, item 2). */
class CloudsDirectiveTest {

	private static ShaderProperties props(String text) {
		return ShaderProperties.from(PropertiesText.parse(text));
	}

	@Test
	void cloudsOffTurnsTheVanillaCloudsOff() {
		ShaderProperties s = props("clouds=off\n");
		assertEquals(Optional.of(ShaderProperties.Clouds.OFF), s.clouds());
		assertTrue(s.disablesVanillaClouds());
	}

	@Test
	void cloudsFastAndFancyLeaveTheVanillaCloudsToTheVideoSetting() {
		// The OptiFine doc gives the video setting the higher priority, so the pack's fast and fancy change nothing.
		assertFalse(props("clouds=fast\n").disablesVanillaClouds());
		assertFalse(props("clouds=fancy\n").disablesVanillaClouds());
	}

	@Test
	void anAbsentOrInvalidCloudsValueLeavesTheVanillaCloudsOn() {
		assertFalse(props("blend.final=off\n").disablesVanillaClouds());
		ShaderProperties bad = props("clouds=sometimes\n");
		assertEquals(Optional.empty(), bad.clouds());
		assertFalse(bad.disablesVanillaClouds());
		assertFalse(bad.problems().isEmpty(), "an invalid clouds value is reported");
	}
}
