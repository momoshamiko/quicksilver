// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the key=value lines of a preprocessed {@code .properties} text (engine-pack-format.md section 5): a backslash at the
 * end of a line continues it, blank lines are skipped, keys and values are trimmed, and the first {@code =} splits. The
 * preprocessor has already removed comments and inactive lines (properties mode).
 */
public final class PropertiesText {
	/** One key=value line in file order, with its 1-based line number. */
	public record Entry(String key, String value, int line) {
	}

	/** The entries in file order, and the lines that had no {@code =}. */
	public record Parsed(List<Entry> entries, List<String> problems) {
		/** The last value of each key (later keys win), in first-seen key order. */
		public Map<String, String> lastWins() {
			Map<String, String> m = new LinkedHashMap<>();
			for (Entry e : entries) m.put(e.key(), e.value());
			return m;
		}
	}

	private PropertiesText() {
	}

	public static Parsed parse(String text) {
		List<Entry> out = new ArrayList<>();
		List<String> problems = new ArrayList<>();
		String[] lines = text == null ? new String[0] : text.replace("\r\n", "\n").split("\n", -1);
		StringBuilder pending = null;
		int pendingLine = 0;
		for (int i = 0; i < lines.length; i++) {
			String raw = lines[i].strip();
			if (pending == null) {
				if (raw.isEmpty()) continue;
				pendingLine = i + 1;
				pending = new StringBuilder();
			} else {
				raw = raw.stripLeading();
			}
			if (raw.endsWith("\\")) {
				pending.append(raw, 0, raw.length() - 1);
				continue;
			}
			pending.append(raw);
			String logical = pending.toString();
			pending = null;
			int eq = logical.indexOf('=');
			if (eq < 0) {
				problems.add("line " + pendingLine + ": no '=' in \"" + logical + "\"");
				continue;
			}
			String key = logical.substring(0, eq).strip();
			String value = logical.substring(eq + 1).strip();
			if (key.isEmpty()) {
				problems.add("line " + pendingLine + ": empty key");
				continue;
			}
			out.add(new Entry(key, value, pendingLine));
		}
		if (pending != null) problems.add("line " + pendingLine + ": continuation at end of file");
		return new Parsed(out, problems);
	}
}
