// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The E1 evidence (docs/engine/plan.md, W2-A; ADR 0006, E1 row): the ten Metal configurations of
 * {@code results/2026-10-26-pins-dims}, each with its pack and options file (from bench/configs.json, following {@code extends}),
 * loaded by the pack model. Writes {@code report.json} and {@code report.md} to the folder of the {@code e1.report} property
 * (default {@code build/reports/e1}), with Vitrail's per-dimension counts from the same runs' logs beside ours.
 *
 * <p>Pass rule: each configuration loads without an exception; every program a dimension runs preprocesses without an
 * error; every program file of a dimension folder is either a documented program or listed by name as unrecognised; every
 * rule and custom value compiles. Runs with {@code -Pcorpus=<folder with the zips>}.
 */
@Tag("corpus")
class E1ReportTest {
	private static final String DATE = "2026-11-13";
	private static final List<String> CONFIGS = List.of("metal-vitrail", "metal-vitrail-high", "metal-vitrail-complementary",
		"metal-vitrail-bsl", "metal-vitrail-unbound", "metal-vitrail-solas", "metal-vitrail-bliss", "metal-vitrail-mellow",
		"metal-vitrail-makeup", "metal-vitrail-photon-bicubic");
	private static final String RUNS = "results/2026-10-26-pins-dims";
	private static final Pattern VITRAIL_DIM = Pattern.compile(
		"\\[pack\\]\\s+(\\S+): (\\d+) programs served directly, (\\d+) through a fallback, (\\d+) not served");
	private static final Pattern VITRAIL_OPTIONS = Pattern.compile(
		"\\[pack\\]\\s+options (\\d+): toggle (\\d+), value (\\d+), const (\\d+), commented out (\\d+), with a value list (\\d+)");
	private static final Pattern VITRAIL_UNRECOGNISED = Pattern.compile("\\[pack\\]\\s+unrecognised program names (\\d+): (.*)");
	private static final Pattern VITRAIL_RECOGNISED = Pattern.compile(
		"\\[pack\\]\\s+programs recognised (\\d+) across \\{(.*?)\\}, by stage .*?, (\\d+) distinct names");

	@Test
	void writesTheE1Report() throws IOException {
		Path root = repoRoot();
		Assumptions.assumeTrue(root != null, "bench/configs.json not found above the working folder");
		List<Path> zips = CorpusTest.corpusZips();
		JsonObject configs = JsonParser.parseString(Files.readString(root.resolve("bench/configs.json"))).getAsJsonObject()
			.getAsJsonObject("configs");
		Path out = Path.of(System.getProperty("e1.report", root.resolve("mod/mcms-metal/build/reports/e1").toString()));
		Files.createDirectories(out);
		Set<String> base = new TreeSet<>();
		for (ProgramCatalog.Program p : ProgramCatalog.base()) base.add(p.name());
		List<String> failures = new ArrayList<>();
		JsonObject report = new JsonObject();
		JsonArray list = new JsonArray();
		StringBuilder md = new StringBuilder();
		md.append("# E1 pack model report (").append(DATE).append(")\n\n");
		md.append("Ten Metal configurations of `").append(RUNS).append("`. Packs at the options of each configuration. ");
		md.append("Counts are over the ").append(base.size()).append(" non-numbered documented programs (`ProgramCatalog.base()`). ");
		md.append("Vitrail's logs count 39 per dimension; the names of those 39 are not in the logs (see the README).\n\n");
		for (String key : CONFIGS) {
			JsonObject cfg = resolve(configs, key);
			String zipName = cfg.get("shaderpack").getAsString();
			String optionsPath = cfg.get("packOptions").isJsonNull() ? null : cfg.get("packOptions").getAsString();
			Path zip = zips.stream().filter(z -> z.getFileName().toString().equals(zipName)).findFirst()
				.orElseThrow(() -> new AssertionError("pack " + zipName + " is not in the corpus folder"));
			Properties options = optionsPath == null ? new Properties() : readProperties(root.resolve(optionsPath));
			JsonObject entry = new JsonObject();
			entry.addProperty("config", key);
			entry.addProperty("label", cfg.get("label").getAsString());
			entry.addProperty("pack", zipName);
			entry.addProperty("optionsFile", optionsPath);
			try (PackSource pack = PackSource.open(zip)) {
				PackModel m = PackModel.load(pack, StandardMacros.engine(), "en_us", options);
				Summary.describe(pack, m, entry, failures, key);
				Map<String, VitrailRun> vitrail = VitrailRun.read(root.resolve(RUNS).resolve(key).resolve("run1/game-latest.log"));
				entry.add("vitrail", VitrailRun.toJson(vitrail));
				Markdown.config(md, key, m, vitrail, base);
				entry.add("comparison", Summary.comparison(m, vitrail, base));
			}
			list.add(entry);
		}
		report.addProperty("milestone", "E1");
		report.addProperty("date", DATE);
		report.add("configs", list);
		Files.writeString(out.resolve("report.json"), report.toString() + "\n", StandardCharsets.UTF_8);
		Files.writeString(out.resolve("report.md"), md.toString(), StandardCharsets.UTF_8);
		System.out.println("[e1] report written to " + out);
		assertTrue(failures.isEmpty(), failures.size() + " E1 failures:\n" + String.join("\n", failures));
	}

