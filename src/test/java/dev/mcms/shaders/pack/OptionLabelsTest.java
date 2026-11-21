// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** The label lookups with their fallbacks: the game's language first, then en_us, then the pack's own text or the name. */
class OptionLabelsTest {
	private static final Lang WANTED = new Lang("de_de", Optional.of("lang/de_DE.lang"), Map.of(
		"option.LEVEL", "Stufe",
		"value.LEVEL.3", "drei"));

	private static final Lang FALLBACK = new Lang("en_us", Optional.of("lang/en_US.lang"), Map.of(
		"option.LEVEL", "Level",
		"option.LEVEL.comment", "Fallback comment",
		"value.LEVEL.1", "one",
		"value.LEVEL.3", "three",
		"prefix.LEVEL", "(",
		"suffix.LEVEL", ")",
		"screen.ADV", "Advanced",
		"profile.HIGH", "High",
		"profile.comment", "Profiles"));

	private static final OptionLabels LABELS = new OptionLabels(WANTED, FALLBACK);

	private static PackOption option(String name, String tooltip) {
		return new PackOption(name, OptionKind.VALUE, "2", List.of("1", "2", "3"), true, "", tooltip, List.of(), false);
	}

	@Test
	void theGameLanguageWinsAndEachKeyFallsBackToEnglish() {
		assertEquals("Stufe", LABELS.optionName("LEVEL"));
		assertEquals("drei", LABELS.valueLabel("LEVEL", "3"));
		assertEquals("one", LABELS.valueLabel("LEVEL", "1"), "a value the game language lacks falls back to en_us");
	}

	@Test
	void anUnlabelledNameShowsItsOwnNameAndAnUnlabelledValueItsText() {
		assertEquals("MODE", LABELS.optionName("MODE"));
		assertEquals("2", LABELS.valueLabel("LEVEL", "2"));
	}

	@Test
	void commentFallsBackToTheShaderSourceCommentAndThenToEmpty() {
		assertEquals("Fallback comment", LABELS.optionComment(option("LEVEL", "source")));
		assertEquals("source", LABELS.optionComment(option("MODE", "source")));
		assertEquals("", LABELS.optionComment(option("MODE", "")));
	}

	@Test
	void prefixAndSuffixWrapTheValueAndAreEmptyWhenMissing() {
		assertEquals("(one)", LABELS.displayValue("LEVEL", "1"));
		assertEquals("2", LABELS.displayValue("MODE", "2"));
	}

	@Test
	void screenTitlesFallBackToTheScreenName() {
		assertEquals("Advanced", LABELS.screenTitle("ADV"));
		assertEquals("OTHER", LABELS.screenTitle("OTHER"));
		assertEquals("", LABELS.screenComment("ADV"));
	}

	@Test
	void profileNamesAndCommentFallBackToTheProfileName() {
		assertEquals("High", LABELS.profileName("HIGH"));
		assertEquals("LOW", LABELS.profileName("LOW"));
		assertEquals("Profiles", LABELS.profileComment());
	}
}
