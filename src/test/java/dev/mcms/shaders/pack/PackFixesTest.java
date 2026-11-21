// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for the W2-A review: the dimension folder rule, the option value lists after tooltip text, the Iris
 * shadow programs, the Iris render stages and GPU macros, and the immutable properties. Each test names the review item.
 */
class PackFixesTest {
	@TempDir
	Path dir;

	private void file(String rel, String text) throws IOException {
		Path p = dir.resolve("shaders").resolve(rel);
		Files.createDirectories(p.getParent());
		Files.writeString(p, text);
	}

	private PackModel load() throws IOException {
		try (PackSource pack = PackSource.open(dir)) {
			return PackModel.load(pack, StandardMacros.engine(), "en_us", new Properties());
		}
	}

	private OptionCatalog scan() throws IOException {
		try (PackSource pack = PackSource.open(dir)) {
			return OptionDiscovery.scan(pack, List.of(""));
		}
	}

	private static DimensionPrograms row(PackModel m, Dimension d) {
		return m.dimensions().stream().filter(r -> r.dimension().equals(Optional.of(d))).findFirst().orElseThrow();
	}

	private static Optional<ResolvedProgram> program(DimensionPrograms d, String name) {
		return d.programs().stream().filter(p -> p.name().equals(name)).findFirst();
	}

	// ---- item 1: the dimension folder rule ----

	@Test
	void aDimensionWithoutItsOwnFolderUsesTheBaseFolder() {
		// OptiFine: "When the world folder is present the shaders will be loaded only from there ignoring the default folder."
		// Iris: a dimension that dimension.properties does not name uses the files in shaders/. A worldN folder of another
		// dimension does not remove the base folder for this one.
		DimensionRules rules = DimensionRules.parse(PropertiesText.parse(""));
		assertEquals("world0", rules.folderFor(Dimension.OVERWORLD, Set.of("world0", "world-1")));
		assertEquals("", rules.folderFor(Dimension.END, Set.of("world0", "world-1")), "no world1: the base folder serves");
		assertEquals("", rules.folderFor(Dimension.END, Set.of("world0")), "no world1: the base folder serves");
	}

	@Test
	void theBaseFolderIsScannedForOptionsWhenAWorldFolderExists() throws IOException {
		file("gbuffers_basic.fsh", "// #define ROOT_OPTION\n#ifdef ROOT_OPTION\nvoid main() {}\n#endif\n");
		file("world-1/gbuffers_basic.fsh", "void main() {}\n");
		PackModel m = load();
		assertTrue(m.options().get("ROOT_OPTION").isPresent(), "the overworld can run from the base folder, so it is scanned");
	}

	@Test
	void theOverworldRunsFromTheBaseFolderWhenNoWorld0Exists() throws IOException {
		file("composite.fsh", "/* DRAWBUFFERS:0 */\nvoid main() {}\n");
		file("world-1/composite.fsh", "/* DRAWBUFFERS:0 */\nvoid main() {}\n");
		PackModel m = load();
		DimensionPrograms overworld = row(m, Dimension.OVERWORLD);
		assertEquals(Optional.of(""), overworld.folder(), "the Solas layout: root serves the overworld");
		assertEquals(ResolvedProgram.Source.DIRECT, program(overworld, "composite").orElseThrow().source());
		assertEquals(Optional.of("world-1"), row(m, Dimension.NETHER).folder());
	}

	@Test
	void aQualifiedRuleDoesNotSwitchOffTheBaseFolder() throws IOException {
		// Decision (report README, item 1): program.world0/<name>.enabled names the files of world0. A dimension that the base
		// folder serves has no world0 files, so the rule has no effect there. Vitrail's Solas log loads shadowcomp from the
		// root for the overworld and its world0 rule is true at the defaults, so the log does not settle the scope.
		file("shaders.properties", "program.world0/composite.enabled=false\n");
		file("composite.fsh", "/* DRAWBUFFERS:0 */\nvoid main() {}\n");
		file("world-1/composite.fsh", "/* DRAWBUFFERS:0 */\nvoid main() {}\n");
		PackModel m = load();
		assertEquals(ResolvedProgram.Source.DIRECT, program(row(m, Dimension.OVERWORLD), "composite").orElseThrow().source());
	}