	/** The repository root: the nearest folder above the working folder that holds bench/configs.json. */
	private static Path repoRoot() {
		for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
			if (Files.isRegularFile(p.resolve("bench/configs.json"))) return p;
		}
		return null;
	}

	/** A configuration with the fields it inherits through {@code extends} filled in (the nearest definition wins). */
	private static JsonObject resolve(JsonObject configs, String key) {
		JsonObject out = new JsonObject();
		String cursor = key;
		Set<String> seen = new LinkedHashSet<>();
		while (cursor != null) {
			if (!seen.add(cursor)) throw new AssertionError("extends cycle at " + cursor);
			JsonObject c = configs.getAsJsonObject(cursor);
			for (String field : List.of("shaderpack", "packOptions")) {
				if (!out.has(field) && c.has(field)) out.add(field, c.get(field));
			}
			cursor = c.has("extends") ? c.get("extends").getAsString() : null;
		}
		out.addProperty("label", configs.getAsJsonObject(key).get("label").getAsString());
		if (!out.has("shaderpack")) throw new AssertionError(key + " names no shaderpack");
		if (!out.has("packOptions")) out.add("packOptions", com.google.gson.JsonNull.INSTANCE);
		return out;
	}

	private static Properties readProperties(Path file) throws IOException {
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			p.load(r);
		}
		return p;
	}

	/** Vitrail's counts for one run, read from its log. */
	record VitrailRun(Map<String, int[]> dimensions, String options, String recognised, List<String> unrecognised) {
		static Map<String, VitrailRun> read(Path log) throws IOException {
			Map<String, VitrailRun> out = new LinkedHashMap<>();
			if (!Files.isRegularFile(log)) return out;
			Map<String, int[]> dims = new LinkedHashMap<>();
			String options = null;
			String recognised = null;
			List<String> unrec = new ArrayList<>();
			for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
				Matcher d = VITRAIL_DIM.matcher(line);
				if (d.find()) {
					dims.putIfAbsent(d.group(1), new int[] {Integer.parseInt(d.group(2)), Integer.parseInt(d.group(3)),
						Integer.parseInt(d.group(4))});
					continue;
				}
				Matcher o = VITRAIL_OPTIONS.matcher(line);
				if (o.find() && options == null) options = o.group(0).replaceFirst("^\\[pack\\]\\s+", "");
				Matcher r = VITRAIL_RECOGNISED.matcher(line);
				if (r.find() && recognised == null) recognised = r.group(0).replaceFirst("^\\[pack\\]\\s+", "");
				Matcher u = VITRAIL_UNRECOGNISED.matcher(line);
				if (u.find() && unrec.isEmpty()) {
					for (String part : u.group(2).split(", ")) unrec.add(part.trim());
				}
			}
			if (!dims.isEmpty()) out.put("", new VitrailRun(dims, options, recognised, unrec));
			return out;
		}

		static JsonObject toJson(Map<String, VitrailRun> runs) {
			JsonObject o = new JsonObject();
			VitrailRun r = runs.get("");
			if (r == null) {
				o.addProperty("log", "missing");
				return o;
			}
			JsonObject dims = new JsonObject();
			for (Map.Entry<String, int[]> e : r.dimensions().entrySet()) {
				JsonObject d = new JsonObject();
				d.addProperty("served", e.getValue()[0]);
				d.addProperty("fallback", e.getValue()[1]);
				d.addProperty("notServed", e.getValue()[2]);
				dims.add(e.getKey(), d);
			}
			o.add("dimensions", dims);
			o.addProperty("options", r.options());
			o.addProperty("recognised", r.recognised());
			JsonArray unrec = new JsonArray();
			for (String u : r.unrecognised()) unrec.add(u);
			o.add("unrecognised", unrec);
			return o;
		}
	}

	/** The JSON of one configuration's model, and the checks that feed the pass rule. */
	static final class Summary {
		private Summary() {
		}

		static void describe(PackSource pack, PackModel m, JsonObject entry, List<String> failures, String key) {
			JsonObject options = new JsonObject();
			Map<OptionKind, Integer> byKind = m.options().countByKind();
			JsonObject names = new JsonObject();
			for (OptionKind k : OptionKind.values()) names.addProperty(k.name(), byKind.get(k));
			options.add("names", names);
			int decls = 0;
			int commented = 0;
			int ambiguous = 0;
			int withList = 0;
			for (PackOption o : m.options().options().values()) {
				decls += o.declarations().size();
				for (PackOption.Declaration d : o.declarations()) if (d.commented()) commented++;
				if (o.ambiguous()) ambiguous++;
				if (o.hasValueList()) withList++;
			}
			options.addProperty("declarations", decls);
			options.addProperty("commentedDeclarations", commented);
			options.addProperty("commentedNames", (int) m.options().options().values().stream()
				.filter(o -> o.declarations().stream().anyMatch(PackOption.Declaration::commented)).count());
			options.addProperty("ambiguous", ambiguous);
			options.addProperty("withValueList", withList);
			options.addProperty("hiddenConsts", (int) m.options().options().values().stream().filter(PackOption::hiddenConst).count());
			options.addProperty("scannedFiles", m.options().files().size());
			entry.add("options", options);
			JsonObject values = new JsonObject();
			values.addProperty("changedFromDefault", m.values().values().size() - countDefault(m));
			values.add("unknown", keys(m.values().unknown().keySet()));
			values.add("problems", strings(m.values().problems()));
			entry.add("optionValues", values);
			JsonArray profiles = new JsonArray();
			for (String name : m.profiles().names()) {
				ProfileSet.Resolved r = m.profiles().resolve(name);
				JsonObject p = new JsonObject();
				p.addProperty("name", name);
				p.addProperty("settings", r.settings().size());
				p.add("disabledPrograms", keys(r.disabledPrograms()));
				p.addProperty("current", m.currentProfile().map(c -> c.name().equals(name)).orElse(false));
				profiles.add(p);
			}
			entry.add("profiles", profiles);
			entry.addProperty("currentProfile", m.currentProfile().map(ProfileSet.Resolved::name).orElse("Custom"));
			JsonObject screens = new JsonObject();
			screens.addProperty("screens", m.screens().screens().size());
			screens.addProperty("items", m.screens().screens().values().stream().mapToInt(s -> s.items().size()).sum());
			screens.add("problems", strings(m.screens().problems()));
			for (String p : m.screens().problems()) {
				if (!p.contains("unknown option")) continue;
				String name = p.substring(p.lastIndexOf('"', p.length() - 2) + 1, p.length() - 1);
				if (mentionedOutsideLang(pack, name)) failures.add(key + " screens: " + p + " (the pack mentions the name; discovery missed it)");
			}
			entry.add("screens", screens);
			entry.addProperty("lang", m.lang().file().orElse(null));
			JsonArray rules = new JsonArray();
			for (PackModel.ProgramRule r : m.programRules()) {
				JsonObject o = new JsonObject();
				o.addProperty("key", r.key());
				o.addProperty("text", r.text());
				r.value().ifPresent(v -> o.addProperty("value", v));
				r.error().ifPresent(e -> {
					o.addProperty("error", e);
					failures.add(key + " program." + r.key() + ".enabled: " + e);
				});
				rules.add(o);
			}
			entry.add("programRules", rules);
			int uniforms = 0;
			int variables = 0;
			JsonArray customErrors = new JsonArray();
			for (PackModel.CustomValue c : m.customValues()) {
				if (c.variable()) variables++;
				else uniforms++;
				c.error().ifPresent(e -> {
					customErrors.add(c.typeName() + "." + c.name() + ": " + e);
					failures.add(key + " " + c.typeName() + "." + c.name() + ": " + e);
				});
			}
			JsonObject custom = new JsonObject();
			custom.addProperty("uniforms", uniforms);
			custom.addProperty("variables", variables);
			custom.add("errors", customErrors);
			entry.add("customValues", custom);
			JsonObject ids = new JsonObject();
			ids.addProperty("blockIds", m.blocks().ids().size());
			ids.addProperty("itemIds", m.items().ids().size());
			ids.addProperty("entityIds", m.entities().ids().size());
			ids.add("blockLayers", keys(m.blocks().layers().keySet()));
			ids.add("problems", strings(concat(m.blocks().problems(), m.items().problems(), m.entities().problems())));
			entry.add("idMaps", ids);
			JsonArray dims = new JsonArray();
			for (DimensionPrograms d : m.dimensions()) {
				dims.add(dimension(pack, d, failures, key));
			}
			entry.add("dimensions", dims);
			// The base folder is run only when the rule uses it, so its errors inform the report and do not fail it.
			entry.add("rootFolder", dimension(pack, m.rootFolder(), new ArrayList<>(), key));
			JsonObject unrec = new JsonObject();
			for (var e : m.unrecognisedByFolder().entrySet()) unrec.add(e.getKey().isEmpty() ? "(root)" : e.getKey(), keys(e.getValue()));
			entry.add("unrecognisedByFolder", unrec);
			entry.add("problems", strings(m.problems()));
			entry.add("warnings", strings(m.warnings()));
		}

		/**
		 * Whether a name the screens list as unknown is declared in the pack: some file other than lang/ and shaders.properties
		 * mentions it as a word. A name no file mentions is the pack's fault (Vitrail's log says so for Photon: "which the pack
		 * does not declare"); a name a file mentions is one our option discovery should have found.
		 */
		static boolean mentionedOutsideLang(PackSource pack, String name) {
			Pattern word = Pattern.compile("\\b" + Pattern.quote(name) + "\\b");
			Deque<String> dirs = new ArrayDeque<>(List.of(""));
			while (!dirs.isEmpty()) {
				String dir = dirs.poll();
				for (String entry : pack.list(dir)) {
					String path = dir.isEmpty() ? entry : dir + "/" + entry;
					if (entry.endsWith("/")) {
						dirs.add(path.substring(0, path.length() - 1));
						continue;
					}
					if (path.startsWith("lang/") || path.equals("shaders.properties")) continue;
					if (pack.readText(path).filter(t -> word.matcher(t).find()).isPresent()) return true;
				}
			}
			return false;
		}

		private static int countDefault(PackModel m) {
			int n = 0;
			for (PackOption o : m.options().options().values()) {
				if (m.values().get(o.name()) != null && m.values().get(o.name()).equals(o.defaultValue())) n++;
			}
			return n;
		}

		private static JsonObject dimension(PackSource pack, DimensionPrograms d, List<String> failures, String key) {
			JsonObject o = new JsonObject();
			o.addProperty("label", d.label());
			o.addProperty("folder", d.folder().orElse(null));
			o.addProperty("reason", d.reason());
			o.addProperty("direct", d.count(ResolvedProgram.Source.DIRECT));
			o.addProperty("fallback", d.count(ResolvedProgram.Source.FALLBACK));
			o.addProperty("disabled", d.count(ResolvedProgram.Source.DISABLED));
			o.addProperty("virtual", d.count(ResolvedProgram.Source.VIRTUAL));
			JsonArray programs = new JsonArray();
			for (ResolvedProgram p : d.programs()) {
				JsonObject po = new JsonObject();
				po.addProperty("name", p.name());
				po.addProperty("source", p.source().name());
				p.servedBy().ifPresent(s -> po.addProperty("servedBy", s));
				p.disabledBy().ifPresent(s -> po.addProperty("disabledBy", s));
				if (!p.errors().isEmpty()) {
					po.add("errors", strings(p.errors()));
					if (p.runs()) failures.add(key + " " + d.label() + " " + p.name() + ": " + p.errors().get(0));
				}
				p.directives().ifPresent(dir -> {
					po.addProperty("writeSource", dir.writeSource());
					po.add("writes", ints(dir.writes()));
					po.add("reads", ints(dir.reads()));
					dir.largestTarget().ifPresent(l -> po.addProperty("largestTarget", l));
				});
				programs.add(po);
			}
			o.add("programs", programs);
			o.add("unrecognised", keys(d.unrecognised()));
			JsonObject phases = new JsonObject();
			phases.add("opaque", ints(d.targets(ProgramCatalog.Phase.OPAQUE)));
			phases.add("translucent", ints(d.targets(ProgramCatalog.Phase.TRANSLUCENT)));
			o.add("targets", phases);
			d.largestTarget().ifPresent(l -> o.addProperty("largestTarget", l));
			checkPartition(pack, d, failures, key);
			return o;
		}

		/**
		 * Every stage file of the folder is either matched to a documented program or listed by name as unrecognised, and
		 * never both.
		 */
		private static void checkPartition(PackSource pack, DimensionPrograms d, List<String> failures, String key) {
			if (d.folder().isEmpty()) return;
			String folder = d.folder().get();
			for (String entry : pack.list(folder)) {
				if (entry.endsWith("/") || !OptionDiscovery.isStageFile(entry)) continue;
				int dot = entry.lastIndexOf('.');
				boolean matched = ProgramCatalog.match(entry.substring(0, dot), entry.substring(dot + 1)).isPresent();
				boolean listed = d.unrecognised().contains(entry);
				if (matched == listed) {
					failures.add(key + " " + d.label() + ": " + entry + (matched ? " is matched and listed" : " is neither matched nor listed"));
				}
			}
		}

		static JsonObject comparison(PackModel m, Map<String, VitrailRun> vitrail, Set<String> base) {
			JsonObject out = new JsonObject();
			VitrailRun v = vitrail.get("");
			if (v == null) return out;
			JsonArray rows = new JsonArray();
			for (var e : v.dimensions().entrySet()) {
				String vName = e.getKey();
				DimensionPrograms ours = matchRow(m, vName);
				JsonObject row = new JsonObject();
				row.addProperty("vitrailDimension", vName);
				row.addProperty("ourRow", ours == null ? null : ours.label());
				row.addProperty("vitrailServed", e.getValue()[0]);
				row.addProperty("vitrailFallback", e.getValue()[1]);
				row.addProperty("vitrailNotServed", e.getValue()[2]);
				if (ours != null) {
					int[] c = base39(ours, base);
					row.addProperty("ourDirect", c[0]);
					row.addProperty("ourFallback", c[1]);
					row.addProperty("ourNotServed", c[2]);
					row.addProperty("differenceDirect", e.getValue()[0] - c[0]);
					row.addProperty("differenceFallback", e.getValue()[1] - c[1]);
					row.addProperty("differenceNotServed", e.getValue()[2] - c[2]);
				}
				rows.add(row);
			}
			out.add("rows", rows);
			return out;
		}

		/** The row of ours a Vitrail row names: "(root)" is the base folder row, any other name is its folder's row. */
		static DimensionPrograms matchRow(PackModel m, String vitrailName) {
			if (vitrailName.equals("(root)")) return m.rootFolder();
			for (DimensionPrograms d : m.dimensions()) {
				if (d.folder().isPresent() && d.folder().get().equals(vitrailName)) return d;
			}
			return null;
		}

		/** Direct, fallback and not-served counts over the 39 base programs; not served includes absent, off and virtual. */
		static int[] base39(DimensionPrograms d, Set<String> base) {
			int direct = 0;
			int fallback = 0;
			for (ResolvedProgram p : d.programs()) {
				if (!base.contains(p.name())) continue;
				if (p.source() == ResolvedProgram.Source.DIRECT) direct++;
				if (p.source() == ResolvedProgram.Source.FALLBACK) fallback++;
			}
			return new int[] {direct, fallback, base.size() - direct - fallback};
		}

		static JsonArray strings(List<String> values) {
			JsonArray a = new JsonArray();
			for (String s : values) a.add(s);
			return a;
		}

		static JsonArray keys(Iterable<String> values) {
			JsonArray a = new JsonArray();
			for (String s : values) a.add(s);
			return a;
		}

		static JsonArray ints(Iterable<Integer> values) {
			JsonArray a = new JsonArray();
			for (int i : values) a.add(i);
			return a;
		}

		@SafeVarargs
		private static List<String> concat(List<String>... lists) {
			List<String> out = new ArrayList<>();
			for (List<String> l : lists) out.addAll(l);
			return out;
		}
	}

	/** The report's Markdown: one section per configuration. */
	static final class Markdown {
		private Markdown() {
		}

		static void config(StringBuilder md, String key, PackModel m, Map<String, VitrailRun> vitrail, Set<String> base) {
			md.append("## ").append(key).append(" (").append(m.name()).append(")\n\n");
			Map<OptionKind, Integer> byKind = m.options().countByKind();
			md.append("- Options: boolean ").append(byKind.get(OptionKind.BOOLEAN)).append(", value ")
				.append(byKind.get(OptionKind.VALUE)).append(", const ").append(byKind.get(OptionKind.CONST))
				.append("; ambiguous ").append(m.options().options().values().stream().filter(PackOption::ambiguous).count())
				.append(". Current profile: ").append(m.currentProfile().map(ProfileSet.Resolved::name).orElse("Custom"))
				.append(". Profiles: ").append(m.profiles().names().size()).append(", screens: ")
				.append(m.screens().screens().size()).append(".\n");
			md.append("- Rules: ").append(m.programRules().size()).append(", custom uniforms: ")
				.append(m.customValues().stream().filter(c -> !c.variable()).count()).append(", variables: ")
				.append(m.customValues().stream().filter(PackModel.CustomValue::variable).count()).append(".\n");
			md.append("- Problems: ").append(m.problems().size()).append(" (see report.json); warnings: ")
				.append(m.warnings().size()).append(".\n\n");
			for (DimensionPrograms d : m.dimensions()) {
				md.append("### ").append(d.label()).append(" (folder ").append(d.folder().map(f -> f.isEmpty() ? "shaders/" : f)
					.orElse("none")).append(", ").append(d.reason()).append(")\n\n");
				md.append("| source | count | programs |\n| --- | --- | --- |\n");
				for (ResolvedProgram.Source s : ResolvedProgram.Source.values()) {
					List<String> names = new ArrayList<>();
					if (s == ResolvedProgram.Source.ABSENT) {
						// The model keeps absent programs out of the list: name the base programs it found no files for.
						for (ProgramCatalog.Program bp : ProgramCatalog.base()) {
							if (d.programs().stream().noneMatch(p -> p.name().equals(bp.name()))) names.add(bp.name());
						}
					}
					for (ResolvedProgram p : d.programs()) {
						if (p.source() != s) continue;
						String extra = p.servedBy().filter(sb -> !sb.equals(p.name())).map(sb -> " <- " + sb).orElse("");
						if (p.disabledBy().isPresent()) extra += " (off: " + p.disabledBy().get() + ")";
						names.add(p.name() + extra);
					}
					md.append("| ").append(s.name()).append(" | ").append(names.size()).append(" | ")
						.append(names.isEmpty() ? "-" : String.join(", ", names)).append(" |\n");
				}
				md.append("\nTargets: opaque ").append(d.targets(ProgramCatalog.Phase.OPAQUE)).append(", translucent ")
					.append(d.targets(ProgramCatalog.Phase.TRANSLUCENT)).append(", largest ")
					.append(d.largestTarget().isPresent() ? d.largestTarget().getAsInt() : -1).append(".\n\n");
				if (!d.unrecognised().isEmpty()) {
					md.append("Unrecognised (no documented program): ").append(String.join(", ", d.unrecognised())).append("\n\n");
				}
				VitrailRun v = vitrail.get("");
				Optional<int[]> vr = v == null ? Optional.empty()
					: Optional.ofNullable(v.dimensions().get(d.folder().isPresent() && d.folder().get().isEmpty() ? "(root)"
						: d.folder().orElse("")));
				int[] ours = Summary.base39(d, base);
				md.append("Base 39 (ours direct / fallback / not served): ").append(ours[0]).append(" / ").append(ours[1])
					.append(" / ").append(ours[2]);
				vr.ifPresent(x -> md.append(". Vitrail: ").append(x[0]).append(" / ").append(x[1]).append(" / ").append(x[2]));
				md.append("\n\n");
			}
		}
	}
}
