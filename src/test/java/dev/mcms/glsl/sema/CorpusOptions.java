// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import dev.mcms.glsl.preprocess.LineRewriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies a pack's options file the way the game does: {@code NAME=VALUE} sets the {@code #define NAME} line of the
 * pack's sources. {@code true} uncomments the define, {@code false} comments it out, and any other value replaces the
 * define's value and keeps its trailing comment. Options that name no define in the sources change nothing.
 */
final class CorpusOptions {

	private static final Pattern DEFINE =
			Pattern.compile("^(\\s*)(//\\s*)?#\\s*define\\s+([A-Za-z_][A-Za-z0-9_]*)(\\s.*)?$");

	private CorpusOptions() {
	}

	/** Parses the OptiFine options-file text (blank lines and {@code #} lines are skipped). */
	static Map<String, String> parse(String text) {
		Map<String, String> options = new LinkedHashMap<>();
		for (String raw : text.split("\\R")) {
			String line = raw.trim();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			int eq = line.indexOf('=');
			if (eq > 0) {
				options.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
			}
		}
		return options;
	}

	/** The rewriter that applies the options to every physical line, keeping the line count. */
	static LineRewriter rewriter(Map<String, String> options) {
		return (path, line, raw) -> {
			Matcher m = DEFINE.matcher(raw);
			if (!m.matches()) {
				return raw;
			}
			String name = m.group(3);
			String value = options.get(name);
			if (value == null) {
				return raw;
			}
			String indent = m.group(1);
			String rest = m.group(4) == null ? "" : m.group(4);
			if (value.equals("true")) {
				return indent + "#define " + name + rest;
			}
			if (value.equals("false")) {
				return indent + "//#define " + name + rest;
			}
			// A value replaces the define's default: the first token of the rest, unless the rest is only a comment.
			Matcher v = Pattern.compile("^\\s+(\\S+)(.*)$").matcher(rest);
			String after = v.matches() && !v.group(1).startsWith("//") ? v.group(2) : rest;
			return indent + "#define " + name + " " + value + after;
		};
	}
}
