// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.sema.Stage;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.translator.CorpusTranslatorTest;
import dev.mcms.shaders.pack.DimensionPrograms;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.ProgramCatalog;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.compute.ComputeKernel;
import dev.mcms.shaders.runtime.gbuffers.GbufferPlans;
import dev.mcms.shaders.runtime.gbuffers.VertexLayoutRegistry;
import dev.mcms.shaders.runtime.terrain.TerrainLayout;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import java.io.IOException;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Complementary at its VERY_HIGH and COMPLEMENTARY profiles, in all three dimensions.
 *
 * <p>From the runtime's own builds ({@link DimensionSet#load}, which carries the gbuffers and shadow plans and the full-screen and
 * compute passes): every texture and image index a pass, a kernel or a plan's variant binds is below {@link #TEXTURE_LIMIT}, and a
 * clouds variant's indices avoid the CloudFaces slot; the plans' sampler slots per stage are below 16.
 *
 * <p>From the translated MSL of every program (its request fitted to Metal's table as the runtime fits it): each graphics stage's
 * sampler slots are exactly the {@code [[sampler(n)]]} indices its entry declares, in 0 to 15; a full-screen pass declares no
 * sampler; every texture index an entry declares is distinct and below the limit; each program compiles with Metal.
 *
 * <p>The largest texture index per kind goes to {@code build/reports/texture-index-<profile>.txt}. Reads the pack and its option
 * files: {@code -Pcorpus=<folder with the pack zips>}.
 */
@Tag("gpu")
@Tag("corpus")
class SamplerSlotsCorpusGpuTest {

	private static final String ZIP = "ComplementaryReimagined_r5.9.3.zip";
	private static final int RGBA8 = 70;
	/** Metal's texture argument table holds indices 0 to 30 (TextureIndexRangeGpuTest). */
	static final int TEXTURE_LIMIT = ProgramRequest.MAX_TEXTURE_INDICES;
	private static final Pattern SAMPLER = Pattern.compile("\\[\\[sampler\\((\\d+)\\)\\]\\]");
	private static final Pattern TEXTURE = Pattern.compile("\\[\\[texture\\((\\d+)\\)\\]\\]");

	@Test
	void veryHighProfileStaysInsideMetalsSlotsInEveryKind() throws IOException {
		check("profile-veryhigh");
	}

	@Test
	void complementaryProfileStaysInsideMetalsSlotsInEveryKind() throws IOException {
		check("profile-complementary");
	}

	private static void check(String profile) throws IOException {
		String corpus = System.getProperty("mcms.test.corpus");
		assumeTrue(corpus != null, "no corpus folder given");
		Path zip = Path.of(corpus, ZIP);
		assumeTrue(Files.isRegularFile(zip), zip + " is missing");
		Path optionFile = Path.of(corpus).getParent().getParent().resolve("pack-options").resolve(profile).resolve(ZIP + ".txt");
		assumeTrue(Files.isRegularFile(optionFile), optionFile + " is missing");
		Properties options = new Properties();
		options.load(Files.newBufferedReader(optionFile));
		PackModel model = CorpusTranslatorTest.loadModel(zip, StandardMacros.engine(), options);

		List<String> failures = new ArrayList<>();
		Map<String, Integer> maxIndex = new TreeMap<>();
		Map<String, String> maxWhere = new TreeMap<>();
		VertexLayoutRegistry.register(GbufferRoutingGpuTest.EXTENDED, TerrainLayout.layout(0), GbufferBuildSupport.TERRAIN_FACTS);
		try {
			checkTranslations(profile, model, failures, maxIndex, maxWhere);
			checkRuntimeBuilds(profile, model, failures, maxIndex, maxWhere);
		} finally {
			VertexLayoutRegistry.unregister(GbufferRoutingGpuTest.EXTENDED);
		}
		report(profile, maxIndex, maxWhere);
		assertTrue(failures.isEmpty(), String.join("\n", failures));
	}

	/** The MSL of every program, from its request fitted to Metal's table: sampler and texture indices per entry, and compiles. */
	private static void checkTranslations(String profile, PackModel model, List<String> failures, Map<String, Integer> maxIndex,
			Map<String, String> maxWhere) {
		int translated = 0;
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			for (CorpusTranslatorTest.Program p : CorpusTranslatorTest.programsOf(model)) {
				if (p.error() != null || p.base().startsWith("dh_")) {
					continue;
				}
				ProgramRequest request = ProgramRequests.fitTextureIndices(CorpusTranslatorTest.requestFor(p, model));
				if (request == null) {
					failures.add(profile + " " + p.name() + ": refused by the texture index fit (logged with its name)");
					continue;
				}
				ProgramTranslation t = Translator.translate(request);
				if (!t.ok()) {
					failures.add(profile + " " + p.name() + ": does not translate: " + t.diagnostics());
					continue;
				}
				translated++;
				HeadlessGpu.CompileResult compiled = gpu.compile(t.msl());
				if (!compiled.ok()) {
					failures.add(profile + " " + p.name() + ": Metal does not compile it: " + compiled.diagnostics());
				}
				checkStage(profile + " " + p.name(), Stage.VERTEX, t, failures);
				checkStage(profile + " " + p.name(), Stage.FRAGMENT, t, failures);
				if (request.family() == ProgramRequest.Family.FULL_SCREEN) {
					assertTrue(t.reflection().samplers().isEmpty(), profile + " " + p.name()
						+ ": a full-screen pass binds no sampler at runtime, so it must declare none: " + t.reflection().samplers());
				}
				for (String entry : entriesOf(t)) {
					int max = maxTextureIndex(profile + " " + p.name(), t.msl(), entry, failures);
					record(maxIndex, maxWhere, "MSL " + request.family(), max, profile + " " + p.name());
				}
			}
		}
		assertTrue(translated > 0, profile + ": no program translated");
	}

	/**
	 * The runtime's own builds, per dimension: the passes' and kernels' texture indices and the plans' variants' texture, image,
	 * overlay and sampler slots, as production binds them.
	 */
	private static void checkRuntimeBuilds(String profile, PackModel model, List<String> failures, Map<String, Integer> maxIndex,
			Map<String, String> maxWhere) {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			CustomUniforms custom = CustomUniforms.of(model);
			for (DimensionPrograms dimension : model.dimensions()) {
				String label = profile + " " + dimension.label();
				DimensionSet set = DimensionSet.load(gpu.context(), "complementary", model, dimension, custom, RGBA8,
					dimension.union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
				GbufferPlans plans = set.plans();
				assertNotNull(plans, label + ": the set carries its plans");
				try {
					for (GbufferPlans.PackVariant pv : plans.packVariants()) {
						String kind = pv.program().startsWith("shadow") ? "SHADOW" : "GBUFFERS";
						String where = label + " " + pv.program();
						checkUses(where, kind, pv.reflection().textures(), pv.reflection().images(), pv.overlaySlot(), pv.clouds(),
							failures, maxIndex, maxWhere);
						for (ProgramRequest.TextureBinding texture : pv.textures()) {
							for (Stage stage : new Stage[] {Stage.VERTEX, Stage.FRAGMENT}) {
								int slot = pv.reflection().samplerSlot(texture.name(), stage);
								if (slot >= 16) {
									failures.add(where + " " + stage + ": " + texture.name() + " binds sampler slot " + slot);
								}
							}
						}
					}
				} finally {
					plans.close();
				}
				for (CompiledProgram pass : set.passes()) {
					boolean shadow = pass.phase() == ProgramCatalog.Phase.SHADOW || pass.phase() == ProgramCatalog.Phase.SHADOWCOMP;
					String kind = shadow ? "SHADOW" : "FULL_SCREEN";
					String where = label + " " + pass.name();
					if (pass.reason() == null) {
						checkUses(where, kind, pass.reflection().textures(), pass.reflection().images(), -1, false, failures,
							maxIndex, maxWhere);
					}
					for (ComputeKernel kernel : pass.kernels()) {
						checkUses(where + " kernel " + kernel.name(), "COMPUTE", kernel.textures(), kernel.images(), -1, false,
							failures, maxIndex, maxWhere);
					}
				}
			}
		}
	}

	/** One binding list's texture indices: each below the limit, distinct, and the largest recorded for the kind. */
	private static void checkUses(String where, String kind, List<ProgramTranslation.Reflection.ResourceUse> textures,
			List<ProgramTranslation.Reflection.ResourceUse> images, int overlaySlot, boolean clouds, List<String> failures,
			Map<String, Integer> maxIndex, Map<String, String> maxWhere) {
		Set<Integer> seen = new HashSet<>();
		List<Integer> slots = new ArrayList<>();
		for (ProgramTranslation.Reflection.ResourceUse use : textures) {
			slots.add(use.slot());
		}
		for (ProgramTranslation.Reflection.ResourceUse use : images) {
			slots.add(use.slot());
		}
		if (overlaySlot >= 0) {
			slots.add(overlaySlot);
		}
		for (int slot : slots) {
			if (!seen.add(slot)) {
				failures.add(where + ": two resources take texture index " + slot);
			}
			checkTextureSlot(where, slot, clouds, failures);
			record(maxIndex, maxWhere, kind, slot, where);
		}
	}

	private static void checkTextureSlot(String where, int slot, boolean clouds, List<String> failures) {
		if (slot < 0 || slot >= TEXTURE_LIMIT) {
			failures.add(where + ": binds texture index " + slot + ", outside 0 to " + (TEXTURE_LIMIT - 1));
		}
		if (clouds && slot == EngineSlots.CLOUD_FACES) {
			failures.add(where + ": takes texture index " + slot + ", the clouds' CloudFaces slot");
		}
	}

	private static void record(Map<String, Integer> maxIndex, Map<String, String> maxWhere, String kind, int index, String where) {
		if (index > maxIndex.getOrDefault(kind, -1)) {
			maxIndex.put(kind, index);
			maxWhere.put(kind, where);
		}
	}

	/** The entry functions of a program: vertex and fragment, or the compute kernel. */
	private static List<String> entriesOf(ProgramTranslation t) {
		List<String> out = new ArrayList<>();
		for (String entry : new String[] {t.vertexEntry(), t.fragmentEntry(), t.computeEntry()}) {
			if (entry != null) {
				out.add(entry);
			}
		}
		return out;
	}

	/**
	 * The stage's entry function declares exactly the sampler indices the reflection gives that stage, distinct and in 0 to 15.
	 */
	private static void checkStage(String program, Stage stage, ProgramTranslation t, List<String> failures) {
		String entry = stage == Stage.VERTEX ? t.vertexEntry() : t.fragmentEntry();
		Set<Integer> declared = new HashSet<>();
		if (entry != null) {
			String signature = signatureOf(t.msl(), entry);
			if (signature == null) {
				failures.add(program + " " + stage + ": entry " + entry + " is not in the MSL");
				return;
			}
			Matcher m = SAMPLER.matcher(signature);
			int count = 0;
			while (m.find()) {
				count++;
				int index = Integer.parseInt(m.group(1));
				declared.add(index);
				if (index >= 16) {
					failures.add(program + " " + stage + ": declares sampler(" + index + "), past the 16 a Metal stage has");
				}
			}
			if (count != declared.size()) {
				failures.add(program + " " + stage + ": two sampler arguments share one index: " + signature);
			}
		}
		Set<Integer> reflected = new HashSet<>();
		for (ProgramTranslation.Reflection.SamplerUse use : t.reflection().samplers()) {
			if (use.stage() == stage) {
				reflected.add(use.slot());
				if (use.slot() < 0 || use.slot() >= 16) {
					failures.add(program + " " + stage + ": " + use.texture() + " reflects sampler slot " + use.slot());
				}
			}
		}
		if (!reflected.equals(declared)) {
			failures.add(program + " " + stage + ": the reflection's sampler slots " + reflected + " are not the entry's " + declared);
		}
	}

	/**
	 * The texture indices an entry function declares are distinct and below {@link #TEXTURE_LIMIT}. Returns the largest, or -1.
	 */
	private static int maxTextureIndex(String program, String msl, String entry, List<String> failures) {
		String signature = signatureOf(msl, entry);
		if (signature == null) {
			return -1;
		}
		Matcher m = TEXTURE.matcher(signature);
		Set<Integer> seen = new HashSet<>();
		int max = -1;
		while (m.find()) {
			int index = Integer.parseInt(m.group(1));
			if (!seen.add(index)) {
				failures.add(program + " " + entry + ": two textures share index " + index);
			}
			if (index >= TEXTURE_LIMIT) {
				failures.add(program + " " + entry + ": binds texture index " + index + ", past Metal's " + TEXTURE_LIMIT
					+ " texture indices (0 to " + (TEXTURE_LIMIT - 1) + ")");
			}
			max = Math.max(max, index);
		}
		return max;
	}

	/** The largest texture index per kind, for the report. */
	private static void report(String profile, Map<String, Integer> max, Map<String, String> where) {
		StringBuilder text = new StringBuilder();
		for (Map.Entry<String, Integer> e : max.entrySet()) {
			text.append(e.getKey()).append(" max texture index ").append(e.getValue()).append(" in ")
				.append(where.get(e.getKey())).append('\n');
		}
		try {
			Path dir = Path.of("build", "reports");
			Files.createDirectories(dir);
			Files.writeString(dir.resolve("texture-index-" + profile + ".txt"), text.toString());
		} catch (IOException e) {
			throw new IllegalStateException("cannot write the texture index report", e);
		}
	}

	/** The argument list of the function named {@code entry}, from its name's opening parenthesis to the matching close. */
	private static String signatureOf(String msl, String entry) {
		Matcher m = Pattern.compile("\\b" + Pattern.quote(entry) + "\\(").matcher(msl);
		if (!m.find()) {
			return null;
		}
		int start = m.end();
		int depth = 1;
		for (int i = start; i < msl.length(); i++) {
			char c = msl.charAt(i);
			if (c == '(') {
				depth++;
			} else if (c == ')' && --depth == 0) {
				return msl.substring(start, i);
			}
		}
		return null;
	}
}
