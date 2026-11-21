// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.util.Optional;

/**
 * The labels a settings screen shows for a pack's options, with the documented fallbacks (shaders.properties, lang files):
 * a name without a label shows the option's own name; a comment without one falls back to the comment written in the
 * shader source (the option's tooltip); a value without a label shows its own text; prefix and suffix are empty; a screen
 * without a title shows its name; a profile without a label shows its name.
 *
 * <p>Each key is read from the wanted language first and then from the fallback language (en_us), key by key.
 */
public final class OptionLabels {
	private final Lang wanted;
	private final Lang fallback;

	/**
	 * @param wanted   the game's language file (may be empty)
	 * @param fallback the en_us file, read for every key the wanted file lacks
	 */
	public OptionLabels(Lang wanted, Lang fallback) {
		this.wanted = wanted;
		this.fallback = fallback;
	}

	private Optional<String> lookup(String key) {
		Optional<String> v = wanted.get(key);
		return v.isPresent() ? v : fallback.get(key);
	}

	/** The option's name in the screen: {@code option.X}, else X. */
	public String optionName(String option) {
		return lookup("option." + option).orElse(option);
	}

	/** The option's tooltip: {@code option.X.comment}, else the comment in the shader source, else empty. */
	public String optionComment(PackOption option) {
		Optional<String> label = lookup("option." + option.name() + ".comment");
		if (label.isPresent()) return label.get();
		return option.tooltip() == null ? "" : option.tooltip();
	}

	/** Whether the pack defines an explicit {@code value.X.v} label for this option value. */
	public boolean hasValueLabel(String option, String value) {
		return lookup("value." + option + "." + value).isPresent();
	}

	/** A value's label: {@code value.X.v}, else the value text. */
	public String valueLabel(String option, String value) {
		return lookup("value." + option + "." + value).orElse(value);
	}

	/** The prefix written before the value: {@code prefix.X}, else empty. */
	public String prefix(String option) {
		return lookup("prefix." + option).orElse("");
	}

	/** The suffix written after the value: {@code suffix.X}, else empty. */
	public String suffix(String option) {
		return lookup("suffix." + option).orElse("");
	}

	/** The text a value button shows: the prefix, the value's label and the suffix. */
	public String displayValue(String option, String value) {
		return prefix(option) + valueLabel(option, value) + suffix(option);
	}

	/** A sub-screen's title: {@code screen.NAME}, else NAME. */
	public String screenTitle(String screen) {
		return lookup("screen." + screen).orElse(screen);
	}

	/** A sub-screen's tooltip: {@code screen.NAME.comment}, else empty. */
	public String screenComment(String screen) {
		return lookup("screen." + screen + ".comment").orElse("");
	}

	/** A profile's name in the profile button: {@code profile.NAME}, else NAME. */
	public String profileName(String profile) {
		return lookup("profile." + profile).orElse(profile);
	}

	/** The profile button's tooltip: {@code profile.comment}, else empty. */
	public String profileComment() {
		return lookup("profile.comment").orElse("");
	}
}
