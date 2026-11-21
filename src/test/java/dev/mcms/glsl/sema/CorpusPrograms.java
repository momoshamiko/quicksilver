// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import dev.mcms.glsl.preprocess.LineRewriter;
import dev.mcms.glsl.preprocess.Mode;
import dev.mcms.glsl.preprocess.PreprocessException;
import dev.mcms.glsl.preprocess.Preprocessor;
import dev.mcms.glsl.preprocess.SourceText;
import dev.mcms.shaders.pack.PackSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which programs the game runs for a pack at a configuration. Iris runs a file only when its name is a program name
 * and the pack's {@code program.<dir>/<name>.enabled} expression is true (shaders.properties, "Programs / Ordering").
 * A name the game does not know (for example Colorwheel's {@code clrwl_*} files) is not run: the Vitrail run logs say
 * "unrecognised program names 4: clrwl_gbuffers x6, ...".
 *
 * <p>The enabled expression names options. An option is on when the pack defines its macro: the defines are read
 * from every source file of the pack after the options are applied, so the defaults and the bench options both take
 * effect.
 */
final class CorpusPrograms {

	/**
	 * Iris program names: the gbuffer names of the OptiFine list (shaders.txt, "Shader Programs") plus the ones the
	 * packs' shaders.properties and the Vitrail run logs name; then the shadow, composite, deferred, prepare, final,
	 * setup and begin passes; then the Distant Horizons programs. The logs name {@code clrwl_*}, {@code gbuffers_voxels}
	 * and {@code shadow_voxels} "unrecognised program names", so they are not here.
	 */
	private static final Pattern PROGRAM_NAME = Pattern.compile(
			"gbuffers_(basic|line|textured|textured_lit|skybasic|skytextured|clouds|terrain|terrain_solid"
					+ "|terrain_cutout|damagedblock|block|beaconbeam|item|entities|entities_glowing|armor_glint"
					+ "|spidereyes|hand|weather|water|hand_water|block_translucent|entities_translucent|particles"
					+ "|particles_translucent|lightning)"
					+ "|(shadow|shadowcomp|prepare|deferred|composite|final|setup|begin)\\d*(_[a-z])?"
					+ "|dh_(terrain|water|shadow)");

	private static final Pattern PROPERTY =
			Pattern.compile("^\\s*program\\.([^=\\s]+)\\.enabled\\s*=\\s*(.*?)\\s*$");

	private static final Pattern DEFINED =
			Pattern.compile("^\\s*#\\s*define\\s+([A-Za-z_][A-Za-z0-9_]*)(?:\\s+(\\S+))?");

	private final Map<String, String> enabledExpressions;
	private final Set<String> defined;
	private final boolean distantHorizons;

	private CorpusPrograms(Map<String, String> enabledExpressions, Set<String> defined, boolean distantHorizons) {
		this.enabledExpressions = enabledExpressions;
		this.defined = defined;
		this.distantHorizons = distantHorizons;
	}

	/** A program file of the pack: the folder it is in ("" at the root), its base name, and its file name. */
	record ProgramFile(String dir, String base, String fileName) {
		String path() {
			return dir + fileName;
		}
	}

	/** Reads the pack's enablement properties and its defines at the given options. */
	static CorpusPrograms of(PackSource source, LineRewriter options, Map<String, String> macros) {
		// Each name with its first value: "#define NAME" is 1, "#define NAME 0" is 0 (the properties conditionals compare
		// integer options, so the value matters). The game's macros come first.
		Map<String, String> predefined = new TreeMap<>(macros);
		for (String file : sourceFiles(source, "")) {
			String text = source.readText(file).orElse("");
			String[] lines = text.split("\\R", -1);
			for (int i = 0; i < lines.length; i++) {
				String raw = options == null ? lines[i] : options.rewrite(file, i + 1, lines[i]);
				if (raw == null) {
					continue;
				}
				Matcher m = DEFINED.matcher(raw);
				if (m.find()) {
					String value = m.group(2) == null || m.group(2).startsWith("//") ? "1" : m.group(2);
					predefined.putIfAbsent(m.group(1), value);
				}
			}
		}
		Set<String> defined = new HashSet<>(predefined.keySet());
		// shaders.properties is read in properties mode: its #if and #ifdef blocks see the pack's defines and the
		// game's macros, as Iris reads them. Every other line of that mode is a comment.
		Map<String, String> expressions = new LinkedHashMap<>();
		Optional<String> properties = source.readText("shaders.properties");
		if (properties.isPresent()) {
			String evaluated;
			try {
				evaluated = new Preprocessor(Mode.PROPERTIES, source.includeResolver(), predefined, null)
						.process(new SourceText("shaders.properties", properties.get())).text();
			} catch (PreprocessException e) {
				throw new IllegalStateException("shaders.properties: " + e.diagnostics(), e);
			}
			for (String line : evaluated.split("\\R")) {
				Matcher m = PROPERTY.matcher(line);
				if (m.matches()) {
					String expr = m.group(2).replaceAll("#.*$", "").trim();
					expressions.put(m.group(1), expr);
				}
			}
		}
		// Distant Horizons programs run only when the mod is loaded, which is when the game defines DISTANT_HORIZONS
		// (the DH page: "All programs and properties have the DISTANT_HORIZONS define set if DH is enabled"). The
		// bench's non-DH runs draw no dh_ pass; the DH run draws them ("Drawing the distant far terrain pass").
		return new CorpusPrograms(expressions, defined, macros.containsKey("DISTANT_HORIZONS"));
	}

	/** Every file of the pack's shaders folder, recursively, as paths from the folder's root. */
	static List<String> sourceFiles(PackSource source, String prefix) {
		List<String> out = new ArrayList<>();
		for (String entry : source.list(prefix)) {
			if (entry.endsWith("/")) {
				out.addAll(sourceFiles(source, prefix + entry));
			} else {
				String ext = entry.substring(entry.lastIndexOf('.') + 1).toLowerCase();
				if (ext.equals("glsl") || ext.equals("fsh") || ext.equals("vsh") || ext.equals("csh")
						|| ext.equals("gsh") || ext.equals("inc")) {
					out.add(prefix + entry);
				}
			}
		}
		return out;
	}

	/** The program files of the folder at {@code dir}, or null when the file is not a program of the game. */
	static ProgramFile programFile(String path) {
		int slash = path.lastIndexOf('/');
		String dir = slash < 0 ? "" : path.substring(0, slash + 1);
		String name = slash < 0 ? path : path.substring(slash + 1);
		int dot = name.lastIndexOf('.');
		if (dot < 0) {
			return null;
		}
		String base = name.substring(0, dot);
		String ext = name.substring(dot + 1);
		if (!ext.equals("vsh") && !ext.equals("fsh") && !ext.equals("csh") && !ext.equals("gsh")) {
			return null;
		}
		return PROGRAM_NAME.matcher(base).matches() ? new ProgramFile(dir, base, name) : null;
	}

	/** The enabled expression that applies to a program, or null when the pack gives none. */
	String expressionFor(ProgramFile p) {
		String key = p.dir().isEmpty() ? p.base() : p.dir().substring(0, p.dir().length() - 1) + "/" + p.base();
		if (enabledExpressions.containsKey(key)) {
			return enabledExpressions.get(key);
		}
		return enabledExpressions.get(p.base());
	}

	/** True when the program runs at this configuration. */
	boolean enabled(ProgramFile p) {
		if (p.base().startsWith("dh_") && !distantHorizons) {
			return false;
		}
		String expr = expressionFor(p);
		return expr == null || Boolean.TRUE.equals(new BoolExpr(expr, defined).evaluate());
	}

	/** Evaluates the shaders.properties boolean expressions: names, true, false, !, &amp;&amp;, ||, parentheses. */
	static final class BoolExpr {
		private final List<String> tokens = new ArrayList<>();
		private final Set<String> defined;
		private int at;

		BoolExpr(String text, Set<String> defined) {
			this.defined = defined;
			Matcher m = Pattern.compile("\\s*(?:(&&|\\|\\||!|\\(|\\)|[A-Za-z_][A-Za-z0-9_]*)|(\\S))").matcher(text);
			while (m.lookingAt()) {
				if (m.group(2) != null) {
					throw new IllegalArgumentException("bad token '" + m.group(2) + "' in " + text);
				}
				tokens.add(m.group(1));
				m.region(m.end(), text.length());
			}
		}

		Boolean evaluate() {
			boolean v = or();
			if (at != tokens.size()) {
				throw new IllegalArgumentException("trailing tokens in shaders.properties expression");
			}
			return v;
		}

		private boolean or() {
			boolean v = and();
			while (at < tokens.size() && tokens.get(at).equals("||")) {
				at++;
				v = and() || v;
			}
			return v;
		}

		private boolean and() {
			boolean v = not();
			while (at < tokens.size() && tokens.get(at).equals("&&")) {
				at++;
				v = not() && v;
			}
			return v;
		}

		private boolean not() {
			if (at < tokens.size() && tokens.get(at).equals("!")) {
				at++;
				return !not();
			}
			if (at >= tokens.size()) {
				throw new IllegalArgumentException("expression ends early");
			}
			String t = tokens.get(at++);
			if (t.equals("(")) {
				boolean v = or();
				if (at >= tokens.size() || !tokens.get(at++).equals(")")) {
					throw new IllegalArgumentException("missing ')'");
				}
				return v;
			}
			if (t.equals("true")) {
				return true;
			}
			if (t.equals("false")) {
				return false;
			}
			return defined.contains(t);
		}
	}
}
