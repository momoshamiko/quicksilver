// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.preprocess;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * A C-style preprocessor for GLSL pack sources ({@link Mode#GLSL}) and the conditional directives of OptiFine/Iris
 * {@code .properties} files ({@link Mode#PROPERTIES}), written from the GLSL specification's preprocessing chapter
 * (C99 where GLSL is silent) and OptiFine's documented pack behavior (docs/research/engine-pack-format.md §3, §5).
 *
 * <p>GLSL mode splices backslash continuations and block comments that run over several lines into one logical line,
 * keeps comments verbatim in active output, expands object-like and function-like macros with C rescanning rules (a
 * macro is never expanded inside its own expansion), evaluates {@code #if} constant expressions with {@code defined},
 * follows {@code #include} through the resolver (nesting depth at most 10), records the root's {@code #version} and
 * every active {@code #extension}, and returns a line map from output lines to the physical file lines they came from.
 * Inactive regions produce no output; their directives, except the conditional family, are ignored. Kept lines are
 * {@code #version}, {@code #extension} and {@code #pragma}.
 *
 * <p>Properties mode evaluates the conditional family with the predefined macros. {@code #define} and {@code #undef}
 * change the macros in active regions for the conditionals that follow, so a pack can set a macro in its own file
 * (Complementary's "Special Define Handling"). The text is not expanded, except the values of the keys that the caller
 * names (the value of {@code size.buffer.colortex1 = REFLECTION_RES REFLECTION_RES} takes the macros defined at its own
 * line), and the directive lines are not output.
 * {@code #include} is ignored with a warning. Every other line starting with {@code #} is a comment ({@code # if} with a
 * space is a comment). Backslash line endings are kept as they are, except in the value of an expanded key, which joins its
 * continued lines before it is expanded.
 *
 * <p>One instance can run {@link #process} repeatedly; runs are independent.
 */
public final class Preprocessor {
	/** OptiFine's documented include nesting limit (engine-pack-format.md §3). */
	private static final int MAX_INCLUDE_DEPTH = 10;
	/** Expansion steps allowed for one logical line (a runaway recursive macro stops here). */
	private static final long MAX_EXPANSION_STEPS = 2_000_000;
	/** GLSL's multi-character operators, longest first; used for lexing and to keep output tokens apart. */
	private static final String[] OPS = {"##", "<<=", ">>=", "++", "--", "<<", ">>", "<=", ">=", "==", "!=", "&&", "||", "&=", "^=", "|=", "+=", "-=", "*=", "/=", "%="};
	/** {@link #OPS} by first character (ASCII), in OPS's order; null where no operator starts (E9, load time: a pack read
	 * shreds every line of every include, and trying each operator at each punctuation character was a tenth of it). */
	private static final String[][] OPS_BY_FIRST = new String[128][];
	/** The one-character strings of ASCII, so a punctuation token takes no substring. */
	private static final String[] ONE_CHAR = new String[128];

	static {
		for (char c = 0; c < 128; c++) {
			ONE_CHAR[c] = String.valueOf(c);
			List<String> starting = new ArrayList<>();
			for (String o : OPS) {
				if (o.charAt(0) == c) {
					starting.add(o);
				}
			}
			OPS_BY_FIRST[c] = starting.isEmpty() ? null : starting.toArray(new String[0]);
		}
	}
	private static final Set<String> DYNAMIC_BUILTINS = Set.of("__LINE__", "__FILE__", "__VERSION__", "GL_compatibility_profile", "GL_core_profile");
	private static final Set<String> CONDITIONAL_WORDS = Set.of("if", "ifdef", "ifndef", "elif", "else", "endif");
	/** The empty operand that {@code ##} pastes through when an argument is empty (C99 6.10.3.3). */
	private static final Tok PLACEMARKER = Tok.code(Kind.PUNCT, "", "", 0, -1);

	private final Mode mode;
	private final IncludeResolver includes;
	private final Map<String, String> predefined;
	private final LineRewriter rewriter;
	private final Predicate<String> expandedKeys;
	private final @Nullable FileCache fileCache;

	/**
	 * The logical lines of the files one pack load preprocesses (E9, load time): a pack's includes are preprocessed once
	 * per program that includes them, and normalizing, splitting, rewriting and splicing their text again each time was a
	 * fifth of a pack's read. One cache serves one load, whose files and line rewriter do not change during it: a path
	 * names one text for the cache's life, and a source without a path is not cached. GLSL mode only. Not thread-safe (a
	 * load runs on one thread).
	 */
	public static final class FileCache {
		/** By line rewriter (null: none), then by path. */
		private final Map<LineRewriter, Map<String, List<Logical>>> byRewriter = new IdentityHashMap<>();
	}

	public Preprocessor(Mode mode, IncludeResolver includes, Map<String, String> predefined, LineRewriter rewriter) {
		this(mode, includes, predefined, rewriter, key -> false);
	}

	/**
	 * @param expandedKeys properties mode: the keys whose values are expanded through the macros defined at their line
	 *                     (the values are otherwise kept as written); ignored in GLSL mode
	 */
	public Preprocessor(Mode mode, IncludeResolver includes, Map<String, String> predefined, LineRewriter rewriter,
			Predicate<String> expandedKeys) {
		this(mode, includes, predefined, rewriter, expandedKeys, null);
	}

	/** The same, with the load's file cache (null: none). */
	public Preprocessor(Mode mode, IncludeResolver includes, Map<String, String> predefined, LineRewriter rewriter,
			Predicate<String> expandedKeys, @Nullable FileCache fileCache) {
		this.mode = Objects.requireNonNull(mode, "mode");
		this.includes = includes;
		this.predefined = predefined == null ? Map.of() : Map.copyOf(predefined);
		this.rewriter = rewriter;
		this.expandedKeys = Objects.requireNonNull(expandedKeys, "expandedKeys");
		this.fileCache = fileCache;
	}

	public PreprocessResult process(SourceText root) throws PreprocessException {
		Run run = new Run();
		try {
			run.processFile(root == null ? new SourceText("", "") : root, true);
		} catch (Fail f) {
			throw new PreprocessException(f.diagnostic);
		}
		return run.result();
	}

	private static final class Fail extends RuntimeException {
		final Diagnostic diagnostic;

		Fail(Diagnostic d) {
			super(d.toString());
			diagnostic = d;
		}
	}

	private enum Kind {
		ID, NUM, PUNCT, STR, COMMENT
	}

	private record Macro(String name, boolean functionLike, List<String> params, List<Tok> body) {
	}

	private record Seg(int line, String text) {
	}

	/** A value in a {@code #if} expression: integer or floating (pack macros such as MC_RENDER_QUALITY are floats). */
	private record Val(long i, double f, boolean flt) {
		static Val of(long v) {
			return new Val(v, 0, false);
		}

		static Val ofF(double v) {
			return new Val(0, v, true);
		}

		boolean truthy() {
			return flt ? f != 0.0 : i != 0;
		}
	}

	/** One logical line: physical segments joined after line splicing; {@code starts[k]} is segment k's offset. */
	private static final class Logical {
		final int line1;
		final List<Seg> segs;
		final String spliced;
		final int[] starts;
		final int[] lines;

		Logical(int line1, List<Seg> segs) {
			this.line1 = line1;
			this.segs = segs;
			StringBuilder sb = new StringBuilder();
			starts = new int[segs.size()];
			lines = new int[segs.size()];
			for (int i = 0; i < segs.size(); i++) {
				starts[i] = sb.length();
				lines[i] = segs.get(i).line();
				sb.append(segs.get(i).text());
			}
			spliced = sb.toString();
		}

		int lineAt(int offset) {
			for (int i = starts.length - 1; i >= 0; i--) {
				if (offset >= starts[i]) return lines[i];
			}
			return line1;
		}
	}

	private static final class Cond {
		final String kind;
		final String file;
		final int startLine;
		final boolean parentActive;
		boolean taken;
		boolean sawElse;
		boolean active;

		Cond(String kind, String file, int startLine, boolean parentActive, boolean value) {
			this.kind = kind;
			this.file = file;
			this.startLine = startLine;
			this.parentActive = parentActive;
			this.active = parentActive && value;
			this.taken = active;
		}
	}

	/** The logical lines after the current one, for a macro invocation that continues on the next line. */
	private interface Feed {
		/** Tokens of the logical line {@code ahead} lines after the current one, not consumed; null at end of file. */
		List<Tok> peekAt(int ahead);

		/** Consumes the given number of logical lines. */
		void consume(int lines);
	}

	/** A preprocessing token. Comment tokens carry their physical parts (text joined with LF). */
	private static final class Tok {
		final Kind kind;
		final String text;
		final String ws;
		/** Forced leading space left behind by a comment that was removed while parsing a directive. */
		final String gap;
		final Set<String> hides;
		final int line;
		final int start;
		final int[] commentLines;
		final int[] commentStarts;

		Tok(Kind kind, String text, String ws, String gap, Set<String> hides, int line, int start, int[] commentLines, int[] commentStarts) {
			this.kind = kind;
			this.text = text;
			this.ws = ws;
			this.gap = gap;
			this.hides = hides;
			this.line = line;
			this.start = start;
			this.commentLines = commentLines;
			this.commentStarts = commentStarts;
		}

		static Tok code(Kind kind, String text, String ws, int line, int start) {
			return new Tok(kind, text, ws, "", null, line, start, null, null);
		}

		boolean isPunct(String p) {
			return kind == Kind.PUNCT && text.equals(p);
		}

		Tok withGap(String g) {
			return new Tok(kind, text, ws, g, hides, line, start, commentLines, commentStarts);
		}

		Tok withWs(String w) {
			return new Tok(kind, text, w, gap, hides, line, start, commentLines, commentStarts);
		}

		Tok withWsIfEmpty(String w) {
			return ws.isEmpty() ? withWs(w) : this;
		}

		Tok withHides(Set<String> h) {
			return new Tok(kind, text, ws, gap, h, line, start, commentLines, commentStarts);
		}

		Tok withLine(int l) {
			return new Tok(kind, text, ws, gap, hides, l, start, commentLines, commentStarts);
		}
	}

	private final class Run {
		final List<String> out = new ArrayList<>();
		final List<LineOrigin> map = new ArrayList<>();
		final List<Diagnostic> warnings = new ArrayList<>();
		final Map<String, Macro> macros = new HashMap<>();
		final Set<String> suppressed = new HashSet<>();
		final LinkedHashSet<String> fileDefined = new LinkedHashSet<>();
		final Map<String, Integer> fileIds = new HashMap<>();
		final LinkedHashSet<String> files = new LinkedHashSet<>();
		final ArrayDeque<Cond> conds = new ArrayDeque<>();
		final List<String> extensions = new ArrayList<>();
		String versionArg;
		int versionNumber = 110;
		int nextFileId;
		String curPath = "";
		int curFileId;
		int includeDepth;
		int lineDelta;
		long steps;
		Logical curLogical = new Logical(1, List.of(new Seg(1, "")));
		/** Properties mode: the last kept line of an expanded value ends with a backslash, so the next kept line continues it. */
		boolean valueOpen;

		Run() {
			for (Map.Entry<String, String> e : predefined.entrySet()) {
				macros.put(e.getKey(), parsePredefined(e.getKey(), e.getValue()));
			}
		}

		Macro parsePredefined(String name, String body) {
			Logical l = new Logical(1, List.of(new Seg(1, body)));
			List<Tok> sig = significant(shred(l), 0);
			if (!sig.isEmpty()) sig.set(0, sig.get(0).withWs(""));
			return new Macro(name, false, List.of(), sig);
		}

		// ---- file and line loop ----------------------------------------------------------

		void processFile(SourceText src, boolean isRoot) {
			String path = src.path() == null ? "" : src.path();
			Integer id = fileIds.get(path);
			if (id == null) {
				id = nextFileId++;
				fileIds.put(path, id);
			}
			files.add(path);
			String prevPath = curPath;
			int prevId = curFileId;
			int prevDelta = lineDelta;
			Logical prevLogical = curLogical;
			curPath = path;
			curFileId = id;
			lineDelta = 0;
			curLogical = new Logical(1, List.of(new Seg(1, "")));
			int condBase = conds.size();
			if (mode == Mode.PROPERTIES) {
				List<String> lines = rewrittenLines(src, path);
				int[] ppos = {0};
				while (ppos[0] < lines.size()) propertiesLine(path, lines, ppos);
			} else {
				List<Logical> logicals;
				if (fileCache == null || path.isEmpty()) {
					logicals = splice(rewrittenLines(src, path));
				} else {
					Map<String, List<Logical>> byPath = fileCache.byRewriter.computeIfAbsent(rewriter, r -> new HashMap<>());
					logicals = byPath.get(path);
					if (logicals == null) {
						logicals = splice(rewrittenLines(src, path));
						byPath.put(path, logicals);
					}
				}
				int[] pos = {0};
				while (pos[0] < logicals.size()) {
					Logical l = logicals.get(pos[0]++);
					glslLine(l, logicals, pos, isRoot);
				}
			}
			if (conds.size() > condBase) {
				Cond c = conds.peek(); // the innermost open conditional
				error(c.file, c.startLine, "unterminated conditional #" + c.kind + " (started here)");
			}
			curPath = prevPath;
			curFileId = prevId;
			lineDelta = prevDelta;
			curLogical = prevLogical;
		}

		/** A file's lines: its text without a byte order mark, with LF line ends, through the line rewriter. */
		private List<String> rewrittenLines(SourceText src, String path) {
			String text = src.text() == null ? "" : src.text();
			if (!text.isEmpty() && text.codePointAt(0) == 0xFEFF) text = text.substring(1);
			text = text.replace("\r\n", "\n").replace('\r', '\n');
			List<String> lines = splitLines(text);
			if (rewriter != null) {
				for (int i = 0; i < lines.size(); i++) {
					String r = rewriter.rewrite(path, i + 1, lines.get(i));
					lines.set(i, r == null ? "" : r);
				}
			}
			return lines;
		}

		void glslLine(Logical l, List<Logical> all, int[] pos, boolean isRoot) {
			curLogical = l;
			steps = 0;
			boolean active = activeNow();
			// E9, load time: a skipped line that holds no '#' is no directive, and with no string or block comment its
			// shredding reports nothing, so it is not shredded (most lines of a pack's includes sit in skipped groups).
			if (!active && l.spliced.indexOf('#') < 0 && l.spliced.indexOf('"') < 0 && l.spliced.indexOf("/*") < 0) {
				return;
			}
			List<Tok> toks = shred(l);
			int d = directiveIndex(toks);
			if (d >= 0) {
				if (active) {
					// A comment that starts before the '#' belongs to the text; one after it belongs to the directive.
					List<Tok> before = new ArrayList<>();
					for (int k = 0; k < d; k++) {
						if (toks.get(k).kind == Kind.COMMENT) before.add(toks.get(k));
					}
					if (!before.isEmpty()) renderExpanded(before, l);
				}
				directive(l, toks, d, active, isRoot);
			} else if (active) {
				activeLine(l, toks, all, pos);
			}
		}

		// ---- tokens --------------------------------------------------------------------

		/** Turns one logical line into tokens. Comments are tokens; a block comment never crosses a logical line. */
		List<Tok> shred(Logical l) {
			String s = l.spliced;
			int n = s.length();
			List<Tok> toks = new ArrayList<>();
			int i = 0;
			String ws = "";
			while (i < n) {
				char c = s.charAt(i);
				if (isWs(c)) {
					int w0 = i;
					while (i < n && isWs(s.charAt(i))) i++;
					ws = ws.isEmpty() ? s.substring(w0, i) : ws + s.substring(w0, i);
					continue;
				}
				if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
					toks.add(commentTok(s, i, n, l, ws, false));
					i = n;
					ws = "";
					continue;
				}
				if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
					int end0 = s.indexOf("*/", i + 2);
					if (end0 < 0) error(curPath, l.lineAt(i), "unterminated comment");
					int end = end0 + 2;
					toks.add(commentTok(s, i, end, l, ws, true));
					i = end;
					ws = "";
					continue;
				}
				if (c == '"') {
					int j = i + 1;
					boolean closed = false;
					while (j < n) {
						char d = s.charAt(j);
						if (d == '\\') {
							j += 2;
							continue;
						}
						if (d == '"') {
							j++;
							closed = true;
							break;
						}
						j++;
					}
					int e = Math.min(j, n);
					toks.add(Tok.code(Kind.STR, s.substring(i, e), ws, l.lineAt(i), i));
					if (!closed) warn(l, "unterminated string literal");
					i = e;
					ws = "";
					continue;
				}
				if (isIdStart(c)) {
					int j = i + 1;
					while (j < n && isIdChar(s.charAt(j))) j++;
					toks.add(Tok.code(Kind.ID, s.substring(i, j), ws, l.line1, i));
					i = j;
					ws = "";
					continue;
				}
				if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
					// pp-number: in a hexadecimal constant 'e' and 'p' are digits, so a sign never continues it
					boolean hex = c == '0' && i + 2 < n && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X');
					int j = i + 1;
					while (j < n) {
						char d = s.charAt(j);
						if (isIdChar(d) || d == '.') {
							j++;
						} else if (!hex && (d == '+' || d == '-') && "eEpP".indexOf(s.charAt(j - 1)) >= 0) {
							j++;
						} else {
							break;
						}
					}
					toks.add(Tok.code(Kind.NUM, s.substring(i, j), ws, l.line1, i));
					i = j;
					ws = "";
					continue;
				}
				String op = null;
				String[] candidates = c < 128 ? OPS_BY_FIRST[c] : null;
				if (candidates != null) {
					for (String o : candidates) {
						if (s.startsWith(o, i)) {
							op = o;
							break;
						}
					}
				}
				if (op != null) {
					toks.add(Tok.code(Kind.PUNCT, op, ws, l.lineAt(i), i));
					i += op.length();
				} else {
					toks.add(Tok.code(Kind.PUNCT, c < 128 ? ONE_CHAR[c] : s.substring(i, i + 1), ws, l.lineAt(i), i));
					i++;
				}
				ws = "";
			}
			return toks;
		}

		/**
		 * Builds a comment token. A block comment splits into one part per physical line it covers; a {@code //} comment
		 * is one part that runs to the end of the logical line (a backslash before the end continues it, as in C).
		 */
		private Tok commentTok(String s, int from, int to, Logical l, String ws, boolean splitParts) {
			if (!splitParts) {
				return new Tok(Kind.COMMENT, s.substring(from, to), ws, "", null, l.lineAt(from), from,
						new int[] {l.lineAt(from)}, new int[] {from});
			}
			List<String> parts = new ArrayList<>();
			List<Integer> partLines = new ArrayList<>(2);
			List<Integer> partStarts = new ArrayList<>(2);
			// The segment holding 'from', then each later segment that starts inside the comment. Blank segments count.
			int first = 0;
			for (int k = 0; k < l.starts.length; k++) {
				if (l.starts[k] <= from) first = k;
			}
			for (int k = first; k < l.starts.length; k++) {
				if (k > first && l.starts[k] >= to) break;
				int a = Math.max(from, l.starts[k]);
				int end = k + 1 < l.starts.length ? l.starts[k + 1] : s.length();
				int b = Math.min(to, end);
				parts.add(s.substring(a, Math.max(a, b)));
				partLines.add(l.lines[k]);
				partStarts.add(a);
			}
			StringBuilder text = new StringBuilder(String.join("\n", parts));
			int[] pl = partLines.stream().mapToInt(Integer::intValue).toArray();
			int[] ps = partStarts.stream().mapToInt(Integer::intValue).toArray();
			return new Tok(Kind.COMMENT, text.toString(), ws, "", null, l.lineAt(from), from, pl, ps);
		}

		/** Index of the first significant token when the line is a directive (its first significant token is {@code #}). */
		int directiveIndex(List<Tok> toks) {
			for (int i = 0; i < toks.size(); i++) {
				Tok t = toks.get(i);
				if (t.kind == Kind.COMMENT) continue;
				return t.isPunct("#") ? i : -1;
			}
			return -1;
		}

		/**
		 * The significant (non-comment) tokens starting at {@code from}, each marked when a comment sat before it, so
		 * directive parsing sees a comment as one space.
		 */
		List<Tok> significant(List<Tok> toks, int from) {
			List<Tok> sig = new ArrayList<>();
			boolean commentSeen = false;
			for (int i = from; i < toks.size(); i++) {
				Tok t = toks.get(i);
				if (t.kind == Kind.COMMENT) {
					commentSeen = true;
					continue;
				}
				sig.add(commentSeen ? t.withGap(" ") : t);
				commentSeen = false;
			}
			return sig;
		}

		// ---- directives -------------------------------------------------------------------

		void directive(Logical l, List<Tok> toks, int hashIndex, boolean active, boolean isRoot) {
			int hashStart = toks.get(hashIndex).start;
			List<Tok> sig = significant(toks, hashIndex + 1);
			if (sig.isEmpty()) return;
			Tok name = sig.get(0);
			if (name.kind == Kind.NUM) return;
			if (name.kind != Kind.ID) {
				if (active) warn(l, "ignoring invalid directive");
				return;
			}
			List<Tok> args = sig.subList(1, sig.size());
			switch (name.text) {
				case "ifdef", "ifndef", "if" -> conditional(l, name.text, args, active);
				case "elif" -> elifDirective(l, args);
				case "else" -> elseDirective(l);
				case "endif" -> endifDirective(l);
				default -> {
					if (!active) return;
					switch (name.text) {
						case "define" -> defineDirective(l, args);
						case "undef" -> undefDirective(l, args);
						case "include" -> includeDirective(l, sig);
						case "error" -> {
							String rest = rawAfter(l, name);
							error(curPath, l.line1, "#error" + (rest.isEmpty() ? "" : " " + rest));
						}
						case "line" -> lineDirective(l, args);
						case "version" -> versionDirective(l, args, toks, isRoot, hashStart);
						case "extension" -> {
							if (!args.isEmpty()) extensions.add(directiveArgText(l, args.get(0)));
							emitRaw(l, toks, hashStart);
						}
						case "pragma" -> emitRaw(l, toks, hashStart);
						default -> warn(l, "unknown directive '#" + name.text + "' ignored");
					}
				}
			}
		}

		/** The raw spliced text after this token's text, stripped. */
		String rawAfter(Logical l, Tok t) {
			return l.spliced.substring(Math.min(t.start + t.text.length(), l.spliced.length())).strip();
		}

		/** The argument text from this token to the end of the line, each comment replaced by a space, stripped. */
		String directiveArgText(Logical l, Tok first) {
			String s = l.spliced;
			StringBuilder sb = new StringBuilder();
			int i = first.start;
			while (i < s.length()) {
				if (s.startsWith("//", i)) break;
				if (s.startsWith("/*", i)) {
					int e = s.indexOf("*/", i + 2);
					i = e < 0 ? s.length() : e + 2;
					sb.append(' ');
					continue;
				}
				sb.append(s.charAt(i++));
			}
			return sb.toString().strip();
		}

		void conditional(Logical l, String kind, List<Tok> args, boolean parentActive) {
			boolean value = false;
			if (parentActive) {
				value = switch (kind) {
					case "ifdef" -> definedName(firstId(l, args, kind));
					case "ifndef" -> !definedName(firstId(l, args, kind));
					default -> evalIf(l, args).truthy();
				};
			}
			conds.push(new Cond(kind, curPath, l.line1, parentActive, value));
		}

		String firstId(Logical l, List<Tok> args, String kind) {
			if (args.isEmpty() || args.get(0).kind != Kind.ID) {
				warn(l, "missing name after #" + kind);
				return "\0missing";
			}
			return args.get(0).text;
		}

		void elifDirective(Logical l, List<Tok> args) {
			if (conds.isEmpty()) error(curPath, l.line1, "#elif without #if");
			Cond c = conds.peek();
			if (c.sawElse) error(curPath, l.line1, "#elif after #else");
			boolean branchAllowed = c.parentActive && !c.taken;
			boolean value = branchAllowed && evalIf(l, args).truthy();
			if (value) c.taken = true;
			c.active = branchAllowed && value;
		}

		void elseDirective(Logical l) {
			if (conds.isEmpty()) error(curPath, l.line1, "#else without #if");
			Cond c = conds.peek();
			if (c.sawElse) error(curPath, l.line1, "duplicate #else");
			c.sawElse = true;
			c.active = c.parentActive && !c.taken;
			c.taken = true;
		}

		void endifDirective(Logical l) {
			if (conds.isEmpty()) {
				// A stray #endif in GLSL closes nothing. Packs ship one (Photon 1.4's TAAU pass, c3_taau_prep.fsh, ends with it)
				// and the loaders they are made on report it and go on (their logs in the packs' public issues), so it is a
				// warning and the line is dropped (E9). A stray #else or #elif, and any stray directive in a properties file,
				// stay errors.
				if (mode != Mode.GLSL) error(curPath, l.line1, "#endif without #if");
				warn(l, "#endif without #if ignored");
				return;
			}
			conds.pop();
		}

		void defineDirective(Logical l, List<Tok> args) {
			if (args.isEmpty() || args.get(0).kind != Kind.ID) error(curPath, l.line1, "#define requires a name");
			String name = args.get(0).text;
			List<Tok> body;
			List<String> params = List.of();
			boolean functionLike = false;
			if (args.size() > 1 && args.get(1).isPunct("(") && args.get(1).ws.isEmpty() && args.get(1).gap.isEmpty()) {
				functionLike = true;
				List<String> ps = new ArrayList<>();
				int i = 2;
				if (i < args.size() && args.get(i).isPunct(")")) {
					i++;
				} else {
					while (true) {
						if (i >= args.size() || args.get(i).kind != Kind.ID) error(curPath, l.line1, "invalid macro parameter list for '" + name + "'");
						String p = args.get(i).text;
						if (ps.contains(p)) error(curPath, l.line1, "duplicate macro parameter '" + p + "'");
						ps.add(p);
						i++;
						if (i >= args.size()) error(curPath, l.line1, "unterminated macro parameter list for '" + name + "'");
						if (args.get(i).isPunct(")")) {
							i++;
							break;
						}
						if (!args.get(i).isPunct(",")) error(curPath, l.line1, "invalid macro parameter list for '" + name + "'");
						i++;
					}
				}
				params = ps;
				body = new ArrayList<>(args.subList(i, args.size()));
			} else {
				body = new ArrayList<>(args.subList(1, args.size()));
			}
			if (!body.isEmpty()) body.set(0, body.get(0).withWs(""));
			macros.put(name, new Macro(name, functionLike, params, body));
			suppressed.remove(name);
			fileDefined.add(name);
		}

		void undefDirective(Logical l, List<Tok> args) {
			if (args.isEmpty() || args.get(0).kind != Kind.ID) error(curPath, l.line1, "#undef requires a name");
			macros.remove(args.get(0).text);
			if (DYNAMIC_BUILTINS.contains(args.get(0).text)) suppressed.add(args.get(0).text);
			if (args.size() > 1) warn(l, "extra tokens after #undef ignored");
		}

		void includeDirective(Logical l, List<Tok> sig) {
			List<Tok> args = sig.subList(1, sig.size());
			if (args.isEmpty()) error(curPath, l.line1, "#include requires a file name");
			Tok first = args.get(0);
			String name;
			if (first.kind == Kind.STR) {
				name = first.text.length() >= 2 ? first.text.substring(1, first.text.length() - 1) : "";
			} else if (first.isPunct("<")) {
				StringBuilder b = new StringBuilder();
				boolean closed = false;
				for (int i = 1; i < args.size(); i++) {
					Tok t = args.get(i);
					if (t.isPunct(">")) {
						closed = true;
						break;
					}
					if (!t.ws.isEmpty() || !t.gap.isEmpty()) {
						warn(l, "whitespace in #include name ignored");
						b.setLength(0);
					}
					b.append(t.text);
				}
				if (!closed) error(curPath, l.line1, "missing '>' in #include");
				name = b.toString();
			} else {
				error(curPath, l.line1, "expected \"name\" or <name> after #include");
				return;
			}
			if (name.isEmpty()) error(curPath, l.line1, "#include requires a file name");
			if (includeDepth >= MAX_INCLUDE_DEPTH) {
				error(curPath, l.line1, "include nesting deeper than " + MAX_INCLUDE_DEPTH + ": " + name);
			}
			SourceText st;
			try {
				st = includes == null ? null : includes.resolve(curPath, name).orElse(null);
			} catch (RuntimeException e) {
				throw new Fail(new Diagnostic(Diagnostic.Severity.ERROR, curPath, l.line1, "failed to read include '" + name + "': " + e));
			}
			if (st == null) error(curPath, l.line1, "include not found: " + name);
			includeDepth++;
			processFile(st, false);
			includeDepth--;
		}

		void lineDirective(Logical l, List<Tok> args) {
			if (args.isEmpty() || args.get(0).kind != Kind.NUM) {
				warn(l, "ignoring malformed #line");
				return;
			}
			Long v = parseNumber(args.get(0).text);
			if (v == null) {
				warn(l, "ignoring malformed #line");
				return;
			}
			int next = l.line1 + l.segs.size();
			lineDelta = (int) (v - next);
		}

		void versionDirective(Logical l, List<Tok> args, List<Tok> toks, boolean isRoot, int hashStart) {
			if (!isRoot) {
				warn(l, "#version in an included file is dropped");
				return;
			}
			if (args.isEmpty() || args.get(0).kind != Kind.NUM) {
				warn(l, "ignoring malformed #version");
				emitRaw(l, toks, hashStart);
				return;
			}
			if (versionArg == null) {
				versionArg = directiveArgText(l, args.get(0));
				Long v = parseNumber(args.get(0).text);
				if (v != null) versionNumber = (int) Math.min(Math.max(v, 0), 9999);
			} else {
				warn(l, "duplicate #version ignored");
			}
			emitRaw(l, toks, hashStart);
		}

		// ---- active lines ----------------------------------------------------------------

		void activeLine(Logical l, List<Tok> toks, List<Logical> all, int[] pos) {
			Feed feed = new Feed() {
				@Override
				public List<Tok> peekAt(int ahead) {
					int at = pos[0] + ahead;
					return at < all.size() ? shred(all.get(at)) : null;
				}

				@Override
				public void consume(int lines) {
					pos[0] += lines;
				}
			};
			renderExpanded(expandAll(toks, false, feed), l);
		}

		void renderExpanded(List<Tok> expanded, Logical l) {
			StringBuilder sb = new StringBuilder();
			int origin = -1;
			String last = null;
			for (Tok t : expanded) {
				if (t.kind == Kind.COMMENT) {
					String[] parts = t.text.split("\n", -1);
					for (int i = 0; i < parts.length; i++) {
						if (i == 0) {
							if (origin < 0) origin = t.commentLines[0];
							sb.append(t.ws).append(parts[0]);
						} else {
							out.add(sb.toString());
							map.add(new LineOrigin(curPath, origin < 0 ? t.commentLines[i] : origin));
							sb.setLength(0);
							origin = t.commentLines[i];
							sb.append(parts[i]);
						}
					}
					last = parts[parts.length - 1];
				} else {
					if (origin < 0) origin = t.line >= 0 ? t.line : l.line1;
					String sep = !t.ws.isEmpty() ? t.ws : !t.gap.isEmpty() ? t.gap : last != null && needsSpace(last, t.text) ? " " : "";
					sb.append(sep).append(t.text);
					last = t.text;
				}
			}
			out.add(sb.toString());
			map.add(new LineOrigin(curPath, origin < 0 ? l.line1 : origin));
		}

		/**
		 * Emits a kept directive line as written, from its {@code #} on, split only at physical boundaries inside block
		 * comments.
		 */
		void emitRaw(Logical l, List<Tok> toks, int from) {
			TreeSet<Integer> splits = new TreeSet<>();
			for (Tok t : toks) {
				if (t.kind == Kind.COMMENT && t.start >= from && t.commentStarts != null) {
					for (int k = 1; k < t.commentStarts.length; k++) splits.add(t.commentStarts[k]);
				}
			}
			int cur = from;
			for (int split : splits) {
				if (split <= cur || split >= l.spliced.length()) continue;
				out.add(l.spliced.substring(cur, split));
				map.add(new LineOrigin(curPath, l.lineAt(cur)));
				cur = split;
			}
			out.add(l.spliced.substring(cur));
			map.add(new LineOrigin(curPath, l.lineAt(cur)));
		}

		// ---- properties mode --------------------------------------------------------------

		void propertiesLine(String path, List<String> lines, int[] pos) {
			int lineNo = pos[0] + 1;
			String raw = lines.get(pos[0]++);
			String t = raw.stripLeading();
			if (!t.startsWith("#")) {
				if (activeNow()) {
					if (valueOpen) {
						// A kept line after an expanded value that a directive interrupted continues that value (PropertiesText joins
						// it whatever its text), so it expands like the value's key line (E6-fix-F).
						String text = expandedValue(lineNo, continued(t, lines, pos));
						valueOpen = text.endsWith("\\");
						out.add(text);
					} else {
						int eq = t.indexOf('=');
						boolean expanded = eq >= 0 && expandedKeys.test(t.substring(0, eq).strip());
						if (expanded) {
							String text = expandedLine(lineNo, continued(t, lines, pos), path);
							valueOpen = text.endsWith("\\");
							out.add(text);
						} else {
							out.add(raw);
						}
					}
					map.add(new LineOrigin(path, lineNo));
				}
				return;
			}
			String rest = t.substring(1);
			if (rest.isEmpty() || Character.isWhitespace(rest.charAt(0))) {
				// "# text" is a comment, even when the text starts with a conditional word.
				String kw = leadingId(rest.strip());
				if (CONDITIONAL_WORDS.contains(kw)) {
					warnings.add(new Diagnostic(Diagnostic.Severity.WARNING, path, lineNo,
							"'#" + kw + "' with a space after '#' is a comment; write '#" + kw + "' to make it a conditional"));
				}
				return;
			}
			String name = leadingId(rest);
			String argText = rest.substring(name.length());
			// Directives may be continued with a trailing backslash; the joined text is for parsing only.
			while (argText.endsWith("\\") && pos[0] < lines.size()) {
				argText = argText.substring(0, argText.length() - 1).stripTrailing();
				argText = argText + " " + lines.get(pos[0]++).strip();
			}
			if (argText.endsWith("\\")) argText = argText.substring(0, argText.length() - 1);
			Logical l = new Logical(lineNo, List.of(new Seg(lineNo, argText)));
			curLogical = l;
			switch (name) {
				case "if", "ifdef", "ifndef" -> conditional(l, name, significant(shred(l), 0), activeNow());
				case "elif" -> elifDirective(l, significant(shred(l), 0));
				case "else" -> elseDirective(l);
				case "endif" -> endifDirective(l);
				case "define" -> {
					if (activeNow()) {
						List<Tok> args = significant(shred(l), 0);
						if (args.isEmpty() || args.get(0).kind != Kind.ID) warn(l, "#define needs a name; the line is ignored");
						else defineDirective(l, args);
					}
				}
				case "undef" -> {
					if (activeNow()) {
						List<Tok> args = significant(shred(l), 0);
						if (args.isEmpty() || args.get(0).kind != Kind.ID) warn(l, "#undef needs a name; the line is ignored");
						else undefDirective(l, args);
					}
				}
				case "include" -> {
					if (activeNow()) warnings.add(new Diagnostic(Diagnostic.Severity.WARNING, path, lineNo, "#include is not a directive in a properties file; ignored"));
				}
				default -> {
				}
			}
		}

		/**
		 * The logical text of a key line and the lines it continues: each trailing backslash is dropped and the next physical line
		 * joins it, stripped, as PropertiesText joins continued lines. A directive line ends the join: a conditional may sit inside a
		 * continued value (Bliss's block.81 has #else and #endif between its continued lines), so the directive is processed in
		 * order, and the value keeps its backslash, which PropertiesText continues with the next kept line. The joined lines are
		 * consumed from {@code pos}.
		 */
		String continued(String first, List<String> lines, int[] pos) {
			StringBuilder sb = new StringBuilder();
			String seg = first.strip();
			while (true) {
				if (!seg.endsWith("\\")) {
					sb.append(seg);
					return sb.toString();
				}
				if (pos[0] >= lines.size() || lines.get(pos[0]).stripLeading().startsWith("#")) {
					sb.append(seg);
					return sb.toString();
				}
				sb.append(seg, 0, seg.length() - 1);
				seg = lines.get(pos[0]++).strip();
			}
		}

		/**
		 * A {@code key=value} line of an expanded key: the key, then its value expanded by {@link #expandedValue}.
		 */
		String expandedLine(int lineNo, String text, String path) {
			int eq = text.indexOf('=');
			return text.substring(0, eq).strip() + "=" + expandedValue(lineNo, text.substring(eq + 1));
		}

		/**
		 * The value of an expanded key, or a kept line that continues one (E6-fix-F), with the identifiers replaced through the
		 * macros defined at this line (object-like and function-like, C rescanning rules, as in GLSL mode). A macro name the value
		 * still holds after expansion (a function-like name with no arguments, or a macro that names itself) is warned about once
		 * per name, and the name stays in the value. A value that a directive interrupts keeps its trailing backslash.
		 */
		String expandedValue(int lineNo, String value) {
			boolean open = value.endsWith("\\");
			if (open) value = value.substring(0, value.length() - 1);
			Logical l = new Logical(lineNo, List.of(new Seg(lineNo, value)));
			curLogical = l;
			List<Tok> expanded = expandAll(significant(shred(l), 0), false, null);
			StringBuilder sb = new StringBuilder();
			String last = null;
			Set<String> left = new LinkedHashSet<>();
			for (Tok t : expanded) {
				if (t.kind == Kind.ID && macros.containsKey(t.text)) left.add(t.text);
				String sep = !t.ws.isEmpty() ? t.ws : !t.gap.isEmpty() ? t.gap : last != null && needsSpace(last, t.text) ? " " : "";
				sb.append(sep).append(t.text);
				last = t.text;
			}
			for (String name : left) {
				warn(l, "\"" + name + "\" is a macro that the value cannot expand; it stays in the value");
			}
			String out = sb.toString().strip();
			return open ? out + " \\" : out;
		}

		String leadingId(String s) {
			int i = 0;
			while (i < s.length() && isIdChar(s.charAt(i))) i++;
			return s.substring(0, i);
		}

		// ---- conditionals and expressions --------------------------------------------------

		boolean activeNow() {
			return conds.isEmpty() || conds.peek().active;
		}

		boolean definedName(String name) {
			return macros.containsKey(name) || builtinActive(name);
		}

		/** Whether a predefined builtin name is defined at this point (the GL profile names depend on {@code #version}). */
		boolean builtinActive(String name) {
			if (!DYNAMIC_BUILTINS.contains(name) || suppressed.contains(name)) return false;
			return switch (name) {
				case "GL_compatibility_profile" -> isCompatProfile();
				case "GL_core_profile" -> !isCompatProfile();
				default -> true;
			};
		}

		Val evalIf(Logical l, List<Tok> args) {
			if (args.isEmpty()) error(curPath, l.line1, "missing expression after #if/#elif");
			List<Tok> exp = expandAll(args, true, null);
			Expr e = new ExprParser(exp).parseFull();
			return e.eval(new Ev(l));
		}

		/** Evaluation context: answers defined() and raises errors with the directive's position. */
		private final class Ev {
			final Logical l;

			Ev(Logical l) {
				this.l = l;
			}

			boolean defined(String name) {
				return definedName(name);
			}

			<T> T err(String message) {
				error(curPath, l.line1, message);
				return null;
			}
		}

		private interface Expr {
			Val eval(Ev ev);
		}

		private record Num(Val v) implements Expr {
			@Override
			public Val eval(Ev ev) {
				return v;
			}
		}

		private record Zero() implements Expr {
			@Override
			public Val eval(Ev ev) {
				return Val.of(0);
			}
		}

		private record Defined(String name) implements Expr {
			@Override
			public Val eval(Ev ev) {
				return Val.of(ev.defined(name) ? 1 : 0);
			}
		}

		private record Un(String op, Expr a) implements Expr {
			@Override
			public Val eval(Ev ev) {
				if (op.equals("!")) return Val.of(a.eval(ev).truthy() ? 0 : 1);
				Val v = a.eval(ev);
				return switch (op) {
					case "+" -> v;
					case "-" -> v.flt() ? Val.ofF(-v.f()) : Val.of(-v.i());
					case "~" -> v.flt() ? ev.err("'~' needs an integer operand in #if") : Val.of(~v.i());
					default -> ev.err("unknown unary operator " + op);
				};
			}
		}

		private record Bin(String op, Expr a, Expr b) implements Expr {
			@Override
			public Val eval(Ev ev) {
				if (op.equals("&&")) return a.eval(ev).truthy() ? Val.of(b.eval(ev).truthy() ? 1 : 0) : Val.of(0);
				if (op.equals("||")) return a.eval(ev).truthy() ? Val.of(1) : Val.of(b.eval(ev).truthy() ? 1 : 0);
				return arith(ev, op, a.eval(ev), b.eval(ev));
			}
		}

		private record Tern(Expr c, Expr a, Expr b) implements Expr {
			@Override
			public Val eval(Ev ev) {
				return c.eval(ev).truthy() ? a.eval(ev) : b.eval(ev);
			}
		}

		static Val arith(Ev ev, String op, Val x, Val y) {
			boolean flt = x.flt() || y.flt();
			return switch (op) {
				case "+" -> flt ? Val.ofF(d(x) + d(y)) : Val.of(x.i() + y.i());
				case "-" -> flt ? Val.ofF(d(x) - d(y)) : Val.of(x.i() - y.i());
				case "*" -> flt ? Val.ofF(d(x) * d(y)) : Val.of(x.i() * y.i());
				case "/" -> {
					if (flt) {
						if (d(y) == 0.0) ev.err("division by zero in #if");
						yield Val.ofF(d(x) / d(y));
					}
					if (y.i() == 0) ev.err("division by zero in #if");
					yield Val.of(x.i() / y.i());
				}
				case "%" -> {
					if (flt) ev.err("float modulo in #if");
					if (y.i() == 0) ev.err("modulo by zero in #if");
					yield Val.of(x.i() % y.i());
				}
				case "<<", ">>" -> {
					if (flt) ev.err("shift needs integer operands in #if");
					long n = y.i();
					if (n < 0) ev.err("negative shift count in #if");
					n &= 63;
					yield op.equals("<<") ? Val.of(x.i() << n) : Val.of(x.i() >> n);
				}
				case "<" -> Val.of((flt ? d(x) < d(y) : x.i() < y.i()) ? 1 : 0);
				case ">" -> Val.of((flt ? d(x) > d(y) : x.i() > y.i()) ? 1 : 0);
				case "<=" -> Val.of((flt ? d(x) <= d(y) : x.i() <= y.i()) ? 1 : 0);
				case ">=" -> Val.of((flt ? d(x) >= d(y) : x.i() >= y.i()) ? 1 : 0);
				case "==" -> Val.of((flt ? d(x) == d(y) : x.i() == y.i()) ? 1 : 0);
				case "!=" -> Val.of((flt ? d(x) != d(y) : x.i() != y.i()) ? 1 : 0);
				case "&" -> x.flt() || y.flt() ? ev.err("'&' needs integer operands in #if") : Val.of(x.i() & y.i());
				case "^" -> x.flt() || y.flt() ? ev.err("'^' needs integer operands in #if") : Val.of(x.i() ^ y.i());
				case "|" -> x.flt() || y.flt() ? ev.err("'|' needs integer operands in #if") : Val.of(x.i() | y.i());
				default -> ev.err("unknown operator " + op);
			};
		}

		static double d(Val v) {
			return v.flt() ? v.f() : v.i();
		}

		private final class ExprParser {
			private final List<Tok> toks;
			private int pos;

			ExprParser(List<Tok> toks) {
				this.toks = toks;
			}

			Expr parseFull() {
				Expr e = ternary();
				Tok t = peek();
				if (t != null) err("unexpected '" + t.text + "' in #if expression");
				return e;
			}

			Expr ternary() {
				Expr c = lor();
				Tok t = peek();
				if (t != null && t.isPunct("?")) {
					pos++;
					Expr a = ternary();
					expect(":");
					Expr b = ternary();
					return new Tern(c, a, b);
				}
				return c;
			}

			Expr lor() {
				Expr e = land();
				while (match("||")) e = new Bin("||", e, land());
				return e;
			}

			Expr land() {
				Expr e = bor();
				while (match("&&")) e = new Bin("&&", e, bor());
				return e;
			}

			Expr bor() {
				Expr e = bxor();
				while (match("|")) e = new Bin("|", e, bxor());
				return e;
			}

			Expr bxor() {
				Expr e = band();
				while (match("^")) e = new Bin("^", e, band());
				return e;
			}

			Expr band() {
				Expr e = equality();
				while (match("&")) e = new Bin("&", e, equality());
				return e;
			}

			Expr equality() {
				Expr e = relational();
				while (true) {
					if (match("==")) e = new Bin("==", e, relational());
					else if (match("!=")) e = new Bin("!=", e, relational());
					else return e;
				}
			}

			Expr relational() {
				Expr e = shift();
				while (true) {
					if (match("<=")) e = new Bin("<=", e, shift());
					else if (match(">=")) e = new Bin(">=", e, shift());
					else if (match("<")) e = new Bin("<", e, shift());
					else if (match(">")) e = new Bin(">", e, shift());
					else return e;
				}
			}

			Expr shift() {
				Expr e = additive();
				while (true) {
					if (match("<<")) e = new Bin("<<", e, additive());
					else if (match(">>")) e = new Bin(">>", e, additive());
					else return e;
				}
			}

			Expr additive() {
				Expr e = multiplicative();
				while (true) {
					if (match("+")) e = new Bin("+", e, multiplicative());
					else if (match("-")) e = new Bin("-", e, multiplicative());
					else return e;
				}
			}

			Expr multiplicative() {
				Expr e = unary();
				while (true) {
					if (match("*")) e = new Bin("*", e, unary());
					else if (match("/")) e = new Bin("/", e, unary());
					else if (match("%")) e = new Bin("%", e, unary());
					else return e;
				}
			}

			Expr unary() {
				Tok t = peek();
				if (t == null) err("missing operand in #if expression");
				if (t.isPunct("+") || t.isPunct("-") || t.isPunct("~") || t.isPunct("!")) {
					pos++;
					return new Un(t.text, unary());
				}
				return primary();
			}

			Expr primary() {
				Tok t = peek();
				if (t == null) err("missing operand in #if expression");
				pos++;
				if (t.isPunct("(")) {
					Expr e = ternary();
					expect(")");
					return e;
				}
				if (t.kind == Kind.NUM) return new Num(number(t));
				if (t.kind == Kind.ID) {
					if (t.text.equals("defined")) {
						Tok a = peek();
						if (a == null) err("missing name after defined");
						if (a.isPunct("(")) {
							pos++;
							Tok n = peek();
							if (n == null || n.kind != Kind.ID) err("defined(...) needs an identifier");
							pos++;
							expect(")");
							return new Defined(n.text);
						}
						if (a.kind != Kind.ID) err("missing name after defined");
						pos++;
						return new Defined(a.text);
					}
					return new Zero();
				}
				if (t.kind == Kind.STR) err("string literal in #if expression");
				return err("unexpected '" + t.text + "' in #if expression");
			}

			/** An integer constant, or a floating constant (accepted with a warning); anything else is an error. */
			Val number(Tok t) {
				Long v = parseNumber(t.text);
				if (v != null) return Val.of(v);
				String f = t.text;
				boolean hex = f.startsWith("0x") || f.startsWith("0X");
				if (!hex && (f.indexOf('.') >= 0 || f.indexOf('e') >= 0 || f.indexOf('E') >= 0)) {
					try {
						double d = Double.parseDouble(stripFloatSuffix(f));
						warn(curLogical, "floating-point value '" + t.text + "' in #if; only integer constants are defined by GLSL");
						return Val.ofF(d);
					} catch (NumberFormatException e) {
						// fall through to the error below
					}
				}
				return err("invalid integer constant '" + t.text + "' in #if expression");
			}

			boolean match(String p) {
				Tok t = peek();
				if (t != null && t.isPunct(p)) {
					pos++;
					return true;
				}
				return false;
			}

			void expect(String p) {
				Tok t = peek();
				if (t == null || !t.isPunct(p)) err("expected '" + p + "' in #if expression");
				pos++;
			}

			Tok peek() {
				while (pos < toks.size() && toks.get(pos).kind == Kind.COMMENT) pos++;
				return pos < toks.size() ? toks.get(pos) : null;
			}

			<T> T err(String message) {
				error(curPath, curLogical.line1, message);
				return null;
			}
		}

		// ---- macro expansion --------------------------------------------------------------

		List<Tok> expandAll(List<Tok> input, boolean ifContext, Feed feed) {
			ArrayDeque<Tok> work = new ArrayDeque<>(input);
			List<Tok> res = new ArrayList<>();
			while (!work.isEmpty()) {
				if (++steps > MAX_EXPANSION_STEPS) error(curPath, curLogical.line1, "macro expansion did not terminate (recursive macros?)");
				Tok t = work.poll();
				if (t.kind != Kind.ID) {
					res.add(t);
					continue;
				}
				if (ifContext && t.text.equals("defined")) {
					res.add(t);
					takeDefinedOperand(work, res);
					continue;
				}
				if (!macros.containsKey(t.text) && builtinActive(t.text)) {
					res.add(builtinExpansion(t));
					continue;
				}
				Macro m = macros.get(t.text);
				if (m == null || (t.hides != null && t.hides.contains(t.text))) {
					res.add(t);
					continue;
				}
				Set<String> hs = union(t.hides, t.text);
				if (!m.functionLike()) {
					List<Tok> repl = substitute(m, List.of(), List.of(), hs, t);
					if (!repl.isEmpty()) repl.set(0, repl.get(0).withWsIfEmpty(t.ws));
					pushFront(work, repl);
					continue;
				}
				// Function-like: a '(' must follow. Newlines count as spaces (C99 5.1.1.2): it may come after blank and
				// comment-only lines. A directive or the end of the file stops the search.
				Tok open = null;
				List<Tok> gap = new ArrayList<>(2);    // comments on the invocation's own line, before the '('
				List<Tok> skipped = new ArrayList<>(); // comments on blank or comment-only lines passed over
				int ahead = 0;
				while (true) {
					if (work.isEmpty()) {
						List<Tok> nxt = feed == null ? null : feed.peekAt(ahead);
						if (nxt == null || directiveIndex(nxt) >= 0) break;
						if (startsWithParen(nxt)) {
							feed.consume(ahead + 1);
							gap.addAll(skipped);
							skipped.clear();
							ahead = 0;
							work.addAll(nxt);
						} else if (onlyComments(nxt)) {
							skipped.addAll(nxt);
							ahead++;
							continue;
						} else {
							break;
						}
					}
					Tok x = work.poll();
					if (x.kind == Kind.COMMENT) {
						gap.add(x);
						continue;
					}
					if (x.isPunct("(")) open = x;
					else gap.add(x);
					break;
				}
				if (open == null) {
					for (int i = gap.size() - 1; i >= 0; i--) work.push(gap.get(i));
					res.add(t);
					continue;
				}
				List<List<Tok>> rawArgs = collectArgs(t, work, feed);
				if (m.params().isEmpty() && rawArgs.size() == 1 && rawArgs.get(0).isEmpty()) rawArgs = List.of();
				if (rawArgs.size() != m.params().size()) {
					error(curPath, t.line, "macro '" + t.text + "' expects " + m.params().size() + " argument(s), got " + rawArgs.size());
				}
				List<List<Tok>> expArgs = new ArrayList<>(rawArgs.size());
				for (List<Tok> a : rawArgs) expArgs.add(expandAll(a, false, null));
				List<Tok> repl = substitute(m, rawArgs, expArgs, hs, t);
				for (Tok g : gap) {
					if (!g.text.startsWith("//")) res.add(g); // a line comment would swallow the code after it
				}
				if (!repl.isEmpty()) repl.set(0, repl.get(0).withWsIfEmpty(t.ws));
				pushFront(work, repl);
			}
			return res;
		}

		boolean startsWithParen(List<Tok> toks) {
			for (Tok t : toks) {
				if (t.kind == Kind.COMMENT) continue;
				return t.isPunct("(");
			}
			return false;
		}

		/** True when a line holds nothing but comments (or nothing at all). */
		static boolean onlyComments(List<Tok> toks) {
			for (Tok t : toks) {
				if (t.kind != Kind.COMMENT) return false;
			}
			return true;
		}

		void takeDefinedOperand(ArrayDeque<Tok> work, List<Tok> res) {
			Tok a = pollSignificant(work, res);
			if (a == null) return;
			res.add(a);
			if (a.isPunct("(")) {
				Tok n = pollSignificant(work, res);
				if (n != null) {
					res.add(n);
					Tok c = pollSignificant(work, res);
					if (c != null && c.isPunct(")")) res.add(c);
					else if (c != null) work.push(c);
				}
			}
		}

		Tok pollSignificant(ArrayDeque<Tok> work, List<Tok> res) {
			while (!work.isEmpty()) {
				Tok x = work.poll();
				if (x.kind == Kind.COMMENT) {
					res.add(x);
					continue;
				}
				return x;
			}
			return null;
		}

		/** The arguments of an invocation, read from the work queue and then the following lines. */
		List<List<Tok>> collectArgs(Tok invocation, ArrayDeque<Tok> work, Feed feed) {
			List<List<Tok>> args = new ArrayList<>();
			List<Tok> cur = new ArrayList<>();
			int depth = 1;
			while (true) {
				if (work.isEmpty()) {
					List<Tok> more = feed == null ? null : feed.peekAt(0);
					if (more == null) error(curPath, invocation.line, "unterminated invocation of macro '" + invocation.text + "'");
					if (directiveIndex(more) >= 0) error(curPath, invocation.line, "directive inside macro invocation (started here)");
					feed.consume(1);
					work.addAll(more);
					continue;
				}
				Tok x = work.poll();
				if (x.kind == Kind.COMMENT) continue;
				if (x.isPunct("(")) {
					depth++;
					cur.add(x);
				} else if (x.isPunct(")")) {
					depth--;
					if (depth == 0) break;
					cur.add(x);
				} else if (x.isPunct(",") && depth == 1) {
					args.add(cur);
					cur = new ArrayList<>();
				} else {
					cur.add(x);
				}
			}
			args.add(cur);
			return args;
		}

		/**
		 * The replacement list of one expansion. Parameters take the raw argument next to {@code #} and {@code ##} and
		 * the expanded argument elsewhere; {@code ##} pastes, and an empty operand becomes a placemarker that is removed at
		 * the end (C99 6.10.3.3). Object-like macros use the same rules with no parameters.
		 */
		List<Tok> substitute(Macro m, List<List<Tok>> rawArgs, List<List<Tok>> expArgs, Set<String> hs, Tok invocation) {
			List<Tok> body = m.body();
			List<String> params = m.params();
			List<Tok> r = new ArrayList<>(body.size());
			for (int i = 0; i < body.size(); i++) {
				Tok b = body.get(i);
				Tok next = i + 1 < body.size() ? body.get(i + 1) : null;
				if (m.functionLike() && b.isPunct("#") && next != null && next.kind == Kind.ID && params.contains(next.text)) {
					r.add(stringify(rawArgs.get(params.indexOf(next.text)), invocation));
					i++;
					continue;
				}
				if (b.isPunct("##")) {
					if (next == null) error(curPath, invocation.line, "'##' at the end of a macro body");
					Tok left = r.isEmpty() ? PLACEMARKER : r.remove(r.size() - 1);
					r.addAll(paste(left, operand(next, params, rawArgs, hs, invocation), invocation));
					i++;
					continue;
				}
				boolean pasteRight = next != null && next.isPunct("##");
				int pi = b.kind == Kind.ID ? params.indexOf(b.text) : -1;
				if (pi >= 0) {
					List<Tok> src = pasteRight ? rawArgs.get(pi) : expArgs.get(pi);
					if (src.isEmpty()) {
						if (pasteRight) r.add(PLACEMARKER);
					} else {
						for (Tok a : src) r.add(a.withLine(invocation.line));
					}
					continue;
				}
				r.add(b.withHides(hs).withLine(invocation.line));
			}
			List<Tok> out = new ArrayList<>(r.size());
			for (Tok x : r) {
				if (x != PLACEMARKER) out.add(x.withHides(unionSet(x.hides, hs)));
			}
			return out;
		}

		/** The right operand of {@code ##}: the raw argument when it is a parameter, else the token itself. */
		List<Tok> operand(Tok t, List<String> params, List<List<Tok>> rawArgs, Set<String> hs, Tok invocation) {
			int pi = t.kind == Kind.ID ? params.indexOf(t.text) : -1;
			if (pi >= 0) {
				List<Tok> a = rawArgs.get(pi);
				if (a.isEmpty()) return List.of(PLACEMARKER);
				List<Tok> copy = new ArrayList<>(a.size());
				for (Tok x : a) copy.add(x.withLine(invocation.line));
				return copy;
			}
			return List.of(t.withHides(hs).withLine(invocation.line));
		}

		/** Pastes the last token of the left side with the first token of the right side; the rest is kept. */
		List<Tok> paste(Tok left, List<Tok> right, Tok invocation) {
			List<Tok> out = new ArrayList<>(right.size());
			Tok first = right.get(0);
			if (left == PLACEMARKER) out.add(first);
			else if (first == PLACEMARKER) out.add(left);
			else out.add(pasteTwo(left, first, invocation));
			out.addAll(right.subList(1, right.size()));
			return out;
		}

		Tok pasteTwo(Tok left, Tok right, Tok invocation) {
			String text = left.text + right.text;
			if (text.startsWith("//") || text.startsWith("/*")) {
				error(curPath, invocation.line, "## pastes '" + left.text + "' and '" + right.text + "' into a comment start");
			}
			Kind kind = isIdStart(text.charAt(0)) ? Kind.ID : Character.isDigit(text.charAt(0)) ? Kind.NUM : Kind.PUNCT;
			return new Tok(kind, text, left.ws, left.gap, intersect(left.hides, right.hides), left.line, -1, null, null);
		}

		Tok stringify(List<Tok> arg, Tok invocation) {
			StringBuilder sb = new StringBuilder("\"");
			for (int i = 0; i < arg.size(); i++) {
				Tok a = arg.get(i);
				if (i > 0 && (!a.ws.isEmpty() || !a.gap.isEmpty())) sb.append(' ');
				sb.append(a.kind == Kind.STR ? a.text.replace("\\", "\\\\").replace("\"", "\\\"") : a.text);
			}
			sb.append('"');
			return new Tok(Kind.STR, sb.toString(), "", "", null, invocation.line, -1, null, null);
		}

		Tok builtinExpansion(Tok t) {
			String value = switch (t.text) {
				case "__LINE__" -> String.valueOf(t.line + lineDelta);
				case "__FILE__" -> String.valueOf(curFileId);
				case "__VERSION__" -> String.valueOf(versionNumber);
				default -> "1"; // a GL profile name reaches here only when it is the active profile
			};
			return new Tok(Kind.NUM, value, t.ws, t.gap, t.hides, t.line, t.start, null, null);
		}

		boolean isCompatProfile() {
			if (versionArg == null) return true;
			return versionArg.contains("compatibility") || versionNumber < 150;
		}

		Set<String> union(Set<String> a, String add) {
			if (a == null || a.isEmpty()) return Set.of(add);
			if (a.contains(add)) return a;
			Set<String> out = new HashSet<>(a.size() + 1);
			out.addAll(a);
			out.add(add);
			return out;
		}

		Set<String> unionSet(Set<String> a, Set<String> b) {
			if (a == null || a.isEmpty()) return b;
			if (b == null || b.isEmpty()) return a;
			Set<String> out = new HashSet<>(a.size() + b.size());
			out.addAll(a);
			out.addAll(b);
			return out;
		}

		Set<String> intersect(Set<String> a, Set<String> b) {
			if (a == null) return b;
			if (b == null) return a;
			Set<String> out = new HashSet<>(a);
			out.retainAll(b);
			return out;
		}

		static void pushFront(ArrayDeque<Tok> work, List<Tok> toks) {
			for (int i = toks.size() - 1; i >= 0; i--) work.push(toks.get(i));
		}

		// ---- result -----------------------------------------------------------------------

		PreprocessResult result() {
			Map<String, String> objs = new LinkedHashMap<>();
			for (String n : fileDefined) {
				Macro m = macros.get(n);
				if (m != null && !m.functionLike()) objs.put(n, renderBody(m.body()));
			}
			return new PreprocessResult(String.join("\n", out), map, versionArg, extensions, files, objs, warnings);
		}

		String renderBody(List<Tok> body) {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < body.size(); i++) {
				Tok t = body.get(i);
				if (i > 0) {
					String sep = !t.ws.isEmpty() ? t.ws : !t.gap.isEmpty() ? t.gap : " ";
					sb.append(sep);
				}
				sb.append(t.text);
			}
			return sb.toString();
		}

		// ---- helpers ----------------------------------------------------------------------

		void warn(Logical l, String message) {
			warnings.add(new Diagnostic(Diagnostic.Severity.WARNING, curPath, l.line1, message));
		}

		void error(String path, int line, String message) {
			throw new Fail(new Diagnostic(Diagnostic.Severity.ERROR, path, line, message));
		}
	}

	static List<String> splitLines(String text) {
		List<String> lines = new ArrayList<>();
		int start = 0;
		for (int i = 0; i < text.length(); i++) {
			if (text.charAt(i) == '\n') {
				lines.add(text.substring(start, i));
				start = i + 1;
			}
		}
		if (start < text.length()) lines.add(text.substring(start));
		return lines;
	}

	/**
	 * Groups physical lines into logical lines. A line ending in a backslash continues the logical line. So does a line
	 * that ends inside a block comment, so a comment that runs over several lines is one logical line. A {@code //}
	 * comment continued by a backslash also carries its next line, as in C (phase 2 precedes comment removal).
	 */
	private static List<Logical> splice(List<String> lines) {
		List<Logical> res = new ArrayList<>();
		boolean inBlock = false;
		int i = 0;
		while (i < lines.size()) {
			int line1 = i + 1;
			List<Seg> segs = new ArrayList<>(2);
			boolean lineComment = false;
			while (true) {
				String text = lines.get(i);
				boolean continued = text.endsWith("\\");
				String body = continued ? text.substring(0, text.length() - 1) : text;
				segs.add(new Seg(i + 1, body));
				if (!lineComment) {
					int k = 0;
					int n = body.length();
					boolean endsInLineComment = false;
					while (k < n) {
						if (inBlock) {
							int e = body.indexOf("*/", k);
							if (e < 0) {
								k = n;
							} else {
								inBlock = false;
								k = e + 2;
							}
							continue;
						}
						char c = body.charAt(k);
						if (c == '"') {
							int e = k + 1;
							while (e < n && body.charAt(e) != '"') {
								if (body.charAt(e) == '\\') e++;
								e++;
							}
							k = Math.min(e + 1, n);
							continue;
						}
						if (c == '/' && k + 1 < n && body.charAt(k + 1) == '/') {
							endsInLineComment = true;
							break;
						}
						if (c == '/' && k + 1 < n && body.charAt(k + 1) == '*') {
							inBlock = true;
							k += 2;
							continue;
						}
						k++;
					}
					lineComment = endsInLineComment && continued;
				}
				i++;
				if ((!continued && !inBlock) || i >= lines.size()) break;
			}
			res.add(new Logical(line1, segs));
		}
		return res;
	}

	static boolean isWs(char c) {
		return c == ' ' || c == '\t' || c == '\f' || c == 0x0B;
	}

	static boolean isIdStart(char c) {
		return c == '_' || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
	}

	static boolean isIdChar(char c) {
		return isIdStart(c) || (c >= '0' && c <= '9');
	}

	/** True when two rendered tokens would glue into one different token, so a space is needed between them. */
	static boolean needsSpace(String a, String b) {
		if (a.isEmpty() || b.isEmpty()) return false;
		char la = a.charAt(a.length() - 1);
		char fb = b.charAt(0);
		if (isIdChar(la) && isIdChar(fb)) return true;
		if (Character.isDigit(la) && fb == '.') return true; // "1" "." would read as the number "1."
		if (la == '.' && Character.isDigit(fb)) return true; // "." "5" would read as ".5"
		if (la == '/' && (fb == '/' || fb == '*')) return true;
		String pair = "" + la + fb;
		for (String op : OPS) {
			if (op.equals(pair)) return true;
		}
		return false;
	}

	/**
	 * Parses an integer constant: decimal, octal (leading 0), hexadecimal (0x), with any u/U/l/L suffix. Returns null when
	 * the text is not an integer constant.
	 */
	static Long parseNumber(String text) {
		String t = stripIntSuffix(text);
		try {
			if (t.startsWith("0x") || t.startsWith("0X")) {
				if (t.length() == 2) return null;
				return Long.parseLong(t.substring(2), 16);
			}
			if (t.startsWith("0") && t.length() > 1) return Long.parseLong(t.substring(1), 8);
			return Long.parseLong(t);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	static String stripIntSuffix(String text) {
		int end = text.length();
		while (end > 0 && "uUlL".indexOf(text.charAt(end - 1)) >= 0) end--;
		return text.substring(0, end);
	}

	static String stripFloatSuffix(String text) {
		int end = text.length();
		if (end > 0 && "fF".indexOf(text.charAt(end - 1)) >= 0) end--;
		return text.substring(0, end);
	}
}
