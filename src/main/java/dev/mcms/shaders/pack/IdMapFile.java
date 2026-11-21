// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@code block.properties}, {@code item.properties} or {@code entity.properties} file (engine-pack-format.md section 6),
 * parsed to entries. Matching the entries to game objects is runtime work.
 *
 * @param kind    "block", "item" or "entity"
 * @param ids     the substitute id to the entries listed on its line (one line per id)
 * @param layers  block only: {@code layer.solid|cutout|translucent} to the entries (empty for other kinds)
 * @param problems duplicate ids, bad ids, entries the grammar rejects
 */
public record IdMapFile(String kind, Map<Integer, List<IdEntry>> ids, Map<String, List<IdEntry>> layers, List<String> problems) {
	public IdMapFile {
		ids = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(ids));
		layers = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(layers));
		problems = List.copyOf(problems);
	}

	/** Parses the entries of a preprocessed id-map file. */
	public static IdMapFile parse(String kind, PropertiesText.Parsed parsed) {
		List<String> problems = new ArrayList<>(parsed.problems());
		Map<Integer, List<IdEntry>> ids = new LinkedHashMap<>();
		Map<String, List<IdEntry>> layers = new LinkedHashMap<>();
		String prefix = kind + ".";
		for (PropertiesText.Entry e : parsed.entries()) {
			String key = e.key();
			if (kind.equals("block") && key.startsWith("layer.")) {
				String layer = key.substring("layer.".length());
				if (!layer.equals("solid") && !layer.equals("cutout") && !layer.equals("translucent")) {
					problems.add("line " + e.line() + ": unknown layer \"" + layer + "\"");
					continue;
				}
				layers.put(layer, entries(e.value(), problems, e.line()));
				continue;
			}
			if (!key.startsWith(prefix)) {
				problems.add("line " + e.line() + ": key \"" + key + "\" is not " + prefix + "<id>");
				continue;
			}
			int id;
			try {
				id = Integer.parseInt(key.substring(prefix.length()));
			} catch (NumberFormatException ex) {
				problems.add("line " + e.line() + ": id \"" + key.substring(prefix.length()) + "\" is not a number");
				continue;
			}
			if (ids.containsKey(id)) problems.add("line " + e.line() + ": " + kind + " id " + id + " has more than one line");
			ids.put(id, entries(e.value(), problems, e.line()));
		}
		return new IdMapFile(kind, ids, layers, problems);
	}

	private static final Pattern DEFINE = Pattern.compile("(?m)^\\s*#\\s*define\\s+([A-Za-z_]\\w*)");

	/**
	 * The names the file's raw text defines with {@code #define}, in any branch. {@link #unexpanded} needs them, because the
	 * preprocessed text no longer holds the {@code #define} lines.
	 */
	public static Set<String> definedNames(String rawText) {
		Set<String> out = new HashSet<>();
		Matcher m = DEFINE.matcher(rawText);
		while (m.find()) out.add(m.group(1));
		return out;
	}

	/**
	 * The distinct names a value still holds after expansion that the file defines: a macro name left in a value, because
	 * the macro is defined below its line or in an inactive branch. A bare name is an entry in its own right, so a name the
	 * file does not define is not reported.
	 */
	public static List<String> unexpanded(PropertiesText.Parsed parsed, Set<String> defined) {
		Set<String> out = new LinkedHashSet<>();
		for (PropertiesText.Entry e : parsed.entries()) {
			for (String token : e.value().strip().split("\\s+")) {
				if (defined.contains(token)) out.add(token);
			}
		}
		return List.copyOf(out);
	}

	private static List<IdEntry> entries(String value, List<String> problems, int line) {
		List<IdEntry> out = new ArrayList<>();
		for (String token : value.strip().split("\\s+")) {
			if (token.isEmpty()) continue;
			Optional<IdEntry> entry = IdEntry.parse(token);
			if (entry.isEmpty()) problems.add("line " + line + ": \"" + token + "\" is not a name");
			else out.add(entry.get());
		}
		return out;
	}
}
