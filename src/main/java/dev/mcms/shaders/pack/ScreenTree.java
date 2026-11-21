// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The option screens of a pack (shaders.properties, engine-pack-format.md section 4): {@code screen=items} is the main
 * screen, {@code screen.NAME=items} a sub-screen, {@code screen[.NAME].columns=N} the column count (default 2). Items are an
 * option, {@code [NAME]} (a link to a sub-screen), {@code <profile>} (the profile selector), {@code <empty>}, and {@code *}
 * (every option no screen names, in discovery order, excluding hidden consts).
 *
 * @param screens  by name; the main screen is named {@link #MAIN}
 * @param problems unknown option names, links to missing screens, cycles, a second {@code *}, bad column counts
 */
public record ScreenTree(Map<String, Screen> screens, List<String> problems) {
	/** The name of the main screen. */
	public static final String MAIN = "";

	public record Screen(String name, int columns, List<Item> items) {
		public Screen {
			items = List.copyOf(items);
		}
	}

	/** One slot of a screen. */
	public sealed interface Item permits OptionItem, LinkItem, ProfileSelector, EmptySlot {
	}

	public record OptionItem(String option) implements Item {
	}

	public record LinkItem(String screen) implements Item {
	}

	public record ProfileSelector() implements Item {
	}

	public record EmptySlot() implements Item {
	}

	public ScreenTree {
		screens = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(screens));
		problems = List.copyOf(problems);
	}

	/**
	 * Builds the tree from the raw {@code screen*} entries of shaders.properties.
	 *
	 * @param raw the entries whose keys are {@code screen}, {@code screen.NAME}, {@code screen.columns} or
	 *            {@code screen.NAME.columns}
	 */
	public static ScreenTree parse(Map<String, String> raw, OptionCatalog catalog) {
		List<String> problems = new ArrayList<>();
		Map<String, String> items = new LinkedHashMap<>();
		Map<String, Integer> columns = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : raw.entrySet()) {
			String k = e.getKey();
			if (k.equals("screen")) {
				items.put(MAIN, e.getValue());
			} else if (k.equals("screen.columns")) {
				columnCount(k, e.getValue(), MAIN, columns, problems);
			} else if (k.endsWith(".columns")) {
				columnCount(k, e.getValue(), k.substring("screen.".length(), k.length() - ".columns".length()), columns, problems);
			} else {
				items.put(k.substring("screen.".length()), e.getValue());
			}
		}
		Set<String> configured = new LinkedHashSet<>();
		Map<String, List<Item>> parsed = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : items.entrySet()) {
			List<Item> out = new ArrayList<>();
			for (String token : e.getValue().strip().split("\\s+")) {
				if (token.isEmpty()) continue;
				if (token.equals("*")) {
					out.add(null);
				} else if (token.startsWith("[") && token.endsWith("]")) {
					out.add(new LinkItem(token.substring(1, token.length() - 1)));
				} else if (token.equals("<profile>")) {
					out.add(new ProfileSelector());
				} else if (token.equals("<empty>")) {
					out.add(new EmptySlot());
				} else if (catalog.get(token).isEmpty()) {
					problems.add("screen \"" + e.getKey() + "\" names unknown option \"" + token + "\"");
				} else {
					out.add(new OptionItem(token));
					configured.add(token);
				}
			}
			parsed.put(e.getKey(), out);
		}
		List<String> rest = new ArrayList<>();
		for (var o : catalog.options().values()) {
			if (!configured.contains(o.name()) && !o.hiddenConst()) rest.add(o.name());
		}
		Map<String, Screen> screens = new LinkedHashMap<>();
		for (Map.Entry<String, List<Item>> e : parsed.entrySet()) {
			List<Item> out = new ArrayList<>();
			boolean seenRest = false;
			for (Item it : e.getValue()) {
				if (it != null) {
					out.add(it);
					continue;
				}
				if (seenRest) {
					problems.add("screen \"" + e.getKey() + "\" has more than one *");
					continue;
				}
				seenRest = true;
				for (String name : rest) out.add(new OptionItem(name));
			}
			screens.put(e.getKey(), new Screen(e.getKey(), columns.getOrDefault(e.getKey(), 2), out));
		}
		for (String name : columns.keySet()) {
			if (!screens.containsKey(name)) problems.add("columns given for screen \"" + name + "\", which is not defined");
		}
		checkLinks(screens, problems);
		return new ScreenTree(screens, problems);
	}

	private static void columnCount(String key, String value, String screen, Map<String, Integer> columns, List<String> problems) {
		try {
			columns.put(screen, Integer.parseInt(value.strip()));
		} catch (NumberFormatException ex) {
			problems.add(key + "=" + value + ": expected a whole number");
		}
	}

	/** Every link names a defined screen, and no screen links back into itself. */
	private static void checkLinks(Map<String, Screen> screens, List<String> problems) {
		Set<String> reached = new LinkedHashSet<>();
		walk(MAIN, screens, reached, new LinkedHashSet<>(), problems);
		for (String name : screens.keySet()) {
			if (!reached.contains(name)) problems.add("screen \"" + name + "\" is not linked from the main screen");
		}
	}

	private static void walk(String name, Map<String, Screen> screens, Set<String> reached, Set<String> stack,
		List<String> problems) {
		reached.add(name);
		Screen s = screens.get(name);
		if (s == null) return;
		if (!stack.add(name)) {
			problems.add("screen \"" + name + "\" links into itself");
			return;
		}
		for (Item it : s.items()) {
			if (it instanceof LinkItem link) {
				if (!screens.containsKey(link.screen())) {
					problems.add("screen \"" + name + "\" links to missing screen \"" + link.screen() + "\"");
				} else {
					walk(link.screen(), screens, reached, stack, problems);
				}
			}
		}
		stack.remove(name);
	}
}
