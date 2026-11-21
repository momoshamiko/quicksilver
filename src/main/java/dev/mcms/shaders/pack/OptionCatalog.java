// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every option one pack declares, in discovery order.
 *
 * @param options  the options by name
 * @param warnings the ambiguity and reading warnings
 * @param files    the shader files the discovery read, in the order it read them
 */
public record OptionCatalog(Map<String, PackOption> options, List<String> warnings, List<String> files) {
	public OptionCatalog {
		options = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(options));
		warnings = List.copyOf(warnings);
		files = List.copyOf(files);
	}

	public Optional<PackOption> get(String name) {
		return Optional.ofNullable(options.get(name));
	}

	public List<PackOption> list() {
		return new ArrayList<>(options.values());
	}

	/** Option counts by kind; every kind appears, even with zero. */
	public Map<OptionKind, Integer> countByKind() {
		Map<OptionKind, Integer> out = new EnumMap<>(OptionKind.class);
		for (OptionKind k : OptionKind.values()) out.put(k, 0);
		for (PackOption o : options.values()) out.merge(o.kind(), 1, Integer::sum);
		return out;
	}
}
