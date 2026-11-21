// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.mcms.glsl.preprocess.LineRewriter;

/**
 * Applies chosen option values to the declaration lines of the shader files, before the preprocessor reads them
 * (engine-pack-format.md section 4). Only lines the discovery recorded are changed; an ambiguous option's lines are kept.
 *
 * <ul>
 * <li>boolean: {@code #define X //tip} becomes {@code // #define X //tip} when off, and back when on;</li>
 * <li>value: the value token after {@code #define X} is replaced;</li>
 * <li>const: the value between {@code =} and {@code ;} is replaced.</li>
 * </ul>
 */
public final class OptionRewriter implements LineRewriter {
	private record Site(String name, OptionKind kind) {
	}

	private static final Pattern BOOL_LINE = Pattern.compile("^(\\s*)(?://\\s*)?#\\s*define\\s+([A-Za-z_]\\w*)(.*)$");
	/** The value ends at white space or at a line comment glued to it ({@code #define X 0.5// [0.25 0.5]}, Bliss). */
	private static final Pattern VALUE_LINE = Pattern.compile(
		"^(\\s*#\\s*define\\s+[A-Za-z_]\\w*\\s+)((?:(?!//)\\S)+)(.*)$");
	private static final Pattern CONST_LINE = Pattern.compile("^(\\s*const\\s+\\w+\\s+[A-Za-z_]\\w*\\s*=\\s*)([^;]*)(;.*)$");

	private final Map<String, Site> sites = new HashMap<>();
	private final OptionValues values;

	public OptionRewriter(OptionCatalog catalog, OptionValues values) {
		this.values = values;
		for (PackOption o : catalog.options().values()) {
			if (o.ambiguous()) continue;
			for (PackOption.Declaration d : o.declarations()) {
				sites.put(d.path() + ":" + d.line(), new Site(o.name(), o.kind()));
			}
		}
	}

	@Override
	public String rewrite(String path, int line, String rawLine) {
		Site site = sites.get(path + ":" + line);
		if (site == null) return rawLine;
		String value = values.get(site.name());
		if (value == null) return rawLine;
		return switch (site.kind()) {
			case BOOLEAN -> rewriteBoolean(rawLine, site.name(), value);
			case VALUE -> {
				Matcher m = VALUE_LINE.matcher(rawLine);
				yield m.matches() ? m.group(1) + value + m.group(3) : rawLine;
			}
			case CONST -> {
				Matcher m = CONST_LINE.matcher(rawLine);
				yield m.matches() ? m.group(1) + value + m.group(3) : rawLine;
			}
		};
	}

	private static String rewriteBoolean(String rawLine, String name, String value) {
		Matcher m = BOOL_LINE.matcher(rawLine);
		if (!m.matches() || !m.group(2).equals(name)) return rawLine;
		String indent = m.group(1);
		String rest = m.group(3);
		return value.equals("true") ? indent + "#define " + name + rest : indent + "// #define " + name + rest;
	}
}
