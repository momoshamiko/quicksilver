// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Lexical path handling for paths inside a pack's {@code shaders/} root, with {@code /} separators. */
public final class PackPaths {
	private PackPaths() {
	}

	/**
	 * Cleans a path to {@code /}-separated form: empty segments and {@code .} segments are dropped, {@code ..} pops the
	 * previous segment. Leading {@code /} is stripped, so the result never starts with {@code /}. {@code ..} segments that
	 * climb above the root stay at the front of the result ({@code a/../../x} becomes {@code ../x}); use
	 * {@link #resolve} to reject those.
	 */
	public static String normalize(String path) {
		if (path == null || path.isEmpty()) return "";
		path = path.replace('\\', '/');
		List<String> out = new ArrayList<>();
		int i = 0;
		int n = path.length();
		while (i < n) {
			int slash = path.indexOf('/', i);
			int end = slash < 0 ? n : slash;
			String seg = path.substring(i, end);
			if (!seg.isEmpty() && !seg.equals(".")) {
				if (seg.equals("..")) {
					if (!out.isEmpty() && !out.get(out.size() - 1).equals("..")) out.remove(out.size() - 1);
					else out.add("..");
				} else {
					out.add(seg);
				}
			}
			i = end + 1;
		}
		return String.join("/", out);
	}

	/**
	 * Resolves an include as the preprocessor sees it. {@code "/x"} is absolute from the {@code shaders/} root;
	 * {@code "x"} is relative to {@code fromFile}'s folder (the root when {@code fromFile} has no folder). Returns empty
	 * when the result would leave the root or name the root itself.
	 */
	public static Optional<String> resolve(String fromFile, String include) {
		if (include == null || include.isEmpty()) return Optional.empty();
		String base = include.startsWith("/") ? include : folderOf(fromFile == null ? "" : fromFile) + "/" + include;
		String p = normalize(base);
		if (p.isEmpty() || p.equals("..") || p.startsWith("../")) return Optional.empty();
		return Optional.of(p);
	}

	private static String folderOf(String file) {
		int slash = file.lastIndexOf('/');
		return slash < 0 ? "" : file.substring(0, slash);
	}
}
