// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.standardroad;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.ColorTarget;
import dev.mcms.glsl.msl.ProgramRequest.TextureBinding;
import dev.mcms.glsl.msl.ProgramRequest.TextureKind;
import dev.mcms.glsl.msl.ProgramRequest.UniformBlock;
import dev.mcms.glsl.msl.ProgramRequest.UniformField;
import dev.mcms.glsl.msl.ProgramRequest.UniformSchema;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.sema.Stage;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.translator.CorpusTranslatorTest;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.StandardMacros;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The E2 corpus differential (ADR 0006 section 4): every full-screen program (deferred*, composite*, final, prepare*) of
 * Complementary Reimagined (the bench's options) and of BSL (defaults) renders through our translator's MSL and through the
 * standard road, with the same synthetic inputs: every sampled texture filled with fixed random data, every engine uniform
 * from a fixed seed, the full-screen quad at 640x360. Every output target must agree within the tolerance written in
 * results/2026-11-13-e2-translator/README.md (the W4-B section).
 *
 * <p>The macros, the options, the enabled programs and the dimension folders come from W2-A's {@link PackModel}, loaded
 * exactly as W3-B's corpus run loads them ({@link CorpusTranslatorTest#loadModel} and {@link CorpusTranslatorTest#programsOf}):
 * one source. Only the uniform schema is the differential's own: it appends the pack uniforms the packs read
 * (lib/uniforms.glsl) to the frame block, so both roads read the same random values instead of both reading zero.
 *
 * <p>Runs with {@code -Pcorpus=<folder with the pack zips> -PgpuTests}. The packs are test inputs and are never committed.
 */
@Tag("corpus")
@Tag("gpu")
final class CorpusDifferentialTest {

	/**
	 * The programs the standard road refuses or disagrees with, by id. Empty: every full-screen program of both packs
	 * agrees within the tolerance. The three causes of the earlier outliers are fixed in the printer: a swizzle of a shadow
	 * lookup (legacy shadow2D returns vec4), a pack global named like an engine block member, and a local named like a Metal
	 * keyword ('new'), now printed under the u_ prefix.
	 */
	static final List<String> OUTLIERS = List.of();

	/**
	 * A colour target the program writes as one constant on both roads at the measured options, with the constant it
	 * must write and the reason: a constant target is no differential evidence, so the trivial-agreement guard fails it
	 * unless it is listed here, and a listed entry whose constant changes (both roads writing a different constant
	 * still agrees) fails too — the pin is the evidence that the guard saw this exact value.
	 */
	record AllowedConstant(String pack, String config, String program, int attachment, float[] value, String reason) {
	}

	/** Every constant target of the measured run, with its pinned constant and its cause (verified against the preprocessed sources). */
	static final List<AllowedConstant> ALLOWED_CONSTANT = List.of(
		// Complementary's composite writes reflectOutput, which starts vec4(0); the screen-space-reflection assignment
		// is behind runtime smoothness and depth guards, and no pixel of the synthetic frame passes them.
		new AllowedConstant("ComplementaryReimagined", "bench", "world-1/composite", 0, new float[] {0, 0, 0, 0},
			"reflectOutput stays its initial vec4(0): the reflection assignment is behind runtime guards"),
		new AllowedConstant("ComplementaryReimagined", "bench", "world0/composite", 0, new float[] {0, 0, 0, 0},
			"reflectOutput stays its initial vec4(0): the reflection assignment is behind runtime guards"),
		new AllowedConstant("ComplementaryReimagined", "bench", "world1/composite", 0, new float[] {0, 0, 0, 0},
			"reflectOutput stays its initial vec4(0): the reflection assignment is behind runtime guards"),
		// The VERY_HIGH profile (FXAA and the colored-lighting volumes on): the same reflectOutput constant in the same
		// three composites, the same reason.
		new AllowedConstant("ComplementaryReimagined", "veryhigh", "world-1/composite", 0, new float[] {0, 0, 0, 0},
			"reflectOutput stays its initial vec4(0): the reflection assignment is behind runtime guards"),
		new AllowedConstant("ComplementaryReimagined", "veryhigh", "world0/composite", 0, new float[] {0, 0, 0, 0},
			"reflectOutput stays its initial vec4(0): the reflection assignment is behind runtime guards"),
		new AllowedConstant("ComplementaryReimagined", "veryhigh", "world1/composite", 0, new float[] {0, 0, 0, 0},
			"reflectOutput stays its initial vec4(0): the reflection assignment is behind runtime guards"),
		// The same constant as the bench's world1/composite1 target 1 (vlFactorM), with the same reason.
		new AllowedConstant("ComplementaryReimagined", "veryhigh", "world1/composite1", 1,
			new float[] {0, 0, 0, 0.57254905f},
			"vlFactorM copies the flat varying vlFactor, one value for the full-screen triangle"),
		// The End variant of composite1 writes vec4(0,0,0,vlFactorM); vlFactorM copies the flat varying vlFactor,
		// one value for the whole full-screen triangle.
		new AllowedConstant("ComplementaryReimagined", "bench", "world1/composite1", 1,
			new float[] {0, 0, 0, 0.57254905f},
			"vlFactorM copies the flat varying vlFactor, one value for the full-screen triangle"),
		// BSL's Nether composite writes vec4(vl, 1) to colortex1; in the Nether the march's shadow accumulation is
		// compiled out (#ifndef NETHER) and MULTICOLORED_BLOCKLIGHT is off at the defaults, so vl is vec3(0). The
		// overworld and End variants carried constants only while the harness served a random inverse projection (a
		// depth unmapped through it is never in [near, far], so the march stopped at its first sample); the harness
		// now serves the game-domain perspective pair and both carry real values.
		new AllowedConstant("BSL", "defaults", "world-1/composite", 1, new float[] {0, 0, 0, 1},
			"vl is vec3(0): the Nether march accumulates nothing (the shadow accumulation is #ifndef NETHER)"),
		// The Nether and End variants of BSL's deferred1 write vec4(cloudViewLength, 0, 0, 1) to colortex4; the cloud
		// ray march is compiled out there and cloudViewLength is cloudMaxDistance divided by itself (1.0).
		new AllowedConstant("BSL", "defaults", "world-1/deferred1", 1, new float[] {1, 0, 0, 1},
			"cloudViewLength is 1.0: the cloud ray march is compiled out in the Nether and End"),
		new AllowedConstant("BSL", "defaults", "world1/deferred1", 1, new float[] {1, 0, 0, 1},
			"cloudViewLength is 1.0: the cloud ray march is compiled out in the Nether and End"));

	/**
	 * A program whose NaN placement the two roads leave to the implementation: GLSL leaves pow of a negative base undefined,
	 * and composite1.glsl:318 (`color = pow(color, vec3(2.2))`) gets one on random input in some texels, where the translator's
	 * MSL and the standard road's MSL return NaN in different texels. Evidence (2026-11-15): with the base clamped at zero in a
	 * copy of the pack (`pow(max(color, vec3(0.0)), vec3(2.2))`) all three programs agree in every texel. Such a program passes
	 * when every texel that is finite on both roads agrees within the tolerance, and at most {@link #PINNED_NAN_FRACTION} of
	 * the texels are NaN on one road only.
	 */
	record UndefinedNaN(String pack, String config, String program, String reason) {
	}

	static final double PINNED_NAN_FRACTION = 0.001;

	static final List<UndefinedNaN> UNDEFINED_NAN = List.of(
		new UndefinedNaN("ComplementaryReimagined", "veryhigh", "world-1/composite1",
			"pow(color, 2.2) at composite1.glsl:318 gets a negative base on random input; GLSL leaves pow of a negative base undefined"),
		new UndefinedNaN("ComplementaryReimagined", "veryhigh", "world0/composite1",
			"pow(color, 2.2) at composite1.glsl:318 gets a negative base on random input; GLSL leaves pow of a negative base undefined"),
		new UndefinedNaN("ComplementaryReimagined", "veryhigh", "world1/composite1",
			"pow(color, 2.2) at composite1.glsl:318 gets a negative base on random input; GLSL leaves pow of a negative base undefined"));

	/** The pinned NaN entry of one program, or null when its NaN placement must agree. */
	private static UndefinedNaN undefinedNaN(String id) {
		for (UndefinedNaN u : UNDEFINED_NAN) {
			if (id.startsWith(u.pack()) && id.contains(" [" + u.config() + "] ") && id.endsWith(" " + u.program())) {
				return u;
			}
		}
		return null;
	}

	/**
	 * The verdict of a pinned program's target: {@code [texels finite on both roads that fail, texels NaN on one road only]}.
	 * A texel is judged by a one-texel differential, so the tolerance is the one of the format.
	 */
	static long[] pinnedCounts(int format, byte[] ours, byte[] theirs) {
		int bpp = DifferentialGpu.bytesPerPixel(format);
		long finiteFailures = 0, oneSided = 0;
		for (int p = 0; p < ours.length / bpp; p++) {
			byte[] x = java.util.Arrays.copyOfRange(ours, p * bpp, (p + 1) * bpp);
			byte[] y = java.util.Arrays.copyOfRange(theirs, p * bpp, (p + 1) * bpp);
			if (DifferentialDiff.of(format, x, y).okPixels() == 1) {
				continue;
			}
			boolean one = false;
			for (int c = 0; c < DifferentialGpu.channels(format); c++) {
				float a = DifferentialGpu.component(format, x, c), b = DifferentialGpu.component(format, y, c);
				one |= Float.isFinite(a) != Float.isFinite(b);
			}
			if (one) {
				oneSided++;
			} else {
				finiteFailures++;
			}
		}
		return new long[] {finiteFailures, oneSided};
	}

	/** The entry of a constant target of one program, or null when it is unexplained. */
	private static AllowedConstant allowedConstant(String id, int attachment) {
		for (AllowedConstant a : ALLOWED_CONSTANT) {
			if (id.startsWith(a.pack()) && id.contains(" [" + a.config() + "] ") && id.endsWith(" " + a.program())
				&& a.attachment() == attachment) {
				return a;
			}
		}
		return null;
	}

	/** Whether a measured constant equals its pinned constant, channel by channel within the format's tolerance. */
	private static boolean constantMatches(int format, float[] measured, float[] pinned) {
		for (int c = 0; c < DifferentialGpu.channels(format); c++) {
			float a = measured[c], b = pinned[c];
			if (!Float.isFinite(a) || !Float.isFinite(b) || Float.compare(a, b) != 0 && !withinFormat(format, c, a, b)) {
				return false;
			}
		}
		return true;
	}

	private static boolean withinFormat(int format, int c, float a, float b) {
		double diff = Math.abs((double) a - b);
		if (format == DifferentialGpu.RG11B10_FLOAT) {
			return diff <= DifferentialDiff.packedFloatStep(c, Math.max(Math.abs((double) a), Math.abs(b)));
		}
		double step = DifferentialDiff.codeValue(format, c);
		return diff <= (step > 0 ? step + 1e-9
			: DifferentialDiff.FLOAT_REL * Math.max(Math.abs(a), Math.abs(b)) + DifferentialDiff.FLOAT_FLOOR);
	}


	/** The preprocessed sources of a program with an unexplained constant target, one file per stage, under tmp. */
	private static void dumpSources(ProgramRequest request, String id) {
		try {
			Path dir = Path.of("build", "e2-diff-src");
			Files.createDirectories(dir);
			String base = id.replaceAll("[^A-Za-z0-9_.-]", "_");
			for (ProgramRequest.StageSource s : request.stages()) {
				Files.writeString(dir.resolve(base + "." + s.stage().name().toLowerCase(java.util.Locale.ROOT)
					+ ".glsl"), s.text());
			}
		} catch (IOException e) {
			// the dump is evidence for a human; the verdict stands without it
		}
	}

	static final int W = 640, H = 360;
	private static final int FRAME_SLOT = dev.mcms.shaders.runtime.EngineSlots.FRAME, DRAW_SLOT = dev.mcms.shaders.runtime.EngineSlots.DRAW;
	private static final String[] PACKS = {"ComplementaryReimagined_r5.9.3.zip", "BSL_v10.1.8.zip"};

	/** The engine's blocks of the corpus run (the layouts W3-B's E2 harness uses, plus the pack uniforms below). */
	static final UniformSchema SCHEMA = new UniformSchema(List.of(
		new UniformBlock("QsFrame", FRAME_SLOT, std140(
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
			"atlasSize", "ivec2",
			// Pack uniforms the game supplies that the packs read (lib/uniforms.glsl). Served from this block, so the
			// roads read the same random values; left out, both roads would read zero (frameTimeSmooth is a divisor).
			"frameTimeSmooth", "float", "timeAngle", "float", "timeBrightness", "float", "shadowFade", "float",
			"cloudHeight", "float", "blindFactor", "float", "darknessFactor", "float", "rainFactor", "float",
			"bedrockLevel", "int", "moonPhase", "int", "heavyFog", "bool", "isElytraFlying", "bool")),
		new UniformBlock("QsDraw", DRAW_SLOT, std140(
			"modelViewMatrix", "mat4", "modelViewMatrixInverse", "mat4", "projectionMatrix", "mat4",
			"projectionMatrixInverse", "mat4", "textureMatrix", "mat4", "normalMatrix", "mat3",
			"entityColor", "vec4", "entityId", "int", "blockEntityId", "int", "blendFunc", "ivec4",
			"instanceId", "int", "renderStage", "int", "spriteBounds", "vec4", "colorModulator", "vec4",
			"modelOffset", "vec3", "chunkOffset", "vec3"))));

	/** Depth textures (depthtexN, shadowtexN and every shadow sampler) are Depth32Float. */
	private static final int DEPTH = DifferentialGpu.DEPTH32_FLOAT;
	/** A colour target's name: colortexN, or gauxN (OptiFine's alias of colortex N+3). */
	private static final Pattern TARGET_NAME = Pattern.compile("(colortex|gaux)(\\d+)");
	private static final Pattern DEPTH_NAME = Pattern.compile("(depthtex|shadowtex)\\d+");
	/** A pack's declared format of a colour target, such as {@code const int colortex0Format = RGBA16F;}. */
	private static final Pattern PACK_FORMAT =
		Pattern.compile("const\\s+int\\s+(colortex\\d+|gaux\\d+)Format\\s*=\\s*(\\w+)");

	/** The outcome of one program: its verdict line, and whether it is an agreement, an outlier or a failure. */
	private record Outcome(String id, String verdict, String kind) {
	}

	@Test
	void fullScreenProgramsOfComplementaryAndBslAgreeWithTheTranslator() throws IOException {
		Path folder = corpusFolder();
		Path optionsDir = folder.getParent().getParent().resolve("pack-options");
		List<String> failures = new ArrayList<>();
		Map<String, Integer> kinds = new TreeMap<>();
		try (DifferentialGpu gpu = new DifferentialGpu()) {
			for (String pack : PACKS) {
				Path zip = folder.resolve(pack);
				assumeTrue(Files.isRegularFile(zip), pack + " is not in " + folder);
				for (Map.Entry<String, Path> config : optionFiles(pack, optionsDir).entrySet()) {
					Properties options = null;
					if (config.getValue() != null) {
						options = new Properties();
						options.load(new StringReader(Files.readString(config.getValue())));
					}
					String label = config.getKey();
					PackModel model = CorpusTranslatorTest.loadModel(zip, StandardMacros.engine(), options);
					for (CorpusTranslatorTest.Program p : CorpusTranslatorTest.programsOf(model)) {
						String id = pack + " [" + label + "] " + p.name();
						if (p.error() != null) {
							if (isFullScreenName(p.base())) {
								String verdict = id + " | LOAD-FAIL " + p.error();
								System.out.println("[e2-diff] " + verdict);
								failures.add(verdict);
							}
							continue;
						}
						if (!isFullScreen(p)) {
							continue;
						}
						Outcome o = differential(gpu, requestFor(p, model), id, packTargetFormats(zip));
						kinds.merge(o.kind(), 1, Integer::sum);
						System.out.println("[e2-diff] " + o.verdict());
						if (!o.kind().equals("AGREE") && !OUTLIERS.contains(id)) {
							failures.add(o.verdict());
						}
					}
				}
			}
		}
		System.out.println("[e2-diff-total] " + kinds);
		int total = kinds.values().stream().mapToInt(Integer::intValue).sum();
		System.out.println("differential: " + kinds.getOrDefault("AGREE", 0) + "/" + total + " agree");
		assertTrue(failures.isEmpty(), "full-screen programs outside the E2 tolerance:\n" + String.join("\n", failures));
	}

	/**
	 * The option sets a pack's programs are run at, by label: Complementary at the bench's options (the file the bench
	 * runs) and at the VERY_HIGH profile (FXAA_DEFINE=1 enables composite7, which the bench options switch off); the other
	 * packs at their defaults (a null file). Each value is the options file, or null.
	 */
	static Map<String, Path> optionFiles(String pack, Path optionsDir) {
		Map<String, Path> out = new java.util.LinkedHashMap<>();
		if (pack.startsWith("Complementary")) {
			out.put("bench", optionsDir.resolve(pack + ".txt"));
			out.put("veryhigh", optionsDir.resolve("profile-veryhigh").resolve(pack + ".txt"));
		} else {
			out.put("defaults", null);
		}
		return out;
	}

	/** Renders the program both ways and returns the outcome. A harness error is a failure of the program. */
	private static Outcome differential(DifferentialGpu gpu, ProgramRequest request, String id,
			Map<Integer, Integer> packFormats) {
		try {
			return differentialOrThrow(gpu, request, id, packFormats);
		} catch (RuntimeException | StackOverflowError e) {
			StackTraceElement[] st = e.getStackTrace();
			String top = String.join(" < ", Arrays.stream(st, 0, Math.min(8, st.length)).map(Object::toString).toList());
			return new Outcome(id, id + " | HARNESS-FAIL " + e + " at " + top, "HARNESS-FAIL");
		}
	}

	private static Outcome differentialOrThrow(DifferentialGpu gpu, ProgramRequest request, String id,
			Map<Integer, Integer> packFormats) {
		if (!request.buffers().isEmpty() || !request.images().isEmpty()) {
			return new Outcome(id, id + " | SKIPPED pack blocks or images (not bound by the harness)", "SKIPPED");
		}
		for (TextureBinding t : request.textures()) {
			if (t.kind() != TextureKind.COLOUR && t.kind() != TextureKind.COMPARE && t.kind() != TextureKind.THREE_D
				&& t.kind() != TextureKind.INTEGER) {
				return new Outcome(id, id + " | SKIPPED texture kind " + t.kind() + " (not bound by the harness)",
					"SKIPPED");
			}
		}
		ProgramTranslation ours = Translator.translate(request);
		if (!ours.ok()) {
			return new Outcome(id, id + " | TRANSLATE-FAIL " + ours.diagnostics().get(0), "TRANSLATE-FAIL");
		}
		StandardRoad.Program std = StandardRoad.compile(request);
		if (!std.ok()) {
			return new Outcome(id, id + " | STANDARD-FAIL " + std.errors(), "STANDARD-FAIL");
		}
		int attachments = 0;
		for (ColorTarget c : request.outputs()) {
			attachments = Math.max(attachments, c.attachment() + 1);
		}
		int[] formats = new int[attachments];
		for (ColorTarget c : request.outputs()) {
			formats[c.attachment()] = targetFormat(packFormats, c.target());
		}
		Map<Integer, DifferentialGpu.Tex> textures = new HashMap<>();
		for (TextureBinding t : request.textures()) {
			textures.put(t.slot(), harnessTexture(t, packFormats));
		}
		Map<Integer, byte[]> blocks = Map.of(FRAME_SLOT, DifferentialGpu.frameBytes(SCHEMA, FRAME_SLOT, 17, W, H),
			DRAW_SLOT, DifferentialGpu.uniformBytes(SCHEMA, DRAW_SLOT, 19));
		byte[][] a;
		byte[][] b;
		try {
			a = gpu.renderFullScreen(new DifferentialGpu.Lib(ours.msl(), ours.vertexEntry(), ours.msl(),
				ours.fragmentEntry()), formats, W, H, textures, blocks);
		} catch (IllegalStateException e) {
			return new Outcome(id, id + " | METAL-FAIL translator: " + e.getMessage(), "METAL-FAIL");
		}
		try {
			StandardRoad.StageMsl v = std.stage(Stage.VERTEX), f = std.stage(Stage.FRAGMENT);
			b = gpu.renderFullScreen(new DifferentialGpu.Lib(v.msl(), v.entry(), f.msl(), f.entry()), formats, W, H,
				textures, blocks);
		} catch (IllegalStateException e) {
			return new Outcome(id, id + " | METAL-FAIL standard road: " + e.getMessage(), "METAL-FAIL");
		}
		StringBuilder sb = new StringBuilder();
		boolean all = true;
		record Measured(int attachment, int format, float[] value) {
		}
		List<Measured> constants = new ArrayList<>();
		for (ColorTarget c : request.outputs()) {
			int fmt = formats[c.attachment()];
			DifferentialDiff d = DifferentialDiff.of(fmt, a[c.attachment()], b[c.attachment()]);
			UndefinedNaN pinned = undefinedNaN(id);
			if (pinned != null) {
				long[] counts = pinnedCounts(fmt, a[c.attachment()], b[c.attachment()]);
				long pixels = d.pixels();
				boolean ok = counts[0] == 0 && counts[1] <= PINNED_NAN_FRACTION * pixels;
				all &= ok;
				sb.append(" target").append(c.attachment()).append(" PINNED (").append(pinned.reason()).append("): ")
					.append(counts[0]).append(" finite texels differ, ").append(counts[1]).append(" of ").append(pixels)
					.append(" texels NaN on one road only");
			} else {
				all &= d.passing() >= d.requiredPassing();
			}
			if (d.passing() < d.requiredPassing()) {
				sb.append(mismatches(fmt, a[c.attachment()], b[c.attachment()], 4));
			}
			boolean written = ours.reflection().outputs().stream().anyMatch(o -> o.attachment() == c.attachment());
			long oursDistinct = DifferentialDiff.distinctValues(fmt, a[c.attachment()]);
			long theirsDistinct = DifferentialDiff.distinctValues(fmt, b[c.attachment()]);
			sb.append(" target").append(c.attachment()).append(' ').append(d).append(", distinct ").append(oursDistinct)
				.append('/').append(theirsDistinct);
			if (!written) {
				sb.append(" (not written by the program)");
			} else if (oursDistinct == 1 && theirsDistinct == 1) {
				float[] value = new float[DifferentialGpu.channels(fmt)];
				StringBuilder text = new StringBuilder();
				for (int ch = 0; ch < value.length; ch++) {
					value[ch] = DifferentialGpu.component(fmt, a[c.attachment()], ch);
					text.append(ch == 0 ? "" : ",").append(value[ch]);
				}
				sb.append(" (constant ").append(text).append(')');
				constants.add(new Measured(c.attachment(), fmt, value));
			}
		}
		String trivial = null;
		for (Measured m : constants) {
			AllowedConstant allowed = allowedConstant(id, m.attachment());
			if (allowed == null) {
				trivial = (trivial == null ? "" : trivial + "; ") + "target" + m.attachment();
			} else if (!constantMatches(m.format(), m.value(), allowed.value())) {
				trivial = (trivial == null ? "" : trivial + "; ") + "target" + m.attachment() + " constant changed"
					+ " (pinned " + java.util.Arrays.toString(allowed.value()) + ", measured "
					+ java.util.Arrays.toString(m.value()) + ")";
			} else {
				sb.append(" target").append(m.attachment()).append(" constant allowed: ").append(allowed.reason());
			}
		}
		if (trivial != null) {
			dumpSources(request, id);
		}
		String kind = !all ? "DIFFER" : trivial != null ? "TRIVIAL" : "AGREE";
		return new Outcome(id, id + " | " + kind + sb, kind);

	}

	/**
	 * A texture the harness binds for a sampler: a 2D texture at the sampler's format, or a 3D one (Complementary's colored
	 * lighting volumes, declared in lib/uniforms.glsl whenever COLORED_LIGHTING is on, which a program may declare without
	 * reading). The integer volumes (voxel_sampler, wsr_sampler, wsr_lod_sampler) are usampler3D: an integer format of
	 * the sampler's type; floodfill_sampler and floodfill_sampler_copy are sampler3D. A 2D integer sampler (puddle_sampler,
	 * usampler2D) takes RGBA32UI; no colour target reads it.
	 */
	static DifferentialGpu.Tex harnessTexture(TextureBinding t, Map<Integer, Integer> packFormats) {
		long seed = t.name().hashCode();
		if (t.kind() == TextureKind.THREE_D) {
			boolean integer = t.name().startsWith("voxel") || t.name().startsWith("wsr");
			return DifferentialGpu.randomTexture(integer ? HeadlessGpu.RGBA32_UINT : DifferentialGpu.RGBA8_UNORM,
				DifferentialGpu.TYPE_3D, 4, 4, 4, seed);
		}
		if (t.kind() == TextureKind.INTEGER) {
			return DifferentialGpu.randomTexture(HeadlessGpu.RGBA32_UINT, DifferentialGpu.TYPE_2D, W, H, 1, seed);
		}
		return DifferentialGpu.randomTexture(textureFormat(t, packFormats), DifferentialGpu.TYPE_2D, W, H, 1, seed);
	}

	/**
	 * The first mismatching texels of a target, each with the channels of both roads (diagnosis of a DIFFER verdict): the
	 * texel index, then ours and theirs per channel.
	 */
	static String mismatches(int format, byte[] ours, byte[] theirs, int limit) {
		int bpp = DifferentialGpu.bytesPerPixel(format);
		int comps = DifferentialGpu.channels(format);
		StringBuilder out = new StringBuilder();
		int shown = 0;
		for (long p = 0; p < ours.length / bpp && shown < limit; p++) {
			StringBuilder row = new StringBuilder();
			boolean differs = false;
			for (int c = 0; c < comps; c++) {
				int index = (int) (p * comps + c);
				float x = DifferentialGpu.component(format, ours, index);
				float y = DifferentialGpu.component(format, theirs, index);
				row.append(" [").append(x).append(" | ").append(y).append(']');
				differs |= Float.compare(x, y) != 0 && !(Float.isFinite(x) && Float.isFinite(y)
					&& Math.abs((double) x - y) <= DifferentialDiff.FLOAT_REL * Math.max(Math.abs(x), Math.abs(y))
					+ DifferentialDiff.FLOAT_FLOOR);
			}
			if (differs) {
				out.append("\n      texel ").append(p).append(" (x ").append(p % W).append(", y ").append(p / W)
					.append("):").append(row);
				shown++;
			}
		}
		return out.toString();
	}

	/** The Metal format of a colour target: its pack format, or the default RGBA8 when the pack declares none. */
	static int targetFormat(Map<Integer, Integer> packFormats, int target) {
		return packFormats.getOrDefault(target, DifferentialGpu.RGBA8_UNORM);
	}

	/**
	 * The format of a sampled texture: depth for depth textures (depthtexN, shadowtexN and every shadow sampler), the pack
	 * format of the colour target it names, and RGBA8 for any other texture.
	 */
	static int textureFormat(TextureBinding t, Map<Integer, Integer> packFormats) {
		if (t.kind() == TextureKind.COMPARE || DEPTH_NAME.matcher(t.name()).matches()) {
			return DEPTH;
		}
		Matcher m = TARGET_NAME.matcher(t.name());
		if (m.matches()) {
			int n = Integer.parseInt(m.group(2));
			return targetFormat(packFormats, m.group(1).equals("gaux") ? n + 3 : n);
		}
		return DifferentialGpu.RGBA8_UNORM;
	}

	/**
	 * The pack's colour target formats, from the {@code const int colortexNFormat} and {@code gauxNFormat} declarations in
	 * every shader source of the pack (the first declaration in file-name order wins). gauxN is colortex N+3.
	 */
	static Map<Integer, Integer> packTargetFormats(Path zip) throws IOException {
		Map<Integer, Integer> out = new TreeMap<>();
		try (ZipFile z = new ZipFile(zip.toFile())) {
			List<String> names = z.stream().map(ZipEntry::getName)
				.filter(n -> n.startsWith("shaders/") && n.matches(".*\\.(glsl|fsh|vsh|inc)")).sorted().toList();
			for (String name : names) {
				String text = new String(z.getInputStream(z.getEntry(name)).readAllBytes(), StandardCharsets.UTF_8);
				Matcher m = PACK_FORMAT.matcher(text);
				while (m.find()) {
					String target = m.group(1);
					int n = target.startsWith("gaux") ? Integer.parseInt(target.substring(4)) + 3
						: Integer.parseInt(target.substring(8));
					out.putIfAbsent(n, DifferentialGpu.ofPackName(m.group(2)));
				}
			}
		}
		return out;
	}

	/**
	 * Load times at defaults: our translator's time (translate, Metal) against the standard road's (print, shaderc,
	 * SPIRV-Cross, Metal) per pack, all programs at defaults (the dh_ programs are not run at defaults). One warm-up pass
	 * without Metal; the measured pass compiles every MSL cold (the sources repeat between the passes, so the warm-up
	 * translates and prints only).
	 */
	@Test
	void loadTimesAtDefaults() throws IOException {
		Path folder = corpusFolder();
		List<Path> zips;
		try (Stream<Path> s = Files.list(folder)) {
			zips = s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".zip")).sorted().toList();
		}
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			for (Path zip : zips) {
				Map<String, Integer> causes = new TreeMap<>();
				measure(gpu, zip, false, causes); // warm-up: the first pass loads the classes and the shader compiler
				causes.clear();
				Timing t = measure(gpu, zip, true, causes);
				System.out.printf("[e2-load] %s: programs=%d (dh-not-run=%d, load-error=%d)"
					+ " | translator %.1f ms (%d refused) + Metal %.1f ms (%d refused)"
					+ " | standard road %.1f ms (print %.1f, shaderc %.1f, SPIRV-Cross %.1f; %d refused)"
					+ " + Metal %.1f ms (%d stage libraries refused)%n",
					zip.getFileName(), t.programs(), t.dh(), t.loadErrors(), t.translateNs() / 1e6, t.transFail(),
					t.metalNs() / 1e6, t.metalFail(), (t.printNs() + t.shadercNs() + t.spirvNs()) / 1e6,
					t.printNs() / 1e6, t.shadercNs() / 1e6, t.spirvNs() / 1e6, t.stdFail(), t.stdMetalNs() / 1e6,
					t.stdMetalFail());
				for (var e : causes.entrySet()) {
					System.out.println("[e2-load-refusal] " + zip.getFileName() + " x" + e.getValue() + ": " + e.getKey());
				}
			}
		}
	}

	/** The first line of Metal diagnostics, the line that names the error. */
	private static String firstLine(String diagnostics) {
		if (diagnostics == null) {
			return "no diagnostics";
		}
		int nl = diagnostics.indexOf('\n');
		return (nl < 0 ? diagnostics : diagnostics.substring(0, nl)).strip();
	}

	/** One pack's timings at defaults; times are summed nanoseconds over the programs. */
	private record Timing(int programs, int dh, int loadErrors, int transFail, int metalFail, int stdFail,
			int stdMetalFail, long translateNs, long metalNs, long printNs, long shadercNs, long spirvNs,
			long stdMetalNs) {
	}

	private static Timing measure(HeadlessGpu gpu, Path zip, boolean metal, Map<String, Integer> refusalCauses)
			throws IOException {
		long translateNs = 0, metalNs = 0, print = 0, shaderc = 0, spirvCross = 0, stdMetalNs = 0;
		int programs = 0, loadErrors = 0, dh = 0, transFail = 0, metalFail = 0, stdFail = 0, stdMetalFail = 0;
		PackModel model = CorpusTranslatorTest.loadModel(zip, StandardMacros.engine(), null);
		for (CorpusTranslatorTest.Program p : CorpusTranslatorTest.programsOf(model)) {
			if (p.error() != null) {
				loadErrors++;
				continue;
			}
			if (p.base().startsWith("dh_")) {
				dh++;
				continue;
			}
			ProgramRequest request = requestFor(p, model);
			ProgramTranslation t = Translator.translate(request);
			translateNs += t.translateNanos();
			if (!t.ok()) {
				transFail++;
			} else if (metal) {
				long c0 = System.nanoTime();
				if (!gpu.compile(t.msl()).ok()) {
					metalFail++;
				}
				metalNs += System.nanoTime() - c0;
			}
			StandardRoad.Program std = StandardRoad.compile(request);
			print += std.printNanos();
			shaderc += std.shadercNanos();
			spirvCross += std.spirvCrossNanos();
			if (!std.ok()) {
				stdFail++;
				refusalCauses.merge("road: " + std.errors().get(0), 1, Integer::sum);
			} else if (metal) {
				for (StandardRoad.StageMsl s : std.stages()) {
					long c0 = System.nanoTime();
					var cr = gpu.compile(s.msl());
					if (!cr.ok()) {
						stdMetalFail++;
						refusalCauses.merge("metal: " + firstLine(cr.diagnostics()), 1, Integer::sum);
					}
					stdMetalNs += System.nanoTime() - c0;
				}
			}
			programs++;
		}
		return new Timing(programs, dh, loadErrors, transFail, metalFail, stdFail, stdMetalFail, translateNs, metalNs,
			print, shaderc, spirvCross, stdMetalNs);
	}

	private static Path corpusFolder() {
		String dir = System.getProperty("mcms.test.corpus");
		assumeTrue(dir != null, "no corpus folder given");
		Path folder = Path.of(dir);
		assumeTrue(Files.isDirectory(folder), dir + " is not a folder");
		return folder;
	}

	/** A full-screen program: a fragment stage, no compute stage, and a documented name the game runs full-screen. */
	private static boolean isFullScreen(CorpusTranslatorTest.Program p) {
		return p.stages().stream().noneMatch(s -> s.stage() == Stage.COMPUTE)
			&& p.stages().stream().anyMatch(s -> s.stage() == Stage.FRAGMENT) && isFullScreenName(p.base());
	}

	private static boolean isFullScreenName(String base) {
		return base.startsWith("deferred") || base.startsWith("composite") || base.equals("final")
			|| base.startsWith("prepare");
	}

	/** Lays out named fields the way std140 does (the same rule as W3-B's harness). */
	static List<UniformField> std140(String... nameTypes) {
		List<UniformField> out = new ArrayList<>();
		long offset = 0;
		for (int i = 0; i < nameTypes.length; i += 2) {
			String type = nameTypes[i + 1];
			long size, align;
			switch (type) {
				case "float", "int", "uint", "bool" -> {
					size = 4;
					align = 4;
				}
				case "vec2", "ivec2", "uvec2" -> {
					size = 8;
					align = 8;
				}
				case "vec3", "ivec3", "uvec3" -> {
					size = 12;
					align = 16;
				}
				case "vec4", "ivec4", "uvec4" -> {
					size = 16;
					align = 16;
				}
				case "mat3" -> {
					size = 48;
					align = 16;
				}
				case "mat4" -> {
					size = 64;
					align = 16;
				}
				default -> throw new IllegalArgumentException("std140 type " + type);
			}
			offset = (offset + align - 1) / align * align;
			out.add(new UniformField(nameTypes[i], type, (int) offset));
			offset += size;
		}
		return out;
	}

	/**
	 * The differential's request of a corpus program: W3-B's corpus request (slots, layouts, targets, samplers) with the
	 * differential's schema swapped in, so the harness serves the pack uniforms the packs read from the frame block.
	 */
	static ProgramRequest requestFor(CorpusTranslatorTest.Program p, PackModel model) {
		ProgramRequest r = CorpusTranslatorTest.requestFor(p, model);
		return new ProgramRequest(r.name(), r.family(), r.stages(), r.vertexLayout(), r.outputs(), r.textures(),
			r.images(), r.buffers(), SCHEMA, r.alphaTest(), r.drawsPoints());
	}
}
