// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.pack.DimensionPrograms;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.gbuffers.GbufferPlans;
import dev.mcms.shaders.runtime.gbuffers.VertexLayoutRegistry;
import dev.mcms.shaders.runtime.terrain.TerrainLayout;
import dev.mcms.shaders.runtime.terrain.TerrainVertexType;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The routing of every game pipeline in every (kind, phase, domain) the plans build, against a golden digest: the SHA-256
 * of the full {@link GbufferRoutingDump} text, kept in {@code src/test/resources/dev/mcms/shaders/runtime/routing-<pack>.sha256}.
 * The digests were taken from the build before the parallel pre-build existed (the base of the loadspeed branch, commit
 * e8a6866), so a match proves the answers did not change: the same program, render stage, depth binding, slots and state
 * presence for every variant. The full dump is written to {@code build/routing/} on every run for diffing; the digests are
 * not stored whole because the Complementary dump is about 4 MB.
 * <p>2026-11-15: the Complementary digest was re-derived for the mip rule (engine-pack-format.md section 7). Every program now
 * samples a mipped target's shared chain for explicit-level lookups, so 894 sampler lines read {@code mip=LINEAR} (the
 * per-program rule had {@code mip=null} there), and each texture binding prints its {@code implicitSampler} (the sampler of a
 * non-declaring program's implicit-LOD lookups, {@code mip=null}, or null). The mcms-classes digest changed for the field only.
 * <p>2026-11-19 (RIL-562): both digests were re-derived for the default alpha-test reference, the game pipeline's own
 * {@code ALPHA_CUTOUT}: the variants of {@code cutout_terrain}, {@code cutout_terrain_multidraw}, {@code cutout_block} and
 * Sodium's {@code cutout_terrain} read {@code alpha=0.5} (0.1 before), 24 lines in the mcms-classes dump (no shadow program)
 * and 56 in the Complementary dump (its shadow variants of those pipelines too); no other line changed. Then again for the
 * pipelines that name no cutout, after the Iris runs (results/2026-11-19-ril562-alpharef): those whose shader cuts nothing
 * read {@code alpha=0.0} and those whose shader drops alpha 0 only read the smallest normal float, 266 and 156 lines in the
 * mcms-classes dump and 360 and 150 in the Complementary dump (0.1 before); the particle, text, glint and crumbling
 * pipelines keep 0.1, their shaders' own cut; no other line changed.
 * <p>2026-11-20 (RIL-570): both digests were re-derived for the cutout terrain stage. Iris 1.11.7 reports
 * {@code TERRAIN_SOLID} for Sodium's cutout pass in the world and in the shadow pass (results/2026-11-19-ril570-cutout,
 * results/2026-11-19-ril562-alpharef), so the variants of {@code cutout_terrain}, {@code cutout_terrain_multidraw}
 * and Sodium's {@code cutout_terrain} read {@code stage=8} (10 before): 6 lines in the mcms-classes dump and 30 in the
 * Complementary dump (its shadow variants too, still {@code shadow_cutout}); no other line changed.
 */
@Tag("gpu")
class GbufferRoutingGpuTest {

	/** The Sodium terrain layout the plans see (E4-B's registration, the same shape the coverage test uses). */
	static final VertexFormat EXTENDED = TerrainVertexType.INSTANCE.getVertexFormat();

	/** How many differing lines of each side a digest mismatch prints, and how many characters of each. */
	private static final int DIFF_LINES = 40;
	private static final int DIFF_LINE_CHARS = 240;

