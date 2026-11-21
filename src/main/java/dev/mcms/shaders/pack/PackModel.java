// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

import dev.mcms.glsl.preprocess.Diagnostic;
import dev.mcms.glsl.preprocess.Mode;
import dev.mcms.glsl.preprocess.PreprocessException;
import dev.mcms.glsl.preprocess.PreprocessResult;
import dev.mcms.glsl.preprocess.Preprocessor;
import dev.mcms.glsl.preprocess.SourceText;
import dev.mcms.shaders.expr.CompiledExpr;
import dev.mcms.shaders.expr.ExprCompiler;
import dev.mcms.shaders.expr.ExprError;
import dev.mcms.shaders.expr.ExprEval;
import dev.mcms.shaders.expr.ExprFrame;
import dev.mcms.shaders.expr.ExprType;

/**
 * One pack loaded with a set of option values: the options, the typed {@code shaders.properties}, profiles, screens,
 * language labels, the compiled program rules and custom values, the id maps, and every dimension's resolved programs
 * (engine-pack-format.md sections 2 to 6, 13). Loading reads the pack once and preprocesses each file once; it allocates
 * nothing per frame.
 *
 * @param unrecognisedByFolder every shader folder with stage files, and the names of its files no documented program has
 * @param problems             every error the load found (unknown options, bad directives, rules that do not compile,
 *                             preprocessor errors of files a dimension runs)
 * @param warnings             non-fatal findings (ambiguous options, case-only file names, preprocessor warnings)
 */
