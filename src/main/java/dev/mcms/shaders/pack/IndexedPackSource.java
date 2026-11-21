// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared index over a pack's entries, used by the zip and folder implementations. Paths are normalized, {@code /}
 * separated, relative to the pack root. Lookups try the exact name first, then fall back to a case-insensitive match
 * (packs are often made on Windows) and record a warning once per requested path. Lookups and the warning list are
 * synchronized: the runtime may read a pack from a background thread while the UI lists it.
 */
abstract class IndexedPackSource implements PackSource {
	private final String name;
	private final Map<String, Boolean> entries = new HashMap<>();
	private final Map<String, TreeSet<String>> byLowerCase = new TreeMap<>();
	private final String shadersPrefix;
	private final List<String> warnings = new ArrayList<>();
	private final Set<String> warnedPaths = new HashSet<>();
	/**
	 * Each text read, by the path asked for (E9, load time): a pack's includes are read once per program that includes them.
	 * A source lives as long as one read of the pack (Engine, the options screen), so the texts are the files of that read.
	 */
	private final Map<String, Optional<String>> texts = new ConcurrentHashMap<>();

	IndexedPackSource(String name, Map<String, Boolean> rawEntries) throws IOException {
		this.name = name;
		for (Map.Entry<String, Boolean> e : rawEntries.entrySet()) {
			String p = e.getKey();
			entries.put(p, e.getValue());
			byLowerCase.computeIfAbsent(p.toLowerCase(Locale.ROOT), k -> new TreeSet<>()).add(p);
			// Parent folders exist even when the zip has no entries for them.
			int slash = p.lastIndexOf('/');
			while (slash >= 0) {
				String dir = p.substring(0, slash);
				entries.putIfAbsent(dir, Boolean.TRUE);
				byLowerCase.computeIfAbsent(dir.toLowerCase(Locale.ROOT), k -> new TreeSet<>()).add(dir);
				slash = dir.lastIndexOf('/');
			}
		}
		this.shadersPrefix = findShadersRoot();
	}

	/** Reads the raw bytes of an entry given its pack-root-relative path; null when it has no data. */
	protected abstract byte[] readEntry(String packPath) throws IOException;

	@Override
	public final String name() {
		return name;
	}

	@Override
	public final Optional<String> readText(String path) {
		Optional<String> cached = texts.get(path);
		if (cached != null) {
			return cached;
		}
		Optional<byte[]> b = readBytes(path);
		Optional<String> text;
		if (b.isEmpty()) {
			text = Optional.empty();
		} else {
			String s = new String(b.get(), StandardCharsets.UTF_8);
			if (!s.isEmpty() && s.codePointAt(0) == 0xFEFF) s = s.substring(1);
			text = Optional.of(s.replace("\r\n", "\n").replace('\r', '\n'));
		}
		texts.put(path, text);
		return text;
	}

	@Override
	public final Optional<byte[]> readBytes(String path) {
		String packPath = locate(PackPaths.normalize(path), false, true);
		if (packPath == null) return Optional.empty();
		try {
			return Optional.ofNullable(readEntry(packPath));
		} catch (IOException e) {
			return Optional.empty();
		}
	}

	@Override
	public final boolean exists(String path) {
		return locate(PackPaths.normalize(path), true, false) != null;
	}

	@Override
	public final List<String> list(String folder) {
		String f = PackPaths.normalize(folder);
		String prefix;
		if (f.isEmpty()) {
			prefix = shadersPrefix;
		} else {
			// The located path, so a folder found by ignoring case lists its real children.
			String located = locate(f, true, true);
			if (located == null) return List.of();
			prefix = located + "/";
		}
		TreeSet<String> children = new TreeSet<>();
		for (Map.Entry<String, Boolean> e : entries.entrySet()) {
			String p = e.getKey();
			if (!p.startsWith(prefix) || p.equals(prefix)) continue;
			String rest = p.substring(prefix.length());
			int slash = rest.indexOf('/');
			children.add(slash < 0 ? (e.getValue() ? rest + "/" : rest) : rest.substring(0, slash + 1));
		}
		return List.copyOf(children);
	}

	@Override
	public synchronized final List<String> warnings() {
		return List.copyOf(warnings);
	}

	@Override
	public void close() throws IOException {
	}

	// The entries map is written only during construction; lookups read it without the lock.

	/**
	 * Finds a path under the shaders root. With dirsAllowed both files and folders match; with warn a case-only match is
	 * reported once. Returns the pack-root-relative path, or null.
	 */
	private synchronized String locate(String path, boolean dirsAllowed, boolean warn) {
		if (path.isEmpty()) return dirsAllowed ? shadersPrefix : null;
		String packPath = shadersPrefix + path;
		Boolean isDir = entries.get(packPath);
		if (isDir != null && (dirsAllowed || !isDir)) return packPath;
		TreeSet<String> candidates = byLowerCase.get(packPath.toLowerCase(Locale.ROOT));
		if (candidates == null) return null;
		for (String c : candidates) {
			Boolean d = entries.get(c);
			if (d == null || (!dirsAllowed && d)) continue;
			if (warn && warnedPaths.add(path)) {
				warnings.add("path '" + path + "' found only by ignoring case as '" + c.substring(shadersPrefix.length()) + "'");
			}
			return c;
		}
		return null;
	}

	private String findShadersRoot() throws IOException {
		Boolean root = entries.get("shaders");
		if (root != null && root) return "shaders/";
		TreeSet<String> caseless = new TreeSet<>();
		for (Map.Entry<String, Boolean> e : entries.entrySet()) {
			if (!e.getValue() || e.getKey().contains("/")) continue;
			if (e.getKey().equalsIgnoreCase("shaders")) caseless.add(e.getKey());
		}
		if (caseless.size() == 1) return caseless.first() + "/";
		if (caseless.size() > 1) throw new IOException("ambiguous shaders/ folders in " + name + ": " + caseless);
		TreeSet<String> wrappers = new TreeSet<>();
		TreeSet<String> wrappersCaseless = new TreeSet<>();
		for (Map.Entry<String, Boolean> e : entries.entrySet()) {
			if (!e.getValue()) continue;
			String p = e.getKey();
			int slash = p.indexOf('/');
			if (slash < 0) continue;
			String top = p.substring(0, slash);
			String child = p.substring(slash + 1);
			if (child.equals("shaders")) wrappers.add(top + "/shaders");
			else if (child.equalsIgnoreCase("shaders")) wrappersCaseless.add(top + "/" + child);
		}
		if (wrappers.size() == 1) return wrappers.first() + "/";
		if (wrappers.size() > 1) throw new IOException("shaders/ found in more than one top-level folder of " + name + ": " + wrappers);
		if (wrappersCaseless.size() == 1) return wrappersCaseless.first() + "/";
		if (wrappersCaseless.size() > 1) throw new IOException("shaders/ found in more than one top-level folder of " + name + ": " + wrappersCaseless);
		throw new IOException("no shaders/ folder found in " + name);
	}
}