	@Test
	void mcmsClassesRoutingMatchesTheGolden() throws IOException {
		Path dir = findTestPack("mcms-classes");
		PackModel model;
		try (PackSource source = PackSource.open(dir)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		check("mcms-classes", model, model.rootFolder());
	}

	@Test
	@Tag("corpus")
	void complementaryOverworldRoutingMatchesTheGolden() throws IOException {
		String corpus = System.getProperty("mcms.test.corpus");
		assumeTrue(corpus != null, "no corpus folder given");
		Path zip = Path.of(corpus, "ComplementaryReimagined_r5.9.3.zip");
		assumeTrue(Files.isRegularFile(zip), zip + " is missing");
		Properties options = new Properties();
		Path optionFile = Path.of(corpus).getParent().getParent().resolve("pack-options")
			.resolve("ComplementaryReimagined_r5.9.3.zip.txt");
		if (Files.isRegularFile(optionFile)) {
			options.load(Files.newBufferedReader(optionFile));
		}
		PackModel model;
		try (PackSource source = PackSource.open(zip)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", options);
		}
		check("complementary-overworld", model, GbufferBuildSupport.overworld(model));
	}

	private static void check(String label, PackModel model, DimensionPrograms dimension) throws IOException {
		VertexLayoutRegistry.register(EXTENDED, TerrainLayout.layout(0), GbufferBuildSupport.TERRAIN_FACTS);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			CustomUniforms custom = CustomUniforms.of(model);
			List<RenderPipeline> pipelines = Engine.gamePipelines();
			try (GbufferPlans plans = GbufferPlans.build(gpu.context(), model, dimension, custom,
				dimension.union().colorFormats(), pipelines, Engine.COPIES_NOT_COMPILED)) {
				String dump = String.join("\n", GbufferRoutingDump.of(plans, pipelines)) + "\n";
				Path dir = Path.of(System.getProperty("user.dir"), "build", "routing");
				Path out = dir.resolve("routing-" + label + ".txt");
				Path matched = dir.resolve("routing-" + label + ".matched.txt");
				Files.createDirectories(dir);
				Files.writeString(out, dump, StandardCharsets.UTF_8);
				String expected = golden("routing-" + label + ".sha256");
				String actual = sha256(dump);
				assertEquals(expected, actual,
					() -> "routing changed; the full dump is in " + out + "\n" + diff(matched, dump));
				Files.copy(out, matched, StandardCopyOption.REPLACE_EXISTING);  // the reference for the next mismatch
			} finally {
				VertexLayoutRegistry.unregister(EXTENDED);
			}
		}
	}

	/**
	 * The readable part of a digest mismatch: the lines only the new dump has ({@code +}) and the lines only the last
	 * matching dump had ({@code -}), capped at {@link #DIFF_LINES} each. Every passing run keeps its dump as the matching
	 * copy in build/routing, so the first mismatch on a machine after a passing run shows the difference; without one the
	 * message says so.
	 */
	private static String diff(Path matched, String dump) {
		if (!Files.isRegularFile(matched)) {
			return "no earlier matching dump at " + matched + " to diff against (diff the full dump by hand)";
		}
		Set<String> before;
		try {
			before = new LinkedHashSet<>(Files.readAllLines(matched, StandardCharsets.UTF_8));
		} catch (IOException e) {
			return "could not read the matching dump " + matched + ": " + e;
		}
		Set<String> after = new LinkedHashSet<>(Arrays.asList(dump.split("\n")));
		List<String> plus = new ArrayList<>();
		List<String> minus = new ArrayList<>();
		int added = 0;
		int removed = 0;
		for (String line : after) {
			if (!before.contains(line)) {
				added++;
				if (plus.size() < DIFF_LINES) {
					plus.add("+ " + clip(line));
				}
			}
		}
		for (String line : before) {
			if (!after.contains(line)) {
				removed++;
				if (minus.size() < DIFF_LINES) {
					minus.add("- " + clip(line));
				}
			}
		}
		List<String> shown = new ArrayList<>(plus);
		shown.addAll(minus);
		return "lines only in the new dump: " + added + "; only in the matching dump: " + removed + " (first "
			+ DIFF_LINES + " of each shown)\n" + String.join("\n", shown);
	}

	private static String clip(String line) {
		return line.length() > DIFF_LINE_CHARS ? line.substring(0, DIFF_LINE_CHARS) + "..." : line;
	}

	private static String golden(String name) throws IOException {
		try (InputStream in = GbufferRoutingGpuTest.class.getResourceAsStream(name)) {
			if (in == null) {
				return "(no golden " + name + ")";
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
		}
	}

	private static String sha256(String text) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is part of every Java runtime", impossible);
		}
	}

	private static Path findTestPack(String name) {
		Path dir = Path.of(System.getProperty("user.dir"));
		for (int i = 0; i < 6; i++) {
			Path candidate = dir.resolve(Path.of("bench", "test-packs", name));
			if (Files.isDirectory(candidate)) {
				return candidate;
			}
			dir = dir.getParent();
		}
		throw new IllegalStateException("bench/test-packs/" + name + " not found above " + System.getProperty("user.dir"));
	}
}