public record PackModel(
	String name,
	StandardMacros macros,
	OptionCatalog options,
	OptionValues values,
	ShaderProperties properties,
	ProfileSet profiles,
	Optional<ProfileSet.Resolved> currentProfile,
	ScreenTree screens,
	Lang lang,
	List<ProgramRule> programRules,
	List<CustomValue> customValues,
	FrameLayout frameLayout,
	IdMapFile blocks,
	IdMapFile items,
	IdMapFile entities,
	DimensionRules dimensionRules,
	List<DimensionPrograms> dimensions,
	DimensionPrograms rootFolder,
	Map<String, List<String>> unrecognisedByFolder,
	List<String> problems,
	List<String> warnings,
	/** The pack's custom textures (E6-B), decoded at load; the render side creates their GPU textures per set. */
	dev.mcms.shaders.pack.textures.CustomTextureTable textures,
	/** The pack's shader storage buffers (E6-B), their sizes and initial files; the render side creates them per set. */
	dev.mcms.shaders.pack.buffers.StorageBufferTable buffers) {

	/**
	 * A {@code program.<name>.enabled} rule.
	 *
	 * @param key       the key as written after {@code program.} (qualified names keep their folder)
	 * @param dimension the folder the rule is qualified with, or null
	 * @param program   the program name without the folder
	 * @param value     the rule's value at the loaded options; empty when it does not compile
	 */
	public record ProgramRule(String key, String dimension, String program, String text, Optional<CompiledExpr> compiled,
		Optional<Boolean> value, Optional<String> error) {
	}

	/**
	 * A {@code uniform.<type>.<name>} or {@code variable.<type>.<name>} line.
	 *
	 * @param type     its declared type; empty when the type name is not one of the six
	 * @param compiled the compiled expression; empty when it does not compile
	 * @param outputSlot the frame slot the value is written to; empty when its type is not one of the six
	 */
	public record CustomValue(boolean variable, String typeName, String name, String text, Optional<ExprType> type,
		Optional<CompiledExpr> compiled, Optional<String> error, OptionalInt outputSlot) {
	}

	public PackModel {
		programRules = List.copyOf(programRules);
		customValues = List.copyOf(customValues);
		dimensions = List.copyOf(dimensions);
		unrecognisedByFolder = java.util.Collections.unmodifiableMap(new TreeMap<>(unrecognisedByFolder));
		problems = List.copyOf(problems);
		warnings = List.copyOf(warnings);
	}

	/**
	 * The required feature flags ({@code iris.features.required}) this run does not provide. A pack that requires one does not
	 * load (plan E6): the engine checks this before it activates the set.
	 */
	public List<String> unsupportedRequiredFeatures() {
		return properties.requiredFeatures().stream().filter(f -> !macros.offers(f)).toList();
	}

	/** Lightweight pack metadata for the options screen (skips shader preprocessing, id maps, and texture decoding). */
	public record OptionsData(
		OptionCatalog options,
		OptionValues values,
		ShaderProperties properties,
		ProfileSet profiles,
		ScreenTree screens,
		Lang lang) {
	}

	/** Loads a pack with the given option values (a values file's properties; empty for the defaults). */
	public static PackModel load(PackSource pack, StandardMacros macros, String langId, Properties overrides) {
		return new Loader(pack, macros, langId, overrides).run();
	}

	/** Loads only options, properties, profiles, screens, and language labels (fast path for the options screen). */
	public static OptionsData loadOptions(PackSource pack, StandardMacros macros, String langId, Properties overrides) {
		return new Loader(pack, macros, langId, overrides).runOptions();
	}

	/** One file of a dimension folder that is a stage of a documented program. */
	private record FileRef(char stage, String suffix, String path) {
	}

	/** A preprocessed file: its text, or the errors that stopped it. */
	private record Pre(String text, List<String> errors) {
	}


	private static char stageLetter(String extension) {
		return switch (extension) {
			case "vsh" -> 'v';
			case "gsh" -> 'g';
			case "fsh" -> 'f';
			case "csh" -> 'c';
			case "tcs" -> 't';
			default -> 'e';
		};
	}

	private static String extensionOf(String name) {
		int dot = name.lastIndexOf('.');
		return dot < 0 ? "" : name.substring(dot + 1);
	}

	private static String stemOf(String name) {
		int dot = name.lastIndexOf('.');
		return dot < 0 ? name : name.substring(0, dot);
	}

	/** Folder names directly under {@code shaders/}, without the trailing slash. */
	static Set<String> topFolders(PackSource pack) {
		Set<String> out = new java.util.TreeSet<>();
		for (String entry : pack.list("")) {
			if (entry.endsWith("/")) out.add(entry.substring(0, entry.length() - 1));
		}
		return out;
	}

	/**
	 * Whether the pack has a Distant Horizons program ({@code dh_terrain} or {@code dh_water}) in any folder of {@code shaders/}
	 * (plan.md E7). The engine reads this before the pack loads: the DISTANT_HORIZONS define and the engine's DH depth follow it.
	 */
	public static boolean declaresDistantPrograms(PackSource pack) {
		List<String> folders = new ArrayList<>(topFolders(pack));
		folders.add("");
		for (String folder : folders) {
			Map<String, List<FileRef>> byProgram = stageFiles(pack, folder, new ArrayList<>());
			if (byProgram.containsKey("dh_terrain") || byProgram.containsKey("dh_water")) {
				return true;
			}
		}
		return false;
	}

	/** The stage files of one folder, grouped by documented program; unmatched names go to {@code unrecognised}. */
	private static Map<String, List<FileRef>> stageFiles(PackSource pack, String folder, List<String> unrecognised) {
		Map<String, List<FileRef>> byProgram = new HashMap<>();
		for (String entry : pack.list(folder)) {
			if (entry.endsWith("/") || !OptionDiscovery.isStageFile(entry)) continue;
			String path = folder.isEmpty() ? entry : folder + "/" + entry;
			var match = ProgramCatalog.match(stemOf(entry), extensionOf(entry));
			if (match.isEmpty()) {
				unrecognised.add(entry);
				continue;
			}
			byProgram.computeIfAbsent(match.get().program().name(), k -> new ArrayList<>())
				.add(new FileRef(stageLetter(extensionOf(entry)), match.get().suffix(), path));
		}
		return byProgram;
	}

	private static Optional<ExprType> typeOf(String s) {
		return switch (s) {
			case "float" -> Optional.of(ExprType.FLOAT);
			case "int" -> Optional.of(ExprType.INT);
			case "bool" -> Optional.of(ExprType.BOOL);
			case "vec2" -> Optional.of(ExprType.VEC2);
			case "vec3" -> Optional.of(ExprType.VEC3);
			case "vec4" -> Optional.of(ExprType.VEC4);
			default -> Optional.empty();
		};
	}

	/** A rule's value: the expression evaluated once with no frame (rules read only options and macros). */
	private static boolean evaluate(CompiledExpr c) {
		ExprEval eval = new ExprEval(new ExprFrame(), c.vectorScratch());
		c.requireScratch(eval);
		return c.evalB(eval);
	}

	/** No key of a properties file takes its value expanded (the preprocessor's default). */
	private static final Predicate<String> NO_EXPANSION = key -> false;

	/**
	 * The shaders.properties keys whose values take the macros defined at their line: {@code size.buffer.*} names option
	 * values and {@code #define}s (Complementary: {@code size.buffer.colortex1 = REFLECTION_RES REFLECTION_RES}). Other
	 * values keep their names, since {@code screen.*} and {@code profile.*} lines name options.
	 */
	private static final Predicate<String> SIZE_BUFFER_KEYS = key -> key.startsWith("size.buffer.");

	/** The state of one load: the pack, the catalog, the values, the preprocessing cache and the findings. */
	private static final class Loader {
		private final PackSource pack;
		private StandardMacros macros;
		private final String langId;
		private final Properties overrides;
		private final List<String> problems = new ArrayList<>();
		private final List<String> warnings = new ArrayList<>();
		private final Map<String, Pre> cache = new HashMap<>();
		/** The logical lines of every file this load preprocesses: an include is split once, not once per program (E9). */
		private final Preprocessor.FileCache files = new Preprocessor.FileCache();
		private Map<String, String> glslMacros;
		private Map<String, String> propsMacros;
		/** The most reads of shaders.properties while its feature requests change (shaderProperties). */
		private static final int PROPERTIES_READS = 4;
		private OptionRewriter rewriter;
		private FrameLayout frameLayout;

		Loader(PackSource pack, StandardMacros macros, String langId, Properties overrides) {
			this.pack = pack;
			this.macros = macros;
			this.langId = langId;
			this.overrides = overrides == null ? new Properties() : overrides;
		}

		OptionsData runOptions() {
			Set<String> top = topFolders(pack);
			glslMacros = macros.predefined();
			DimensionRules dimRules = dimensionRules();
			List<String> scanFolders = new ArrayList<>(List.of(""));
			for (String f : top) {
				if (f.startsWith("world") || dimRules.folderDimensions().containsKey(f)) scanFolders.add(f);
			}
			OptionCatalog catalog = OptionDiscovery.scan(pack, scanFolders);
			OptionValues values = OptionValues.of(catalog, overrides);
			ShaderProperties props = shaderProperties(catalog, values);
			ProfileSet profiles = ProfileSet.parse(props.profiles(), catalog);
			ScreenTree screens = ScreenTree.parse(props.screens(), catalog);
			Lang lang = Lang.select(pack, langId);
			return new OptionsData(catalog, values, props, profiles, screens, lang);
		}

		PackModel run() {
			Set<String> top = topFolders(pack);
			glslMacros = macros.predefined();
			// dimension.properties is read before the feature requests are known, with the standard macros only: the option scan
			// needs its folder list first, and the requests come from shaders.properties, which reads the options. Its lines name
			// dimensions and folders, so an IRIS_FEATURE_ test in it sees the flag undefined. No pack of the corpus has one.
			DimensionRules dimRules = dimensionRules();
			// The base folder is always scanned: a dimension with no folder of its own runs from it (DimensionRules.folderFor),
			// so its options are read whatever worldN folders the pack has.
			List<String> scanFolders = new ArrayList<>(List.of(""));
			for (String f : top) {
				if (f.startsWith("world") || dimRules.folderDimensions().containsKey(f)) scanFolders.add(f);
			}
			OptionCatalog catalog = OptionDiscovery.scan(pack, scanFolders);
			warnings.addAll(catalog.warnings());
			OptionValues values = OptionValues.of(catalog, overrides);
			problems.addAll(values.problems());
			rewriter = new OptionRewriter(catalog, values);
			ShaderProperties props = shaderProperties(catalog, values);
			// The pack's feature flags decide the IRIS_FEATURE_ defines of the rest of the load (iris.features.optional and
			// required). shaderProperties read the file with the flags its own requests define.
			macros = macros.withRequestedFeatures(props.requestedFeatures());
			glslMacros = macros.predefined();
			propsMacros = withOptions(glslMacros, catalog, values);
			ProfileSet profiles = ProfileSet.parse(props.profiles(), catalog);
			Optional<ProfileSet.Resolved> current = profiles.detect(values);
			ScreenTree screens = ScreenTree.parse(props.screens(), catalog);
			Lang lang = Lang.select(pack, langId);
			List<ProgramRule> rules = programRules(props, catalog, values);
			List<CustomValue> custom = customValues(props, catalog, values);
			IdMapFile blocks = idMap("block");
			IdMapFile items = idMap("item");
			IdMapFile entities = idMap("entity");
			List<DimensionPrograms> dims = new ArrayList<>();
			for (DimensionRules.Row row : dimRules.rows(top)) {
				dims.add(resolveDimension(row, rules, current.orElse(null)));
			}
			Map<String, List<String>> unrecognised = new LinkedHashMap<>();
			for (String f : scanFoldersWithStages(top)) {
				List<String> names = new ArrayList<>();
				stageFiles(pack, f, names);
				unrecognised.put(f, names);
			}
			for (String profileName : profiles.names()) problems.addAll(profiles.resolve(profileName).problems());
			problems.addAll(screens.problems());
			problems.addAll(dimRules.problems());
			problems.addAll(blocks.problems());
			problems.addAll(items.problems());
			problems.addAll(entities.problems());
			problems.addAll(props.problems());
			for (ProgramRule r : rules) r.error().ifPresent(e -> problems.add("program." + r.key() + ".enabled: " + e));
			for (CustomValue c : custom) c.error().ifPresent(e -> problems.add(c.typeName() + "." + c.name() + ": " + e));
			DimensionPrograms rootFolder = resolveDimension(new DimensionRules.Row("shaders/ root", Optional.empty(), "",
				"the base folder, whether or not the dimension rule uses it"), rules, current.orElse(null));
			// E6-B: a texture the pack names that the engine cannot read is a warning, not a load problem (the sampler keeps
			// the engine's own source), so a pack with a missing texture still loads.
			dev.mcms.shaders.pack.textures.CustomTextureTable textures =
				dev.mcms.shaders.pack.textures.CustomTextureTable.of(props, pack);
			warnings.addAll(textures.problems());
			dev.mcms.shaders.pack.buffers.StorageBufferTable buffers =
				dev.mcms.shaders.pack.buffers.StorageBufferTable.of(props, pack);
			warnings.addAll(buffers.problems());
			return new PackModel(pack.name(), macros, catalog, values, props, profiles, current, screens, lang, rules, custom, frameLayout,
				blocks, items, entities, dimRules, dims, rootFolder, unrecognised, problems, warnings, textures, buffers);
		}

		/**
		 * The predefined macros plus the option values, for the properties files that read options: a flag or number the pack
		 * sets as an option is defined, as the option's value.
		 */
		private Map<String, String> withOptions(Map<String, String> base, OptionCatalog catalog, OptionValues values) {
			Map<String, String> out = new LinkedHashMap<>(base);
			for (PackOption o : catalog.options().values()) {
				if (o.kind() == OptionKind.BOOLEAN) {
					if (values.isOn(o.name())) out.put(o.name(), "");
				} else if (values.get(o.name()) != null) {
					out.put(o.name(), values.get(o.name()));
				}
			}
			return out;
		}

		/** The folders whose stage files the report lists: the root and every folder with stage files. */
		private List<String> scanFoldersWithStages(Set<String> top) {
			List<String> out = new ArrayList<>();
			out.add("");
			for (String f : top) {
				for (String e : pack.list(f)) {
					if (!e.endsWith("/") && OptionDiscovery.isStageFile(e)) {
						out.add(f);
						break;
					}
				}
			}
			return out;
		}

		private Pre preprocess(Mode mode, String path, Map<String, String> predefined, boolean useRewriter,
				Predicate<String> expandedKeys) {
			Optional<String> text = pack.readText(path);
			if (text.isEmpty()) return new Pre("", List.of());
			try {
				PreprocessResult r = new Preprocessor(mode, pack.includeResolver(), predefined, useRewriter ? rewriter : null,
					expandedKeys, files).process(new SourceText(path, text.get()));
				for (Diagnostic d : r.warnings()) warnings.add(d.toString());
				return new Pre(r.text(), List.of());
			} catch (PreprocessException e) {
				return new Pre("", e.diagnostics().stream().map(Diagnostic::toString).toList());
			}
		}

		private Pre glsl(String path) {
			return cache.computeIfAbsent(path, p -> preprocess(Mode.GLSL, p, glslMacros, true, NO_EXPANSION));
		}

		/**
		 * Reads shaders.properties with the feature macros its own iris.features.* lines request. The file tests the flags too
		 * (Complementary: #if defined IRIS_FEATURE_BLOCK_EMISSION_ATTRIBUTE around size.buffer lines), so the file is read again
		 * with the requests of the last read until they stop changing; at most PROPERTIES_READS reads. Only the last read's
		 * warnings and errors are kept.
		 */
		private ShaderProperties shaderProperties(OptionCatalog catalog, OptionValues values) {
			Set<String> requested = Set.of();
			for (int read = 1;; read++) {
				int mark = warnings.size();
				Map<String, String> defines = withOptions(macros.withRequestedFeatures(requested).predefined(), catalog, values);
				Pre pre = preprocess(Mode.PROPERTIES, "shaders.properties", defines, false, SIZE_BUFFER_KEYS);
				ShaderProperties props = ShaderProperties.from(PropertiesText.parse(pre.text()));
				Set<String> next = Set.copyOf(props.requestedFeatures());
				if (next.equals(requested)) {
					problems.addAll(pre.errors());
					return props;
				}
				if (read == PROPERTIES_READS) {
					problems.addAll(pre.errors());
					warnings.add("shaders.properties: its iris.features requests did not settle in " + PROPERTIES_READS
						+ " reads; the last read's values are used");
					return props;
				}
				warnings.subList(mark, warnings.size()).clear();
				requested = next;
			}
		}

		private DimensionRules dimensionRules() {
			Pre pre = preprocess(Mode.PROPERTIES, "dimension.properties", glslMacros, false, NO_EXPANSION);
			return DimensionRules.parse(PropertiesText.parse(pre.text()));
		}

		private IdMapFile idMap(String kind) {
			String path = kind + ".properties";
			// The values of the kind's own lines take the macros defined at their line (as size.buffer values do).
			Predicate<String> valueKeys = key -> key.startsWith(kind + ".") || (kind.equals("block") && key.startsWith("layer."));
			Pre pre = preprocess(Mode.PROPERTIES, path, propsMacros, false, valueKeys);
			problems.addAll(pre.errors());
			PropertiesText.Parsed parsed = PropertiesText.parse(pre.text());
			Set<String> defined = IdMapFile.definedNames(pack.readText(path).orElse(""));
			for (String name : IdMapFile.unexpanded(parsed, defined)) {
				warnings.add(path + ": \"" + name + "\" is a macro name left unexpanded in a value; it is defined below its line or in an inactive branch");
			}
			return IdMapFile.parse(kind, parsed);
		}

		private List<ProgramRule> programRules(ShaderProperties props, OptionCatalog catalog, OptionValues values) {
			PackScope scope = PackScope.forPrograms(macros, catalog, values);
			List<ProgramRule> out = new ArrayList<>();
			for (Map.Entry<String, String> e : props.programRules().entrySet()) {
				String key = e.getKey();
				String dimension = null;
				String program = key;
				int slash = key.lastIndexOf('/');
				if (slash >= 0) {
					dimension = key.substring(0, slash);
					program = key.substring(slash + 1);
				}
				try {
					CompiledExpr c = ExprCompiler.compile(e.getValue(), scope, ExprType.BOOL);
					out.add(new ProgramRule(key, dimension, program, e.getValue(), Optional.of(c),
						Optional.of(evaluate(c)), Optional.empty()));
				} catch (ExprError ex) {
					out.add(new ProgramRule(key, dimension, program, e.getValue(), Optional.empty(), Optional.empty(),
						Optional.of(ex.getMessage())));
				}
			}
			return out;
		}

		private List<CustomValue> customValues(ShaderProperties props, OptionCatalog catalog, OptionValues values) {
			PackScope scope = PackScope.forUniforms(macros, catalog, values);
			List<CustomValue> out = new ArrayList<>();
			for (ShaderProperties.CustomValueSource cv : props.customValues()) {
				Optional<ExprType> type = typeOf(cv.typeName());
				if (type.isEmpty()) {
					out.add(new CustomValue(cv.variable(), cv.typeName(), cv.name(), cv.expression(), Optional.empty(),
						Optional.empty(), Optional.of("type \"" + cv.typeName() + "\" is not float, int, bool, vec2, vec3 or vec4"),
						OptionalInt.empty()));
					continue;
				}
				// The expression compiles before its name is bound, so it cannot read its own value.
				Optional<CompiledExpr> compiled = Optional.empty();
				Optional<String> error = Optional.empty();
				try {
					compiled = Optional.of(ExprCompiler.compile(cv.expression(), scope, type.get()));
				} catch (ExprError ex) {
					error = Optional.of(ex.getMessage());
				}
				int slot = scope.frame(cv.name(), type.get());
				out.add(new CustomValue(cv.variable(), cv.typeName(), cv.name(), cv.expression(), type, compiled, error,
					OptionalInt.of(slot)));
			}
			frameLayout = scope.layout();
			return out;
		}

		private DimensionPrograms resolveDimension(DimensionRules.Row row, List<ProgramRule> rules, ProfileSet.Resolved profile) {
			if (row.folder() == null) {
				return new DimensionPrograms(row.label(), row.dimension(), Optional.empty(), row.reason(), List.of(), List.of());
			}
			String folder = row.folder();
			List<String> unrecognised = new ArrayList<>();
			Map<String, List<FileRef>> byProgram = stageFiles(pack, folder, unrecognised);
			Resolver resolver = new Resolver(folder, byProgram, rules, profile);
			List<ResolvedProgram> programs = new ArrayList<>();
			for (ProgramCatalog.Program p : ProgramCatalog.all()) {
				ResolvedProgram r = resolver.resolve(p.name(), 0);
				if (r.source() != ResolvedProgram.Source.ABSENT) programs.add(r);
			}
			return new DimensionPrograms(row.label(), row.dimension(), Optional.of(folder), row.reason(), programs, unrecognised);
		}

		/** Resolves programs of one dimension folder: the switch-off rules, the profile, then the fallback chain. */
		private final class Resolver {
			private final String folder;
			private final Map<String, List<FileRef>> byProgram;
			private final List<ProgramRule> rules;
			private final ProfileSet.Resolved profile;
			private final Map<String, ResolvedProgram> memo = new HashMap<>();

			Resolver(String folder, Map<String, List<FileRef>> byProgram, List<ProgramRule> rules, ProfileSet.Resolved profile) {
				this.folder = folder;
				this.byProgram = byProgram;
				this.rules = rules;
				this.profile = profile;
			}

			ResolvedProgram resolve(String name, int depth) {
				ResolvedProgram cached = memo.get(name);
				if (cached != null) return cached;
				ProgramCatalog.Program def = ProgramCatalog.lookup(name).orElseThrow();
				ResolvedProgram out;
				if (def.virtual()) {
					out = new ResolvedProgram(name, def, ResolvedProgram.Source.VIRTUAL, Optional.empty(), Optional.empty(),
						List.of(), List.of(), Optional.empty());
				} else {
					List<FileRef> own = byProgram.getOrDefault(name, List.of());
					Optional<String> disabled = disabledBy(name);
					if (!own.isEmpty() && disabled.isEmpty()) {
						out = direct(name, def, own);
					} else {
						ResolvedProgram fb = def.fallback() != null && depth < 16 ? resolve(def.fallback(), depth + 1) : null;
						if (fb != null && fb.runs()) {
							out = new ResolvedProgram(name, def, ResolvedProgram.Source.FALLBACK,
								Optional.of(fb.servedBy().orElse(fb.name())), disabled, fb.stages(), fb.errors(), fb.directives());
						} else if (!own.isEmpty()) {
							out = new ResolvedProgram(name, def, ResolvedProgram.Source.DISABLED, Optional.empty(), disabled,
								List.of(), List.of(), Optional.empty());
						} else {
							out = new ResolvedProgram(name, def, ResolvedProgram.Source.ABSENT, Optional.empty(), Optional.empty(),
								List.of(), List.of(), Optional.empty());
						}
					}
				}
				memo.put(name, out);
				return out;
			}

			/**
			 * The rule or profile that switches the program off in this dimension, if one does. Rules and profile entries name a
			 * program by its path under {@code shaders/}: a name with a folder ({@code program.world-1/name.enabled},
			 * {@code !program.world-1/name}) switches off that folder's program, and a name without one only the base folder's.
			 * Iris 1.11.7 runs world0/composite1 under {@code program.composite1.enabled=false} and under a current profile's
			 * {@code !program.composite1}, and switches it off for the world0/ forms (measured with the mcms-progenable test
			 * pack, E7). Bliss 2.1.2 relies on it: its {@code program.composite5.enabled=TAA_UPSCALING} leaves world0/composite5
			 * running.
			 */
			private Optional<String> disabledBy(String name) {
				String qualified = folder.isEmpty() ? null : folder;
				Optional<ProgramRule> rule = rules.stream()
					.filter(r -> r.program().equals(name) && Objects.equals(qualified, r.dimension())).findFirst();
				if (rule.isPresent() && rule.get().value().isPresent() && !rule.get().value().get()) {
					return Optional.of("rule program." + rule.get().key() + ".enabled");
				}
				if (profile != null && profile.disabledPrograms().contains(folder.isEmpty() ? name : folder + "/" + name)) {
					return Optional.of("profile " + profile.name());
				}
				return Optional.empty();
			}

			private ResolvedProgram direct(String name, ProgramCatalog.Program def, List<FileRef> own) {
				List<StageFile> stages = new ArrayList<>();
				List<String> errors = new ArrayList<>();
				List<FileRef> ordered = new ArrayList<>(own);
				ordered.sort(Comparator.comparingInt((FileRef f) -> "vgfcte".indexOf(f.stage()))
					.thenComparing(FileRef::suffix));
				for (FileRef f : ordered) {
					Pre pre = glsl(f.path());
					if (!pre.errors().isEmpty()) {
						errors.addAll(pre.errors());
					} else {
						stages.add(new StageFile(f.stage(), f.path(), pre.text()));
					}
				}
				Optional<ProgramDirectives> directives = errors.isEmpty() && !stages.isEmpty()
					? Optional.of(ProgramDirectives.of(stages)) : Optional.empty();
				return new ResolvedProgram(name, def, ResolvedProgram.Source.DIRECT, Optional.of(name), Optional.empty(),
					stages, errors, directives);
			}
		}
	}
}
