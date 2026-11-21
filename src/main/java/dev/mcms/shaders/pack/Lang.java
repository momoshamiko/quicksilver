// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;

/**
 * The labels of one language file ({@code shaders/lang/<id>.lang}, engine-pack-format.md section 4): {@code option.X},
 * {@code option.X.comment}, {@code value.X.v}, {@code prefix.X}, {@code suffix.X}, {@code screen.NAME}, {@code screen.NAME.comment},
 * {@code profile.NAME}, {@code profile.comment}. File names match without regard to case; the {@code en_us} file is the
 * fallback when the wanted language has none.
 *
 * @param id      the language id that was asked for
 * @param file    the file read, or empty when the pack has none
 * @param entries the keys and values
 */
public record Lang(String id, Optional<String> file, Map<String, String> entries) {
	public static final String FALLBACK = "en_us";

	public Lang {
		entries = java.util.Collections.unmodifiableMap(new TreeMap<>(entries));
	}

	/** The label for a key, or empty when the file does not give one. */
	public Optional<String> get(String key) {
		return Optional.ofNullable(entries.get(key));
	}

	/** Reads the wanted language, or {@link #FALLBACK}; an empty result when the pack has neither. */
	public static Lang select(PackSource pack, String wanted) {
		String want = wanted.toLowerCase(java.util.Locale.ROOT);
		String fallbackFile = null;
		String wantedFile = null;
		for (String entry : pack.list("lang")) {
			if (entry.endsWith("/") || !entry.toLowerCase(java.util.Locale.ROOT).endsWith(".lang")) continue;
			String id = entry.substring(0, entry.length() - ".lang".length()).toLowerCase(java.util.Locale.ROOT);
			if (id.equals(want)) wantedFile = entry;
			if (id.equals(FALLBACK)) fallbackFile = entry;
		}
		String chosen = wantedFile != null ? wantedFile : fallbackFile;
		if (chosen == null) return new Lang(wanted, Optional.empty(), Map.of());
		Optional<String> text = pack.readText("lang/" + chosen);
		if (text.isEmpty()) return new Lang(wanted, Optional.empty(), Map.of());
		Properties p = new Properties();
		try {
			p.load(new StringReader(text.get()));
		} catch (java.io.IOException | IllegalArgumentException e) {
			return new Lang(wanted, Optional.empty(), Map.of());
		}
		Map<String, String> entries = new TreeMap<>();
		for (String k : new ArrayList<>(p.stringPropertyNames())) entries.put(k, p.getProperty(k));
		return new Lang(wanted, Optional.of("lang/" + chosen), entries);
	}
}
