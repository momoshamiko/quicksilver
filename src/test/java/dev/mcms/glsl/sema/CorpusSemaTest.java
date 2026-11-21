// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.mcms.glsl.GlslSyntaxException;
import dev.mcms.glsl.ast.Unit;
import dev.mcms.glsl.parse.Parser;
import dev.mcms.glsl.preprocess.LineRewriter;
import dev.mcms.glsl.preprocess.Mode;
import dev.mcms.glsl.preprocess.PreprocessException;
import dev.mcms.glsl.preprocess.PreprocessResult;
import dev.mcms.glsl.preprocess.Preprocessor;
import dev.mcms.glsl.preprocess.SourceText;
import dev.mcms.shaders.pack.PackSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every program the game runs for every corpus pack is preprocessed with {@link CorpusMacros}, parsed, and analyzed in
 * lenient mode. Each pack runs at its defaults; a pack with a file in {@code bench/pack-options/<zip>.txt} also runs at
 * those options. The test prints one summary line per run, every error, and the leniencies each run needs.
 *
 * <p>An error passes only when {@link #KNOWN} lists it. An engine gap is named with its program and cause. A pack-side
 * error is listed only with a run-log line where Iris or Vitrail also fails on that program at that configuration; its
 * reason quotes that line after "log:". Any other error fails the test.
 *
 * <p>Runs with {@code -Pcorpus=<folder with the pack zips>}. The packs are test inputs and are never committed.
 */
@Tag("corpus")
class CorpusSemaTest {

	static final Map<String, String> MACROS = CorpusMacros.table();

	/**
	 * An error the corpus shows that the analyzer does not report as clean. {@code packSide} marks a pack's own error;
	 * its reason must quote a run-log line after "log:". An engine gap's reason names the cause.
	 */
	record Known(String pack, String run, String code, String token, List<String> programs, boolean packSide,
			String reason) {
	}

	private static final String CLOUDS_REASON = "pack-side at the bench options (CLOUD_STYLE_DEFINE=50, so"
			+ " lib/colors/cloudColors.glsl is included): its globals read sky colours that"
			+ " lib/colors/skyColors.glsl declares only under #ifdef OVERWORLD, and the Nether and End programs define"
			+ " no OVERWORLD. Defaults (CLOUD_STYLE_DEFINE=-1) do not include the file. Vitrail, run with the bench"
			+ " options: log: results/2026-10-26-pins-dims/metal-vitrail/run1/game-latest.log: \"vitrail:pack/3/fancy"
			+ "/world-1/gbuffers_clouds/fragment:2147: error: 'dayMiddleSkyColor' : undeclared identifier\". Iris,"
			+ " Complementary with the bench's options (results/2026-10-11-m0c-dims README): log: results/2026-10-11-m0c-dims"
			+ "/gl-iris/run1/game-latest.log: \"Shader compilation log for clouds: ERROR: 0:418: Use of undeclared"
			+ " identifier 'dayMiddleSkyColor'\".";

	/** Known errors. Empty until each one is fixed or shown to be pack-side. */
	static final List<Known> KNOWN = List.of(
			new Known("ComplementaryReimagined", "bench options", "unknown-identifier", "dayMiddleSkyColor",
					List.of("world-1/gbuffers_clouds.fsh", "world1/gbuffers_clouds.fsh"), true, CLOUDS_REASON),
			new Known("ComplementaryReimagined", "bench options", "unknown-identifier", "nightMiddleSkyColor",
					List.of("world-1/gbuffers_clouds.fsh", "world1/gbuffers_clouds.fsh"), true, CLOUDS_REASON));

	private record Finding(String pack, String program, String code, String text) {
	}

	/** One run: a pack at one set of options (null options = the defaults). */
	private record Run(Path zip, String name, Map<String, String> options) {
	}

	/** What one run produced. */
	private static final class Tally {
		final String name;
		int vertex;
		int fragment;
		int compute;
		int disabled;
		int unrecognised;
		final List<Finding> errors = new ArrayList<>();
		final Map<String, Set<String>> leniencies = new TreeMap<>();
		final Map<String, String> samples = new TreeMap<>();
		int warnings;
		long elapsedMs;

		Tally(String name) {
			this.name = name;
		}
	}

	static Stage stageOf(String path) {
		if (path.endsWith(".vsh")) {
			return Stage.VERTEX;
		}
		if (path.endsWith(".fsh")) {
			return Stage.FRAGMENT;
		}
		if (path.endsWith(".csh")) {
			return Stage.COMPUTE;
		}
		return null; // geometry programs are not part of the pack format used here
	}

	private static boolean matches(Finding f, Known k) {
		return f.pack().startsWith(k.pack()) && f.pack().endsWith("(" + k.run() + ")") && f.code().equals(k.code())
				&& f.text().contains(k.token()) && k.programs().contains(f.program());
	}

	private static Known knownFor(Finding f) {
		for (Known k : KNOWN) {
			if (matches(f, k)) {
				return k;
			}
		}
		return null;
	}

	@Test
	void everyCorpusProgramAnalyzes() throws IOException {
		String dir = System.getProperty("mcms.test.corpus");
		assumeTrue(dir != null, "no corpus folder given");
		Path folder = Path.of(dir);
		assumeTrue(Files.isDirectory(folder), dir + " is not a folder");
		Path optionsDir = folder.toAbsolutePath().getParent().getParent().resolve("pack-options");
		List<Path> zips;
		try (Stream<Path> s = Files.list(folder)) {
			zips = s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".zip")).sorted().toList();
		}
		assumeTrue(!zips.isEmpty(), "no pack zips in " + dir);

		for (Known k : KNOWN) {
			assertTrue(!k.packSide() || k.reason().contains("log:"),
					"pack-side entry without a run-log line: " + k.pack() + " " + k.token());
		}

		List<String> unexpected = new ArrayList<>();
		for (Run run : runs(zips, optionsDir)) {
			Tally t = analyze(run);
			report(t);
			for (Finding f : t.errors) {
				if (knownFor(f) == null) {
					unexpected.add(f.text());
				}
			}
		}
		assertTrue(unexpected.isEmpty(), "errors not listed as known:\n" + String.join("\n", unexpected));
	}

	private static List<Run> runs(List<Path> zips, Path optionsDir) throws IOException {
		List<Run> runs = new ArrayList<>();
		for (Path zip : zips) {
			String pack = zip.getFileName().toString();
			runs.add(new Run(zip, pack + " (defaults)", null));
			Path file = optionsDir.resolve(pack + ".txt");
			if (Files.isRegularFile(file)) {
				runs.add(new Run(zip, pack + " (bench options)", CorpusOptions.parse(Files.readString(file))));
			}
		}
		return runs;
	}

	private static Tally analyze(Run run) throws IOException {
		Tally t = new Tally(run.name());
		long t0 = System.nanoTime();
		LineRewriter rewriter = run.options() == null ? null : CorpusOptions.rewriter(run.options());
		try (PackSource source = PackSource.open(run.zip())) {
			CorpusPrograms programs = CorpusPrograms.of(source, rewriter, MACROS);
			List<String> paths = new ArrayList<>();
			for (String entry : source.list("")) {
				if (entry.endsWith("/")) {
					if (entry.startsWith("world")) {
						for (String file : source.list(entry)) {
							paths.add(entry + file);
						}
					}
				} else {
					paths.add(entry);
				}
			}
			for (String path : paths) {
				CorpusPrograms.ProgramFile p = CorpusPrograms.programFile(path);
				if (p == null) {
					t.unrecognised++;
				} else if (!programs.enabled(p)) {
					t.disabled++;
				} else {
					analyzeProgram(t, source, path, rewriter);
				}
			}
		}
		t.elapsedMs = (System.nanoTime() - t0) / 1_000_000;
		return t;
	}

	private static void analyzeProgram(Tally t, PackSource source, String path, LineRewriter rewriter) {
		Stage stage = stageOf(path);
		if (stage == null) {
			t.unrecognised++;
			return;
		}
		switch (stage) {
			case VERTEX -> t.vertex++;
			case FRAGMENT -> t.fragment++;
			case COMPUTE -> t.compute++;
		}
		String text = source.readText(path).orElse("");
		PreprocessResult pre;
		try {
			pre = new Preprocessor(Mode.GLSL, source.includeResolver(), MACROS, rewriter)
					.process(new SourceText(path, text));
		} catch (PreprocessException e) {
			t.errors.add(new Finding(t.name, path, "preprocess", t.name + " " + path + ": preprocess: "
					+ e.diagnostics().get(0)));
			return;
		}
		List<SourceLine> lineMap = pre.lineMap().stream().map(o -> new SourceLine(o.path(), o.line())).toList();
		Unit unit;
		try {
			unit = Parser.parse(pre.text());
		} catch (GlslSyntaxException e) {
			t.errors.add(new Finding(t.name, path, "parse", t.name + " " + where(lineMap, path, e.line())
					+ " parse: " + e.getMessage()));
			return;
		}
		SemaResult r = SemanticAnalyzer.analyze(unit, SemaOptions.lenient(stage, lineMap));
		for (SemaDiagnostic d : r.diagnostics()) {
			if (d.severity() == SemaDiagnostic.Severity.ERROR) {
				t.errors.add(new Finding(t.name, path, d.code(), t.name + " " + location(lineMap, path, d) + " "
						+ d.code() + ": " + d.message()));
			} else {
				t.warnings++;
				t.leniencies.computeIfAbsent(d.code(), k -> new TreeSet<>()).add(path);
				t.samples.putIfAbsent(d.code(), d.message());
			}
		}
	}

	private static String where(List<SourceLine> lineMap, String path, int line) {
		if (line >= 1 && line <= lineMap.size()) {
			SourceLine o = lineMap.get(line - 1);
			return o.path() + ":" + o.line();
		}
		return path + ":" + line;
	}

	private static String location(List<SourceLine> lineMap, String path, SemaDiagnostic d) {
		if (d.origin() != null) {
			return d.origin().path() + ":" + d.origin().line();
		}
		return where(lineMap, path, d.pos().line());
	}

	private static void report(Tally t) {
		System.out.println("[corpus] " + t.name + ": stages vertex=" + t.vertex + " fragment=" + t.fragment
				+ " compute=" + t.compute + " disabled=" + t.disabled + " unrecognised=" + t.unrecognised
				+ ", errors=" + t.errors.size() + ", warnings=" + t.warnings + " " + kinds(t) + ", " + t.elapsedMs
				+ " ms");
		for (Finding f : t.errors) {
			Known k = knownFor(f);
			System.out.println((k == null ? "[corpus-error] " : (k.packSide() ? "[corpus-pack-side] " : "[corpus-engine-gap] "))
					+ f.text() + (k == null ? "" : " :: " + k.reason()));
		}
		for (Map.Entry<String, Set<String>> e : t.leniencies.entrySet()) {
			System.out.println("[corpus-leniency] " + t.name + " " + e.getKey() + " files=" + e.getValue() + " e.g. "
					+ t.samples.get(e.getKey()));
		}
	}

	private static String kinds(Tally t) {
		Map<String, Integer> counts = new TreeMap<>();
		for (Map.Entry<String, Set<String>> e : t.leniencies.entrySet()) {
			counts.put(e.getKey(), e.getValue().size());
		}
		return counts.toString();
	}
}