	@Test
	void aQualifiedRuleSwitchesOffItsOwnFolder() throws IOException {
		file("shaders.properties", "program.world-1/composite.enabled=false\n");
		file("composite.fsh", "/* DRAWBUFFERS:0 */\nvoid main() {}\n");
		file("world-1/composite.fsh", "/* DRAWBUFFERS:0 */\nvoid main() {}\n");
		PackModel m = load();
		assertEquals(ResolvedProgram.Source.DISABLED, program(row(m, Dimension.NETHER), "composite").orElseThrow().source());
	}

	// ---- item 2: the Iris shadow programs ----

	@Test
	void irisAdditionalShadowProgramsAreDocumentedAndFallBackToShadow() {
		for (String n : List.of("shadow_water", "shadow_entities", "shadow_block")) {
			ProgramCatalog.Program p = ProgramCatalog.lookup(n).orElseThrow(() -> new AssertionError(n + " is not documented"));
			assertEquals("shadow", p.fallback(), n + " falls back to shadow (Iris shadow page)");
			assertEquals(ProgramCatalog.Phase.SHADOW, p.phase(), n);
			assertTrue(ProgramCatalog.match(n, "fsh").isPresent(), n + " matches its stage file");
		}
	}

	@Test
	void aNestedDimensionServesShadowWaterFromItsFallback() throws IOException {
		file("shadow.fsh", "/* DRAWBUFFERS:0 */\nvoid main() {}\n");
		file("shadow_water.fsh", "/* DRAWBUFFERS:0 */\nvoid main() {}\n");
		file("world-1/shadow.fsh", "/* DRAWBUFFERS:0 */\nvoid main() {}\n");
		PackModel m = load();
		assertEquals(ResolvedProgram.Source.DIRECT, program(row(m, Dimension.OVERWORLD), "shadow_water").orElseThrow().source());
		ResolvedProgram nether = program(row(m, Dimension.NETHER), "shadow_water").orElseThrow();
		assertEquals(ResolvedProgram.Source.FALLBACK, nether.source());
		assertEquals(Optional.of("shadow"), nether.servedBy());
	}

	// ---- item 3: value lists after tooltip text ----

	@Test
	void aValueListAfterTooltipTextIsRead() throws IOException {
		// The OptiFine doc's own example: #define SHADOW_DARKNESS 0.10 // Shadow darkness levels [0.05 0.10 0.20]
		file("composite.fsh", "#define SHADOW_DARKNESS 0.10 // Shadow darkness levels [0.05 0.10 0.20]\n"
			+ "const float sunPathRotation = 40.0; // Sun path rotation [0.0 40.0 60.0]\n"
			+ "void main() { float d = SHADOW_DARKNESS; }\n");
		OptionCatalog c = scan();
		PackOption dark = c.get("SHADOW_DARKNESS").orElseThrow();
		assertTrue(dark.hasValueList(), "a list after tooltip text is a value list");
		assertEquals(List.of("0.05", "0.10", "0.20"), dark.values());
		assertEquals("0.10", dark.defaultValue());
		assertEquals("Shadow darkness levels", dark.tooltip());
		PackOption sun = c.get("sunPathRotation").orElseThrow();
		assertTrue(sun.hasValueList(), "the same form counts for a const");
		assertEquals(List.of("0.0", "40.0", "60.0"), sun.values());
		assertEquals("Sun path rotation", sun.tooltip());
	}

	// ---- item 4 and 5 and 6: the standard macros ----

