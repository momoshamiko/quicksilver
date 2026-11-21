// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.TreeSet;

/**
 * Writes a pack's option values file ({@code shaderpacks/<pack file name>.txt}, {@link OptionValues#fileFor}) in
 * {@code java.util.Properties} format: one {@code NAME=value} line per option whose value differs from its default, then the
 * file's names the pack does not declare ({@link OptionValues#unknown}), kept as they were: a pack update that drops an option
 * and a later one that brings it back, or another loader's names, must not lose the player's values. The public docs give no
 * header line and no key order; each group comes out sorted by name, with no header, so a file written here is stable under a
 * rewrite. A declared name without a default is not written.
 */
public final class OptionFile {
	private OptionFile() {
	}

	/** The file's text: the changed values sorted by name, then the unknown names sorted by name. */
	public static String render(Map<String, String> defaults, Map<String, String> chosen, Map<String, String> unknown) {
		StringBuilder out = new StringBuilder();
		for (String name : new TreeSet<>(chosen.keySet())) {
			String value = chosen.get(name);
			String fallback = defaults.get(name);
			if (value == null || fallback == null || fallback.equals(value)) continue;
			out.append(escape(name)).append('=').append(escape(value)).append('\n');
		}
		for (String name : new TreeSet<>(unknown.keySet())) {
			String value = unknown.get(name);
			if (value == null || chosen.containsKey(name) || defaults.containsKey(name)) continue;
			out.append(escape(name)).append('=').append(escape(value)).append('\n');
		}
		return out.toString();
	}

	/**
	 * Writes the file atomically: the text goes to a temporary file in the same folder, which then replaces the file with
	 * an atomic move. A failure leaves the old file as it was.
	 */
	public static void write(Path file, Map<String, String> defaults, Map<String, String> chosen, Map<String, String> unknown)
			throws IOException {
		Path dir = file.toAbsolutePath().getParent();
		Files.createDirectories(dir);
		Path tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
		try {
			try {
				// createTempFile makes the file readable by its owner only; the pack's options file is readable by everyone, as the
				// game's own files are (QuicksilverSettings.save does the same)
				Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-r--r--"));
			} catch (UnsupportedOperationException notPosix) {
				// a file system without POSIX modes keeps its own default
			}
			Files.writeString(tmp, render(defaults, chosen, unknown), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException fallback) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException | RuntimeException e) {
			try {
				Files.deleteIfExists(tmp);
			} catch (IOException cleanup) {
				e.addSuppressed(cleanup);
			}
			throw e;
		}
	}

	/** Escapes the characters {@code Properties.load} reads specially. */
	static String escape(String s) {
		StringBuilder b = new StringBuilder(s.length());
		for (char c : s.toCharArray()) {
			switch (c) {
				case '\\', '=', ':', '#', '!', ' ' -> b.append('\\').append(c);
				case '\n' -> b.append("\\n");
				case '\r' -> b.append("\\r");
				case '\t' -> b.append("\\t");
				default -> b.append(c);
			}
		}
		return b.toString();
	}
}
