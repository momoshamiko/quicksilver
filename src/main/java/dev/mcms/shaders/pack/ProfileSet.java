// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The profiles of a pack (shaders.properties, {@code profile.NAME=items}): each item sets an option, copies another profile
 * ({@code profile.OTHER}) or disables a program ({@code !program.NAME}, optionally {@code world-1/NAME}). The current
 * profile is the one whose settings all match the option values; no match means "Custom".
 */
public final class ProfileSet {
	public enum Kind { SET, INCLUDE, DISABLE_PROGRAM }

	/** One item: a setting (name and value), an included profile name, or a disabled program name. */
	public record Item(Kind kind, String name, String value) {
	}

	/** A profile as written. */
	public record Profile(String name, List<Item> items) {
		public Profile {
			items = List.copyOf(items);
		}
	}

	/**
	 * A profile with its inheritance applied.
	 *
	 * @param settings         option name to value, in the order the items set them
	 * @param disabledPrograms program names (possibly dimension-qualified) the profile disables
	 * @param problems         unknown options and profile names, cycles
	 */
	public record Resolved(String name, Map<String, String> settings, Set<String> disabledPrograms, List<String> problems) {
		public Resolved {
			settings = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(settings));
			disabledPrograms = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(disabledPrograms));
			problems = List.copyOf(problems);
		}
	}

	private final Map<String, Profile> profiles;
	private final OptionCatalog catalog;

	private ProfileSet(Map<String, Profile> profiles, OptionCatalog catalog) {
		this.profiles = profiles;
		this.catalog = catalog;
	}

	/** Parses the raw profile items of shaders.properties; the names keep their file order. */
	public static ProfileSet parse(Map<String, String> rawItems, OptionCatalog catalog) {
		Map<String, Profile> out = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : rawItems.entrySet()) {
			List<Item> items = new ArrayList<>();
			for (String token : e.getValue().strip().split("\\s+")) {
				if (token.isEmpty()) continue;
				items.add(item(token));
			}
			out.put(e.getKey(), new Profile(e.getKey(), items));
		}
		return new ProfileSet(out, catalog);
	}

	private static Item item(String token) {
		if (token.startsWith("!program.")) return new Item(Kind.DISABLE_PROGRAM, token.substring("!program.".length()), "");
		if (token.startsWith("profile.")) return new Item(Kind.INCLUDE, token.substring("profile.".length()), "");
		if (token.startsWith("!")) return new Item(Kind.SET, token.substring(1), "false");
		int colon = token.indexOf(':');
		int eq = token.indexOf('=');
		int cut = colon < 0 ? eq : (eq < 0 ? colon : Math.min(colon, eq));
		if (cut > 0) return new Item(Kind.SET, token.substring(0, cut), token.substring(cut + 1));
		return new Item(Kind.SET, token, "true");
	}

	public List<String> names() {
		return new ArrayList<>(profiles.keySet());
	}

	public Optional<Profile> get(String name) {
		return Optional.ofNullable(profiles.get(name));
	}

	/** The profile with its inheritance applied; the program disables of an included profile are kept. */
	public Resolved resolve(String name) {
		List<String> problems = new ArrayList<>();
		Map<String, String> settings = new LinkedHashMap<>();
		Set<String> disabled = new LinkedHashSet<>();
		Set<String> stack = new LinkedHashSet<>();
		apply(name, settings, disabled, stack, problems);
		return new Resolved(name, settings, disabled, problems);
	}

	private void apply(String name, Map<String, String> settings, Set<String> disabled, Set<String> stack, List<String> problems) {
		Profile p = profiles.get(name);
		if (p == null) {
			problems.add("profile \"" + name + "\" is not defined");
			return;
		}
		if (!stack.add(name)) {
			problems.add("profile \"" + name + "\" includes itself");
			return;
		}
		for (Item it : p.items()) {
			switch (it.kind()) {
				case INCLUDE -> apply(it.name(), settings, disabled, stack, problems);
				case DISABLE_PROGRAM -> disabled.add(it.name());
				case SET -> {
					if (catalog.get(it.name()).isEmpty()) {
						problems.add("profile \"" + name + "\" sets unknown option \"" + it.name() + "\"");
					} else {
						settings.put(it.name(), it.value());
					}
				}
			}
		}
		stack.remove(name);
	}

	/**
	 * The current profile: the one whose settings match the option values. When several match, the one with more settings
	 * wins, then the first in the file. Empty when none matches ("Custom").
	 */
	public Optional<Resolved> detect(OptionValues values) {
		Resolved best = null;
		for (String name : profiles.keySet()) {
			Resolved r = resolve(name);
			boolean match = true;
			for (Map.Entry<String, String> s : r.settings().entrySet()) {
				if (!sameValue(values.get(s.getKey()), s.getValue())) {
					match = false;
					break;
				}
			}
			if (match && (best == null || r.settings().size() > best.settings().size())) best = r;
		}
		return Optional.ofNullable(best);
	}

	private static boolean sameValue(String current, String wanted) {
		if (current == null) return false;
		if (current.equals(wanted)) return true;
		try {
			return Double.parseDouble(current) == Double.parseDouble(wanted);
		} catch (NumberFormatException ex) {
			return false;
		}
	}
}
