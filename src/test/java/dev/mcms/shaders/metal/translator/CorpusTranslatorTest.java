// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal.translator;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.BufferBinding;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.ImageBinding;
import dev.mcms.glsl.msl.ProgramRequest.SamplerState;
import dev.mcms.glsl.msl.ProgramRequest.Semantic;
import dev.mcms.glsl.msl.ProgramRequest.StageSource;
import dev.mcms.glsl.msl.ProgramRequest.TextureBinding;
import dev.mcms.glsl.msl.ProgramRequest.TextureKind;
import dev.mcms.glsl.msl.ProgramRequest.UniformBlock;
import dev.mcms.glsl.msl.ProgramRequest.UniformField;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.msl.ProgramRequest.VertexAttribute;
import dev.mcms.glsl.msl.ProgramRequest.VertexLayout;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.runtime.distant.DistantVertices;
import dev.mcms.shaders.pack.DimensionPrograms;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.ProgramDirectives;
import dev.mcms.shaders.pack.ResolvedProgram;
import dev.mcms.shaders.pack.ShaderProperties;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.pack.StageFile;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * E2 evidence (docs/engine/plan.md, E2): every program the game runs in some dimension of the eight packs is translated
 * and compiled by Metal headlessly. The macros, the options, the enabled programs and the dimension folders come from
 * W2-A's {@link PackModel}, loaded once per configuration. The configurations are the pack's defaults, a distant
 * horizons run (only the dh_ programs are counted there), the pack's bench options file (bench/pack-options/&lt;zip&gt;.txt)
 * and the profile folders the bench names (profile-high, profile-complementary). Each program prints one line. The test
 * fails for a program that neither translates nor compiles and is not listed in {@link #PACK_SIDE}.
 *
 * <p>Runs with {@code -Pcorpus=<folder with the pack zips> -PgpuTests}. The packs are test inputs and are never
 * committed.
 */
@Tag("corpus")
@Tag("gpu")
public final class CorpusTranslatorTest {

	/**
	 * A pack-side error: the pack's zip name, the configuration label, the program's name, a token of the verdict, and
	 * the evidence (a log line of an earlier run where Iris or Vitrail fails the same way). Only a failure shown to be
	 * pack-side by such a log line is listed here.
	 */
	record PackSide(String pack, String config, String program, String token, String evidence) {
	}

	/** The Complementary clouds shader at the bench options (CLOUD_STYLE_DEFINE=50 includes cloudColors.glsl). */
	private static final String CLOUDS_EVIDENCE = "pack-side at the bench options: lib/colors/cloudColors.glsl reads"
		+ " sky colours that lib/colors/skyColors.glsl declares only under #ifdef OVERWORLD, and the Nether and End"
		+ " programs define no OVERWORLD. Vitrail, run with the bench options: log: results/2026-10-26-pins-dims/"
		+ "metal-vitrail/run1/game-latest.log: \"vitrail:pack/3/fancy/world-1/gbuffers_clouds/fragment:2147: error:"
		+ " 'dayMiddleSkyColor' : undeclared identifier\". Iris, the same pack and options: log: results/2026-10-11-"
		+ "m0c-dims/gl-iris/run1/game-latest.log: \"Shader compilation log for clouds: ERROR: 0:418: Use of undeclared"
		+ " identifier 'dayMiddleSkyColor'\".";

	static final List<PackSide> PACK_SIDE = List.of(
		new PackSide("ComplementaryReimagined", "bench", "world-1/gbuffers_clouds", "dayMiddleSkyColor",
			CLOUDS_EVIDENCE),
		new PackSide("ComplementaryReimagined", "bench", "world1/gbuffers_clouds", "dayMiddleSkyColor",
			CLOUDS_EVIDENCE),
		new PackSide("ComplementaryReimagined", "bench", "world-1/gbuffers_clouds", "nightMiddleSkyColor",
			CLOUDS_EVIDENCE),
		new PackSide("ComplementaryReimagined", "bench", "world1/gbuffers_clouds", "nightMiddleSkyColor",
			CLOUDS_EVIDENCE));

	private static final Pattern RESOURCE_DECL = Pattern.compile(
		"(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+(?:layout\\s*\\([^)]*\\)\\s*)?(?:(?:highp|mediump|lowp)\\s+)?"
			+ "((?:i|u)?sampler\\w+|(?:i|u)?image\\w+)\\s+([\\w\\s,]+?)\\s*[;\\[]");
	private static final Pattern BLOCK_DECL = Pattern.compile("\\b(buffer|uniform)\\s+(\\w+)\\s*\\{");
	private static final Pattern COLORTEX = Pattern.compile("colortex(\\d+)|gcolor|gaux(\\d)");

	/** Slots of the engine's blocks; the pack's own blocks take slots from 0 in declaration order. */
	static final int FRAME_SLOT = dev.mcms.shaders.runtime.EngineSlots.FRAME;
	static final int DRAW_SLOT = dev.mcms.shaders.runtime.EngineSlots.DRAW;
	static final int CUSTOM_SLOT = dev.mcms.shaders.runtime.EngineSlots.CUSTOM;
	static final int DH_SLOT = 19; // E7 adds its block slot to EngineSlots; 19 is the top pack slot here

	/** Vertex layout of the game's entities and blocks (the vanilla vertex format). */
	static final VertexLayout ENTITY = VertexLayout.of(
		new VertexAttribute(Semantic.POSITION, "float3", 0, 0),
		new VertexAttribute(Semantic.COLOR, "uchar4Normalized", 0, 12),
		new VertexAttribute(Semantic.TEXCOORD0, "float2", 0, 16),
		new VertexAttribute(Semantic.OVERLAY, "short2", 0, 24),
		new VertexAttribute(Semantic.LIGHTMAP, "short2", 0, 28),
		new VertexAttribute(Semantic.NORMAL, "char4Normalized", 0, 32));

	/** The extended Sodium terrain layout: the entity layout plus block id, mid texcoord, tangent and block centre. */
	static final VertexLayout TERRAIN = VertexLayout.of(
		new VertexAttribute(Semantic.POSITION, "float3", 0, 0),
		new VertexAttribute(Semantic.COLOR, "uchar4Normalized", 0, 12),
		new VertexAttribute(Semantic.TEXCOORD0, "float2", 0, 16),
		new VertexAttribute(Semantic.LIGHTMAP, "short2", 0, 24),
		new VertexAttribute(Semantic.NORMAL, "char4Normalized", 0, 28),
		new VertexAttribute(Semantic.ENTITY, "short3", 0, 32),
		new VertexAttribute(Semantic.MID_TEXCOORD, "float2", 0, 40),
		new VertexAttribute(Semantic.TANGENT, "char4Normalized", 0, 48),
		new VertexAttribute(Semantic.MID_BLOCK, "uchar4", 0, 52),
		new VertexAttribute(Semantic.VELOCITY, "float3", 0, 56));

	/**
	 * Distant Horizons terrain layout: DH's 16-byte record as the engine serves it (DistantVertices, javap of DH 3.3.4).
	 */
	static final VertexLayout DISTANT_HORIZONS = DistantVertices.layout();

	/** One configuration of a pack: its label, the options file's values (null for the defaults), and DH on or off. */
	record Config(String label, Properties options, boolean distantHorizons) {
	}

	/**
	 * One program the game runs: its name as the pack folder and name ("world-1/gbuffers_clouds"), its documented base
	 * name, its stages, the colour targets its fragment stage writes (from the model's directives), and a preprocessing
	 * or model error (null when the program has stages).
	 */
	public record Program(String name, String base, List<StageSource> stages, List<Integer> writes, String error) {
	}

	@Test
	void everyCorpusProgramTranslatesAndCompiles() throws IOException {
		String dir = System.getProperty("mcms.test.corpus");
		assumeTrue(dir != null, "no corpus folder given");
		Path folder = Path.of(dir);
		assumeTrue(Files.isDirectory(folder), dir + " is not a folder");
		Path optionsDir = folder.getParent().getParent().resolve("pack-options");
		List<Path> zips;
		try (Stream<Path> s = Files.list(folder)) {
			zips = s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".zip")).sorted().toList();
		}
		assumeTrue(!zips.isEmpty(), "no pack zips in " + dir);
		List<String> failures = new ArrayList<>();
		int[] totals = new int[2];
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			for (Path zip : zips) {
				String pack = zip.getFileName().toString();
				for (Config config : configsOf(zip, optionsDir)) {
					Tally tally = new Tally();
					runConfig(gpu, pack, zip, config, tally, failures);
					totals[0] += tally.programs;
					totals[1] += tally.passed;
					System.out.println("[e2-pack] " + pack + " [" + config.label() + "]: programs=" + tally.programs
						+ " ok=" + tally.passed + " translate-fail=" + tally.translateFail + " metal-fail="
						+ tally.metalFail + " translate-ms=" + ms(tally.translateNs) + " metal-compile-ms="
						+ ms(tally.compileNs) + " model-problems=" + tally.modelProblems);
				}
			}
		}
		System.out.println("[e2-total] programs=" + totals[0] + " passed=" + totals[1] + " failed=" + failures.size());
		assertTrue(failures.isEmpty(), "programs neither translated nor compiled and not pack-side:\n"
			+ String.join("\n", failures));
	}

	/** Milliseconds with one decimal, from nanoseconds summed over the programs (no per-program truncation). */
	static String ms(long ns) {
		return String.format(java.util.Locale.ROOT, "%.1f", ns / 1_000_000.0);
	}

	/** Per configuration counts and times; times are summed in nanoseconds. */
	private static final class Tally {
		int programs;
		int passed;
		int translateFail;
		int metalFail;
		int modelProblems;
		long translateNs;
		long compileNs;
	}

	private static void runConfig(HeadlessGpu gpu, String pack, Path zip, Config config, Tally tally,
			List<String> failures) throws IOException {
		StandardMacros macros = config.distantHorizons()
			? new StandardMacros(StandardMacros.ENGINE_FEATURES, true) : StandardMacros.engine();
		PackModel model = loadModel(zip, macros, config.options());
		tally.modelProblems = model.problems().size();
		for (String problem : model.problems()) {
			System.out.println("[e2-model] " + pack + " [" + config.label() + "]: " + problem);
		}
		for (Program p : programsOf(model)) {
			if (config.distantHorizons() != p.base().startsWith("dh_")) {
				continue;
			}
			tally.programs++;
			String verdict;
			ProgramRequest request = p.error() == null ? requestFor(p, model) : null;
			long t0 = System.nanoTime();
			if (p.error() != null) {
				verdict = "TRANSLATE-FAIL preprocess " + p.error();
				tally.translateFail++;
			} else {
				ProgramTranslation t = Translator.translate(request);
				tally.translateNs += System.nanoTime() - t0;
				if (!t.ok()) {
					verdict = "TRANSLATE-FAIL " + summarize(t.diagnostics());
					dumpSource(pack, config, p);
					tally.translateFail++;
				} else {
					long c0 = System.nanoTime();
					HeadlessGpu.CompileResult r = gpu.compile(t.msl());
					tally.compileNs += System.nanoTime() - c0;
					if (r.ok()) {
						verdict = "OK";
						tally.passed++;
					} else {
						verdict = "METAL-FAIL " + errorLine(r.diagnostics());
						tally.metalFail++;
						dump(pack, config, p, t.msl());
					}
				}
			}
			long elapsed = (System.nanoTime() - t0) / 1_000_000;
			boolean packSide = isPackSide(pack, config.label(), p.name(), verdict);
			String id = pack + " [" + config.label() + "] " + p.name();
			if (!verdict.equals("OK") && !packSide) {
				failures.add(id + ": " + verdict);
			}
			System.out.println("[e2] " + id + " | " + (packSide ? "PACK-SIDE " : "") + verdict + " | " + elapsed
				+ " ms");
		}
	}

	/** Loads the pack at one configuration: the model reads the options, the profiles and the programs itself. */
	public static PackModel loadModel(Path zip, StandardMacros macros, Properties options) throws IOException {
		try (PackSource source = PackSource.open(zip)) {
			return PackModel.load(source, macros, "en_us", options);
		}
	}

	/**
	 * The programs the game runs in some dimension of the pack: each DIRECT program of each dimension's shader folder,
	 * once per folder and name. Fallbacks, switched-off and absent programs are not counted; the base folder counts only
	 * when a dimension uses it.
	 */
	public static List<Program> programsOf(PackModel model) {
		Map<String, Program> out = new TreeMap<>();
		for (DimensionPrograms dim : model.dimensions()) {
			if (dim.folder().isEmpty()) {
				continue;
			}
			String folder = dim.folder().get();
			for (ResolvedProgram rp : dim.programs()) {
				if (rp.source() != ResolvedProgram.Source.DIRECT) {
					continue;
				}
				String name = folder.isEmpty() ? rp.name() : folder + "/" + rp.name();
				for (Program p : programsFrom(name, rp)) {
					out.putIfAbsent(p.name(), p);
				}
			}
		}
		return List.copyOf(out.values());
	}

	/**
	 * The programs one resolved program is: its graphics stages and its compute stage. Iris runs a compute stage as its
	 * own program (a .csh beside the .vsh and .fsh of the same name), and the translator takes one compute stage per
	 * request, so the compute stage is named "name.csh" when graphics stages share the name.
	 */
	private static List<Program> programsFrom(String name, ResolvedProgram rp) {
		if (!rp.errors().isEmpty()) {
			return List.of(new Program(name, rp.name(), List.of(), List.of(), String.join("; ", rp.errors())));
		}
		List<StageSource> graphics = new ArrayList<>();
		List<StageSource> compute = new ArrayList<>();
		for (StageFile s : rp.stages()) {
			Stage stage = stageOf(s.stage());
			if (stage == null) {
				return List.of(new Program(name, rp.name(), List.of(), List.of(),
					"stage file " + s.path() + " is not a stage the translator takes"));
			}
			StageSource source = new StageSource(stage, s.text(), linesOf(s.path(), s.text()));
			(stage == Stage.COMPUTE ? compute : graphics).add(source);
		}
		List<Integer> writes = rp.directives().map(ProgramDirectives::writes).orElse(List.of());
		List<Program> out = new ArrayList<>();
		if (!graphics.isEmpty()) {
			out.add(new Program(name, rp.name(), graphics, writes, null));
		}
		if (!compute.isEmpty()) {
			out.add(new Program(graphics.isEmpty() ? name : name + ".csh", rp.name(), compute, List.of(), null));
		}
		return out;
	}

	private static Stage stageOf(char letter) {
		return switch (letter) {
			case 'v' -> Stage.VERTEX;
			case 'f' -> Stage.FRAGMENT;
			case 'c' -> Stage.COMPUTE;
			default -> null;
		};
	}

	/**
	 * Line numbers for the preprocessed text: the model keeps no line map per stage, so a diagnostic names the file and
	 * the line of the preprocessed text (the README says so).
	 */
	private static List<SourceLine> linesOf(String path, String text) {
		int n = text.split("\n", -1).length;
		return IntStream.rangeClosed(1, n).mapToObj(i -> new SourceLine(path, i)).toList();
	}

	/** The configurations one pack runs: defaults, distant horizons, the bench options and the profile folders. */
	static List<Config> configsOf(Path zip, Path optionsDir) throws IOException {
		List<Config> out = new ArrayList<>();
		out.add(new Config("defaults", null, false));
		out.add(new Config("defaults+dh", null, true));
		String name = zip.getFileName().toString();
		for (String sub : new String[] {"", "profile-high/", "profile-complementary/"}) {
			Path file = optionsDir.resolve(sub + name + ".txt");
			if (Files.isRegularFile(file)) {
				Properties options = new Properties();
				options.load(new StringReader(Files.readString(file)));
				String label = sub.isEmpty() ? "bench" : sub.substring(0, sub.length() - 1);
				out.add(new Config(label, options, false));
			}
		}
		return out;
	}

	private static void dump(String pack, Config config, Program p, String msl) throws IOException {
		Path dumpDir = Path.of(System.getProperty("java.io.tmpdir"), "e2-msl");
		Files.createDirectories(dumpDir);
		Files.writeString(dumpDir.resolve((pack + "_" + config.label() + "_" + p.name()).replaceAll(
			"[^A-Za-z0-9_.-]", "_") + ".metal"), msl);
	}

	/** The preprocessed text of a program that did not translate, one file per stage (the text the verdict is about). */
	private static void dumpSource(String pack, Config config, Program p) throws IOException {
		Path dumpDir = Path.of(System.getProperty("java.io.tmpdir"), "e2-src");
		Files.createDirectories(dumpDir);
		for (StageSource s : p.stages()) {
			String stage = s.stage().name().toLowerCase(java.util.Locale.ROOT);
			Files.writeString(dumpDir.resolve((pack + "_" + config.label() + "_" + p.name() + "." + stage + ".glsl")
				.replaceAll("[^A-Za-z0-9_.-]", "_")), s.text());
		}
	}

	/** Whether the text names the identifier as a whole word. */
	private static boolean mentions(String text, String identifier) {
		return Pattern.compile("\\b" + Pattern.quote(identifier) + "\\b").matcher(text).find();
	}

	/**
	 * Iris declares a custom image's sampler for the program (shaders.properties: image.name = sampler format ...), so a
	 * program names it without a declaration. The request declares it in each stage that names it, with the sampler type
	 * that the image's format gives.
	 */
	static List<StageSource> withCustomImageDeclarations(List<StageSource> stages, PackModel model) {
		String all = String.join("\n", stages.stream().map(StageSource::text).toList());
		Set<String> declared = new HashSet<>();
		Matcher m = RESOURCE_DECL.matcher(all);
		while (m.find()) {
			for (String name : m.group(2).split(",")) {
				declared.add(name.trim());
			}
		}
		List<StageSource> out = new ArrayList<>(stages);
		for (ShaderProperties.ImageBinding ib : model.properties().images()) {
			String sampler = ib.sampler();
			if (sampler == null || sampler.isEmpty() || declared.contains(sampler) || !mentions(all, sampler)) {
				continue;
			}
			declared.add(sampler);
			String prefix = !ib.format().contains("integer") ? "" : ib.pixelType().contains("unsigned") ? "u" : "i";
			String decl = "uniform " + prefix + "sampler" + (ib.z().isPresent() ? "3D" : "2D") + " " + sampler + ";";
			for (int i = 0; i < out.size(); i++) {
				StageSource s = out.get(i);
				if (mentions(s.text(), sampler)) {
					String text = insertDeclaration(s.text(), decl);
					String path = s.lineMap().get(0).path();
					out.set(i, new StageSource(s.stage(), text, linesOf(path, text)));
				}
			}
		}
		return out;
	}

	/** The text with a declaration after its #version line, or first when it has none. */
	private static String insertDeclaration(String text, String decl) {
		List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
		int at = 0;
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).trim().startsWith("#version")) {
				at = i + 1;
				break;
			}
		}
		lines.add(at, decl);
		return String.join("\n", lines);
	}

	private static boolean isPackSide(String pack, String config, String program, String verdict) {
		for (PackSide ps : PACK_SIDE) {
			if (pack.startsWith(ps.pack()) && config.equals(ps.config()) && program.equals(ps.program())
					&& verdict.contains(ps.token())) {
				return true;
			}
		}
		return false;
	}

	private static String summarize(List<ProgramTranslation.Diagnostic> ds) {
		StringBuilder sb = new StringBuilder();
		for (ProgramTranslation.Diagnostic d : ds) {
			if (d.severity() == ProgramTranslation.Diagnostic.Severity.ERROR) {
				if (sb.length() > 0) {
					sb.append("; ");
				}
				sb.append(d.code()).append(' ').append(d.origin() == null ? "" : d.origin().path() + ":"
					+ d.origin().line() + " ").append(d.message());
				if (sb.length() > 400) {
					break;
				}
			}
		}
		return sb.toString();
	}

	/** The first Metal error line (warnings are not failures); the first line when no error line is present. */
	private static String errorLine(String s) {
		if (s == null) {
			return "";
		}
		for (String line : s.split("\n")) {
			if (line.contains("error:")) {
				return line.length() > 300 ? line.substring(0, 300) : line;
			}
		}
		int nl = s.indexOf('\n');
		return nl < 0 ? s : s.substring(0, nl);
	}

	/** The engine's frame block (engine-pack-format 10), std140 offsets. */
	static final UniformBlock FRAME_BLOCK = new UniformBlock("QsFrame", FRAME_SLOT, std140(
		"fogColor", "vec3", "skyColor", "vec3", "fogStart", "float", "fogEnd", "float", "fogDensity", "float",
		"fogMode", "int", "worldTime", "int", "frameCounter", "int", "frameTimeCounter", "float",
		"frameTime", "float", "sunAngle", "float", "rainStrength", "float", "wetness", "float",
		"aspectRatio", "float", "viewWidth", "float", "viewHeight", "float", "near", "float", "far", "float",
		"alphaTestRef", "float", "centerDepthSmooth", "float", "cameraPosition", "vec3",
		"previousCameraPosition", "vec3", "sunPosition", "vec3", "moonPosition", "vec3",
		"shadowLightPosition", "vec3", "upPosition", "vec3", "eyeAltitude", "float",
		"gbufferModelView", "mat4", "gbufferModelViewInverse", "mat4", "gbufferProjection", "mat4",
		"gbufferProjectionInverse", "mat4", "gbufferPreviousModelView", "mat4",
		"gbufferPreviousProjection", "mat4", "shadowModelView", "mat4", "shadowModelViewInverse", "mat4",
		"shadowProjection", "mat4", "shadowProjectionInverse", "mat4", "eyeBrightness", "ivec2",
		"eyeBrightnessSmooth", "vec2", "isEyeInWater", "int", "nightVision", "float", "blindness", "float",
		"screenBrightness", "float", "heldItemId", "int", "heldBlockLightValue", "int", "hideGUI", "int",
		"atlasSize", "ivec2"));

	/** The engine's per-draw block (engine-pack-format 10), std140 offsets. */
	static final UniformBlock DRAW_BLOCK = new UniformBlock("QsDraw", DRAW_SLOT, std140(
		"modelViewMatrix", "mat4", "modelViewMatrixInverse", "mat4", "projectionMatrix", "mat4",
		"projectionMatrixInverse", "mat4", "textureMatrix", "mat4", "normalMatrix", "mat3",
		"entityColor", "vec4", "entityId", "int", "blockEntityId", "int", "blendFunc", "ivec4",
		"instanceId", "int", "renderStage", "int", "spriteBounds", "vec4", "colorModulator", "vec4",
		"modelOffset", "vec3", "chunkOffset", "vec3"));

	/** Distant Horizons uniforms (engine-pack-format 17), bound for the dh_ programs only. */
	static final UniformBlock DH_BLOCK = new UniformBlock("QsDh", DH_SLOT, std140(
		"dhNearPlane", "float", "dhFarPlane", "float", "dhRenderDistance", "int", "dhProjection", "mat4",
		"dhProjectionInverse", "mat4", "dhPreviousProjection", "mat4"));

	/**
	 * The uniform schema of one program: the frame and draw blocks, the pack's own uniform values in a block of their
	 * own (the model's {@code uniform.} lines, in file order; variables are not uniforms), and for dh_ programs the
	 * Distant Horizons block.
	 */
	static UniformSchema schemaFor(PackModel model, boolean distantHorizons) {
		List<UniformBlock> blocks = new ArrayList<>(List.of(FRAME_BLOCK, DRAW_BLOCK));
		List<String> custom = new ArrayList<>();
		for (PackModel.CustomValue cv : model.customValues()) {
			if (!cv.variable() && cv.type().isPresent()) {
				custom.add(cv.name());
				custom.add(cv.typeName());
			}
		}
		if (!custom.isEmpty()) {
			blocks.add(new UniformBlock("QsCustom", CUSTOM_SLOT, std140(custom.toArray(String[]::new))));
		}
		if (distantHorizons) {
			blocks.add(DH_BLOCK);
		}
		return new UniformSchema(blocks);
	}

	/** Lays out named fields the way std140 does: each field at its alignment, a vec3 takes 12 bytes. */
	static List<UniformField> std140(String... nameTypes) {
		List<UniformField> out = new ArrayList<>();
		long offset = 0;
		for (int i = 0; i < nameTypes.length; i += 2) {
			String type = nameTypes[i + 1];
			long[] sizeAlign = std140SizeAlign(type);
			offset = (offset + sizeAlign[1] - 1) / sizeAlign[1] * sizeAlign[1];
			out.add(new UniformField(nameTypes[i], type, (int) offset));
			offset += sizeAlign[0];
		}
		return out;
	}

	private static long[] std140SizeAlign(String type) {
		return switch (type) {
			case "float", "int", "uint", "bool" -> new long[] {4, 4};
			case "vec2", "ivec2", "uvec2" -> new long[] {8, 8};
			case "vec3", "ivec3", "uvec3" -> new long[] {12, 16};
			case "vec4", "ivec4", "uvec4" -> new long[] {16, 16};
			case "mat3" -> new long[] {48, 16};
			case "mat4" -> new long[] {64, 16};
			default -> throw new IllegalArgumentException("std140 type " + type);
		};
	}

	/**
	 * A representative request for a program: the family and the vertex layout from its documented name (folder
	 * prefixes are not part of it), the colour targets from the model's directives. Every sampler and image the program
	 * declares (in any declarator of any declaration) gets the next slot, so none is left unbound.
	 */
	public static ProgramRequest requestFor(Program p, PackModel model) {
		List<StageSource> stages = withCustomImageDeclarations(p.stages(), model);
		boolean compute = stages.stream().anyMatch(s -> s.stage() == Stage.COMPUTE);
		String all = String.join("\n", stages.stream().map(StageSource::text).toList());
		String base = p.base();
		boolean dh = base.startsWith("dh_");
		List<ColorTarget> outputs = new ArrayList<>();
		for (int i = 0; i < p.writes().size(); i++) {
			outputs.add(new ColorTarget(i, p.writes().get(i), 4));
		}
		List<TextureBinding> textures = new ArrayList<>();
		List<ImageBinding> images = new ArrayList<>();
		List<BufferBinding> buffers = new ArrayList<>();
		int slot = 0;
		Matcher sm = RESOURCE_DECL.matcher(all);
		Set<String> seen = new HashSet<>();
		while (sm.find()) {
			String type = sm.group(1);
			for (String declarator : sm.group(2).split(",")) {
				String name = declarator.trim();
				if (name.isEmpty() || !seen.add(name)) {
					continue;
				}
				if (type.contains("image")) {
					images.add(new ImageBinding(name, slot++));
					continue;
				}
				boolean shadow = type.endsWith("Shadow");
				TextureKind kind = shadow ? TextureKind.COMPARE : type.contains("3D") ? TextureKind.THREE_D
					: type.startsWith("isampler") || type.startsWith("usampler") ? TextureKind.INTEGER
					: type.contains("Buffer") ? TextureKind.BUFFER : type.contains("Cube") ? TextureKind.CUBE
					: type.contains("Array") ? TextureKind.ARRAY : TextureKind.COLOUR;
				int target = -1;
				Matcher cm = COLORTEX.matcher(name);
				if (cm.matches()) {
					target = cm.group(1) != null ? Integer.parseInt(cm.group(1)) : -1;
				}
				SamplerState state = new SamplerState(ProgramRequest.Filter.LINEAR, null,
					ProgramRequest.Wrap.CLAMP_TO_EDGE, shadow, type.contains("2DRect"));
				textures.add(new TextureBinding(name, slot++, kind, target, state, -1));
			}
		}
		Matcher bm = BLOCK_DECL.matcher(all);
		int blockSlot = 0;
		while (bm.find()) {
			if (buffers.stream().noneMatch(b -> b.name().equals(bm.group(2)))) {
				buffers.add(new BufferBinding(bm.group(2), blockSlot++));
			}
		}
		ProgramRequest.Family family = compute ? ProgramRequest.Family.COMPUTE
			: dh ? ProgramRequest.Family.DISTANT_HORIZONS
			: base.startsWith("gbuffers") ? ProgramRequest.Family.GBUFFERS
			: base.startsWith("shadow") ? ProgramRequest.Family.SHADOW
			: ProgramRequest.Family.FULL_SCREEN;
		VertexLayout layout = compute ? null : dh ? DISTANT_HORIZONS
			: base.contains("terrain") ? TERRAIN
			: base.startsWith("gbuffers") || base.startsWith("shadow") ? ENTITY
			: VertexLayout.fullScreenQuad();
		// The compatibility alpha test (engine-pack-format 12, 15): a compatibility gbuffers program (one that writes
		// gl_FragData or gl_FragColor) gets the pack's alphaTest.<program> when it sets one, else GREATER against the
		// engine's alphaTestRef (the corpus's choice of default, not a measured one). A program that sets it off has none.
		ShaderProperties.AlphaTest configured = model.properties().alphaTests().get(p.name());
		boolean compatibility = all.contains("gl_FragData") || all.contains("gl_FragColor");
		ProgramRequest.AlphaTest alpha = null;
		if (base.startsWith("gbuffers") && compatibility) {
			if (configured == null) {
				alpha = new ProgramRequest.AlphaTest("GREATER", "alphaTestRef");
			} else if (!configured.off()) {
				alpha = new ProgramRequest.AlphaTest(configured.func(), "alphaTestRef");
			}
		}
		// A program that writes gl_PointSize is drawn with points by the pipeline (the layout's point draws).
		boolean points = all.contains("gl_PointSize");
		return new ProgramRequest(p.name(), family, stages, layout, outputs, textures, images, buffers,
			schemaFor(model, dh), alpha, points);
	}
}