	@Test
	void renderStagesAreTheIrisListInItsOrder() {
		// Iris render stages page: the names in the order of its table. The values are list positions.
		List<String> iris = List.of("NONE", "SKY", "SUNSET", "CUSTOM_SKY", "SUN", "MOON", "STARS", "VOID", "TERRAIN_SOLID",
			"TERRAIN_CUTOUT_MIPPED", "TERRAIN_CUTOUT", "ENTITIES", "BLOCK_ENTITIES", "DESTROY", "OUTLINE", "DEBUG",
			"HAND_SOLID", "TERRAIN_TRANSLUCENT", "TRIPWIRE", "PARTICLES", "CLOUDS", "RAIN_SNOW", "WORLD_BORDER",
			"HAND_TRANSLUCENT");
		assertEquals(iris, StandardMacros.RENDER_STAGES);
		Map<String, String> p = StandardMacros.engine().predefined();
		Set<String> defined = p.keySet().stream().filter(k -> k.startsWith("MC_RENDER_STAGE_")).collect(Collectors.toSet());
		assertEquals(iris.stream().map(s -> "MC_RENDER_STAGE_" + s).collect(Collectors.toSet()), defined);
		assertEquals("9", p.get("MC_RENDER_STAGE_TERRAIN_CUTOUT_MIPPED"));
		assertEquals("10", p.get("MC_RENDER_STAGE_TERRAIN_CUTOUT"));
	}

	@Test
	void irisGpuAndColorBufferMacrosFollowThePages() {
		StandardMacros m = StandardMacros.engine();
		assertTrue(m.isDefined("MC_GL_RENDERER_APPLE"), "Iris GPU hardware page: Apple renderer (Iris 1.8 or higher)");
		assertFalse(m.isDefined("MC_GL_RENDERER_OTHER"), "an Apple GPU has its own renderer macro");
		assertEquals("16", m.predefined().get("MAX_COLOR_BUFFERS"), "the engine serves colortex0 to colortex15 (Targets.COLOR_TARGETS)");
	}

	@Test
	void engineDefinesNoOperatingSystemMacro() {
		// Decided 2026-11-15: MC_OS_MAC stands for Apple OpenGL's limits, and the engine is not Apple's OpenGL.
		Set<String> operatingSystems = StandardMacros.engine().predefined().keySet().stream()
			.filter(k -> k.startsWith("MC_OS_")).collect(Collectors.toSet());
		assertEquals(Set.of(), operatingSystems);
	}

	@Test
	void engineDefinesOnlyTheRequestedFlagsItProvides() {
		Set<String> none = flagsOf(StandardMacros.engine());
		assertTrue(none.isEmpty(), "no flag is defined without a request, got " + none);
		Set<String> all = flagsOf(StandardMacros.engine().withRequestedFeatures(StandardMacros.IRIS_FEATURES));
		assertEquals(Set.of("IRIS_FEATURE_BLOCK_EMISSION_ATTRIBUTE", "IRIS_FEATURE_COMPUTE_SHADERS", "IRIS_FEATURE_CUSTOM_IMAGES",
			"IRIS_FEATURE_ENTITY_TRANSLUCENT", "IRIS_FEATURE_HIGHER_SHADOWCOLOR", "IRIS_FEATURE_PER_BUFFER_BLENDING",
			"IRIS_FEATURE_SSBO"), all);
	}

	private static Set<String> flagsOf(StandardMacros macros) {
		return macros.predefined().keySet().stream().filter(k -> k.startsWith("IRIS_FEATURE_")).collect(Collectors.toSet());
	}

	@Test
	void everyEngineFeatureFlagNamesItsCode() {
		assertEquals(StandardMacros.ENGINE_FEATURES, StandardMacros.FEATURE_SOURCES.keySet());
		for (String f : StandardMacros.ENGINE_FEATURES) {
			assertFalse(StandardMacros.FEATURE_SOURCES.get(f).isBlank(), f + " has no source");
		}
	}

	// ---- item 9 (part): shadow programs and shader properties ----

	@Test
	void shaderPropertiesCopyTheirCollections() {
		ShaderProperties s = ShaderProperties.from(PropertiesText.parse("sliders=A B\nclouds=fast\n"));
		assertThrows(UnsupportedOperationException.class, () -> s.sliders().add("C"), "the sliders list is a copy");
		assertThrows(UnsupportedOperationException.class, () -> s.booleans().put("X", true), "the booleans map is a copy");
		assertThrows(UnsupportedOperationException.class, () -> s.problems().add("X"), "the problems list is a copy");
	}
}
