// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The chosen value of every option of a pack: the defaults, changed by an option values file
 * ({@code shaderpacks/<pack file name>.txt}, {@code java.util.Properties} format) or by a profile.
 *
 * @param values  every catalog option with its value text ("true"/"false" for booleans)
 * @param unknown names in the values file that the pack does not declare: kept and reported
 * @param problems values outside a list, not a boolean, or for an ambiguous option: reported and not applied
 */
public record OptionValues(Map<String, String> values, Map<String, String> unknown, List<String> problems) {
	public OptionValues {
		values = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
		unknown = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(unknown));
		problems = List.copyOf(problems);
	}

	/** The values file of a pack: {@code <shaderpacks folder>/<pack file name>.txt}. */
	public static Path fileFor(Path shaderpacks, String packFileName) {
		return shaderpacks.resolve(packFileName + ".txt");
	}

	/** Reads a values file in {@code java.util.Properties} format; empty properties when the file does not exist. */
	public static Properties readFile(Path file) throws IOException {
		Properties p = new Properties();
		if (!Files.isRegularFile(file)) return p;
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			p.load(r);
		}
		return p;
	}

	/** The defaults of every option. */
	public static OptionValues defaults(OptionCatalog catalog) {
		Map<String, String> v = new LinkedHashMap<>();
		for (PackOption o : catalog.options().values()) v.put(o.name(), o.defaultValue());
		return new OptionValues(v, Map.of(), List.of());
	}

	/** The defaults, changed by the values file's entries. */
	public static OptionValues of(OptionCatalog catalog, Properties file) {
		OptionValues base = defaults(catalog);
		Map<String, String> changes = new LinkedHashMap<>();
		for (String key : new java.util.TreeSet<>(file.stringPropertyNames())) changes.put(key, file.getProperty(key).strip());
		return base.with(catalog, changes);
	}

	/** The option's value text, or null for a name the pack does not declare. */
	public String get(String name) {
		return values.get(name);
	}

	/** Whether a boolean option is on. */
	public boolean isOn(String name) {
		return "true".equals(values.get(name));
	}

	/** Applies changes; each is validated against the catalog and the rejects are reported, the others apply. */
	public OptionValues with(OptionCatalog catalog, Map<String, String> changes) {
		Map<String, String> v = new LinkedHashMap<>(values);
		Map<String, String> unk = new LinkedHashMap<>(unknown);
		List<String> probs = new ArrayList<>(problems);
		for (Map.Entry<String, String> e : changes.entrySet()) {
			String name = e.getKey();
			String value = e.getValue();
			var option = catalog.get(name);
			if (option.isEmpty()) {
				unk.put(name, value);
				probs.add("unknown option \"" + name + "\" kept, not used by the pack");
				continue;
			}
			PackOption o = option.get();
			if (o.ambiguous()) {
				probs.add("option \"" + name + "\" is ambiguous in the pack and cannot be changed");
				continue;
			}
			String problem = check(o, value);
			if (problem != null) {
				probs.add("option \"" + name + "\" value \"" + value + "\": " + problem);
				continue;
			}
			v.put(name, value);
		}
		return new OptionValues(v, unk, probs);
	}

	/** A reason the value is not allowed, or null when it is. */
	static String check(PackOption o, String value) {
		if (o.kind() == OptionKind.BOOLEAN) {
			return value.equals("true") || value.equals("false") ? null : "a boolean must be true or false";
		}
		if (o.hasValueList()) {
			return o.values().contains(value) ? null : "not in the list " + o.values();
		}
		return null;
	}
}
