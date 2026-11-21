// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One name in an id-map line (OptiFine doc properties_files.txt, "Blocks"): {@code <namespace:>name<:prop=v1,v2:...>}.
 * The namespace defaults to {@code minecraft}. A part after the name is a state only when it contains {@code =}, so
 * {@code minecraft:oak_stairs:facing=east,west} has namespace {@code minecraft}, name {@code oak_stairs} and one property.
 *
 * @param namespace the namespace
 * @param name      the object name
 * @param state     the property names and their allowed values (empty when the name has no state)
 */
public record IdEntry(String namespace, String name, Map<String, List<String>> state) {
	public IdEntry {
		state = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(state));
	}

	public static Optional<IdEntry> parse(String token) {
		String[] parts = token.split(":", -1);
		String namespace = "minecraft";
		String name;
		int first;
		if (parts.length >= 2 && !parts[1].contains("=")) {
			namespace = parts[0];
			name = parts[1];
			first = 2;
		} else {
			name = parts[0];
			first = 1;
		}
		if (namespace.isEmpty() || name.isEmpty()) return Optional.empty();
		Map<String, List<String>> state = new LinkedHashMap<>();
		for (int i = first; i < parts.length; i++) {
			int eq = parts[i].indexOf('=');
			if (eq <= 0 || eq == parts[i].length() - 1) return Optional.empty();
			List<String> values = new ArrayList<>();
			for (String v : parts[i].substring(eq + 1).split(",", -1)) {
				if (v.isEmpty()) return Optional.empty();
				values.add(v);
			}
			state.put(parts[i].substring(0, eq), values);
		}
		return Optional.of(new IdEntry(namespace, name, state));
	}
}
