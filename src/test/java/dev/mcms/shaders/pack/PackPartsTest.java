// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** The pure parts of the pack model: catalog, directives, constants, macros, properties, profiles, screens and id maps. */
class PackPartsTest {

	// ---- program catalog ----

	@Test
	void baseListHasTheFortyTwoDocumentedNonNumberedNames() {
		assertEquals(42, ProgramCatalog.base().size(), "the OptiFine list, the Iris additions and the three Iris 1.8 shadow programs");
		assertTrue(ProgramCatalog.base().stream().anyMatch(p -> p.name().equals("gbuffers_lightning")), "an Iris addition");
		assertTrue(ProgramCatalog.base().stream().anyMatch(p -> p.name().equals("deferred_pre") && p.virtual()));
		assertFalse(ProgramCatalog.base().stream().anyMatch(p -> p.name().equals("composite2")), "numbered names are not base");
	}

	@Test
	void fallbacksFollowTheDocsTables() {
		assertEquals("gbuffers_hand", ProgramCatalog.lookup("gbuffers_hand_water").orElseThrow().fallback());
		assertEquals("gbuffers_terrain", ProgramCatalog.lookup("gbuffers_water").orElseThrow().fallback());
		assertEquals("gbuffers_textured", ProgramCatalog.lookup("gbuffers_clouds").orElseThrow().fallback());
		assertNull(ProgramCatalog.lookup("composite2").orElseThrow().fallback(), "numbered passes have no fallback");
	}

	@Test
	void computeSuffixesMatchOnlyCompute() {
		assertEquals("a", ProgramCatalog.match("composite_a", "csh").orElseThrow().suffix());
		assertEquals("composite", ProgramCatalog.match("composite_a", "csh").orElseThrow().program().name());
		assertEquals("composite2", ProgramCatalog.match("composite2_b", "csh").orElseThrow().program().name());
		assertTrue(ProgramCatalog.match("composite_a", "fsh").isEmpty(), "a suffix letter is for compute files only");
		assertTrue(ProgramCatalog.match("gbuffers_basic_a", "csh").isEmpty(), "gbuffers take no compute files");
		assertTrue(ProgramCatalog.match("clrwl_gbuffers", "fsh").isEmpty());
	}

	@Test
	void gbuffersSplitAtDeferred() {
		assertEquals(ProgramCatalog.Phase.TRANSLUCENT, ProgramCatalog.lookup("gbuffers_water").orElseThrow().phase());
		assertEquals(ProgramCatalog.Phase.TRANSLUCENT, ProgramCatalog.lookup("gbuffers_weather").orElseThrow().phase());
		assertEquals(ProgramCatalog.Phase.OPAQUE, ProgramCatalog.lookup("gbuffers_terrain").orElseThrow().phase());
	}

	// ---- directives ----

	private static ProgramDirectives directives(String fragment) {
		return ProgramDirectives.of(List.of(new StageFile('f', "p.fsh", fragment)));
	}

	@Test
	void lastDrawBuffersCommentInTheFragmentWins() {
		ProgramDirectives d = directives("/* DRAWBUFFERS:0 */\n/* DRAWBUFFERS:27 */\nvoid main() {}\n");
		assertEquals(List.of(2, 7), d.writes());
		assertEquals("DRAWBUFFERS", d.writeSource());
	}

	@Test
	void renderTargetsListsTheTargets() {
		ProgramDirectives d = directives("/* RENDERTARGETS: 0,2,11,15 */\n");
		assertEquals(List.of(0, 2, 11, 15), d.writes());
		assertEquals(15, d.largestTarget().getAsInt());
	}

	@Test
	void aFragmentWithoutACommentWritesNoTargetWhenItHasNoOutput() {
		ProgramDirectives d = directives("void main() {}\n");
		assertEquals(List.of(), d.writes(), "the active code writes no target, so the program claims none");
		assertEquals("default-narrowed", d.writeSource());
	}

	@Test
	void readsMatchWholeWordsAndAliases() {
		ProgramDirectives d = ProgramDirectives.of(List.of(new StageFile('f', "p.fsh",
			"uniform sampler2D colortex10;\nuniform sampler2D gaux1;\n/* DRAWBUFFERS:0 */\n")));
		assertTrue(d.reads().contains(10));
		assertFalse(d.reads().contains(1), "colortex10 is not colortex1");
		assertTrue(d.reads().contains(4), "gaux1 is colortex4");
	}

	@Test
	void constantsFormatsClearsAndMipmapsAreRead() {
		ProgramDirectives d = directives(String.join("\n",
			"const int colortex3Format = RGBA16F;",
			"const bool colortex4Clear = false;",
			"const vec4 colortex4ClearColor = vec4(0.5, 0.0, 0.0, 1.0);",
			"const bool gcolorMipmapEnabled = true;",
			"const float sunPathRotation = -40.0;",
			"const ivec3 workGroups = ivec3(50, 30, 1);",
			"/* SHADOWRES:1024 */",
			"void main() {}", ""));
		assertEquals("RGBA16F", d.colorFormats().get(3));
		assertEquals(Boolean.FALSE, d.clears().get(4));
		assertEquals(0.5, d.clearColors().get(4).values().get(0));
		assertEquals(Boolean.TRUE, d.mipmaps().get(0));
		assertEquals(-40.0, d.constants().get("sunPathRotation").asDouble());
		assertEquals(List.of(50.0, 30.0, 1.0), d.constants().get("workGroups").values());
		assertEquals(1024.0, d.legacyValues().get("SHADOWRES"));
	}

	@Test
	void theLegacyGcolorClearColourIsColortex0sClearColour() {
		ProgramDirectives d = directives("const vec4 gcolorClearColor = vec4(0.5, 0.25, 0.125, 1.0);\n");
		assertEquals(0.5, d.clearColors().get(0).values().get(0));
	}

	@Test
	void theLastConstantDeclarationWins() {
		ProgramDirectives d = directives("const int shadowMapResolution = 1024;\nconst int shadowMapResolution = 2048;\n");
		assertEquals(2048, d.constants().get("shadowMapResolution").asInt());
	}

	// ---- constants ----

	@Test
	void constEvaluatorReadsLiteralsVectorsAndArithmetic() {
		assertEquals(0.5, ConstValue.parse("float", "0.5f").orElseThrow().asDouble());
		assertEquals(7.0, ConstValue.parse("float", "1.0 + 2.0 * 3.0").orElseThrow().asDouble(), "precedence");
		assertEquals(List.of(0.5, 0.5), ConstValue.parse("vec2", "vec2(0.5)").orElseThrow().values(), "broadcast");
		assertEquals(List.of(-1.0), ConstValue.parse("float", "-(2.0 - 1.0)").orElseThrow().values());
		assertTrue(ConstValue.parse("bool", "true").orElseThrow().asBoolean());
	}

	@Test
	void constEvaluatorRejectsWhatItDoesNotKnow() {
		assertTrue(ConstValue.parse("float", "mystery(1.0)").isEmpty());
		assertTrue(ConstValue.parse("int", "RGBA16").isEmpty());
		assertTrue(ConstValue.parse("vec3", "vec3(1.0, 2.0)").isEmpty(), "two components for vec3");
	}

	// ---- macros ----

	@Test
	void mcVersionSortsAfterEveryOneDotXValue() {
		int v = Integer.parseInt(StandardMacros.MC_VERSION);
		assertTrue(v > 12100, "1.21.x is 12100 in the 122 format; 26.3 must sort after it");
		assertEquals("260300", StandardMacros.MC_VERSION);
	}

	@Test
	void everyEngineMacroHasASource() {
		for (StandardMacros.Macro m : StandardMacros.engine().macros()) {
			assertFalse(m.source().isBlank(), m.name() + " has no source");
		}
	}

	@Test
	void onlyRequestedAndProvidedFeatureFlagsAreDefined() {
		StandardMacros engine = StandardMacros.engine();
		StandardMacros requested = engine.withRequestedFeatures(List.of("SSBO", "CAN_DISABLE_WEATHER", "NOT_A_FLAG"));
		assertTrue(requested.isDefined("IRIS_FEATURE_SSBO"), "requested and provided");
		assertFalse(requested.isDefined("IRIS_FEATURE_CAN_DISABLE_WEATHER"), "requested but not provided by the engine");
		assertFalse(requested.isDefined("IRIS_FEATURE_COMPUTE_SHADERS"), "provided but not requested");
		assertFalse(engine.isDefined("IRIS_FEATURE_SSBO"), "no feature flag is defined before a pack requests it");
		assertThrows(IllegalArgumentException.class, () -> new StandardMacros(Set.of("NOT_A_FLAG"), false));
	}

	@Test
	void engineProvidesTheE6SetAndDefinesEachRequestedFlagOnly() {
		assertTrue(StandardMacros.IRIS_FEATURES.containsAll(StandardMacros.ENGINE_FEATURES));
		StandardMacros engine = StandardMacros.engine();
		for (String f : StandardMacros.ENGINE_FEATURES) {
			assertTrue(engine.offers(f), f + " is provided");
		}
		assertFalse(engine.offers("CAN_DISABLE_WEATHER"));
		StandardMacros narrowed = engine.withRequestedFeatures(List.of("SSBO"));
		assertTrue(narrowed.isDefined("IRIS_FEATURE_SSBO"));
		assertFalse(narrowed.isDefined("IRIS_FEATURE_CUSTOM_IMAGES"), "not requested");
		assertEquals("460", narrowed.predefined().get("MC_GL_VERSION"), "the GL level does not depend on the requests");
		assertEquals("460", narrowed.predefined().get("MC_GLSL_VERSION"));
	}

	@Test
	void renderStagesAreNumberedInExecutionOrder() {
		StandardMacros m = StandardMacros.engine();
		assertEquals("0", m.predefined().get("MC_RENDER_STAGE_NONE"));
		assertEquals("1", m.predefined().get("MC_RENDER_STAGE_SKY"));
		assertEquals(String.valueOf(StandardMacros.RENDER_STAGES.size() - 1), m.predefined().get("MC_RENDER_STAGE_HAND_TRANSLUCENT"));
	}

	@Test
	void distantHorizonsIsDefinedOnlyWhenRequested() {
		assertFalse(StandardMacros.engine().isDefined("DISTANT_HORIZONS"));
		assertTrue(new StandardMacros(StandardMacros.ENGINE_FEATURES, true).isDefined("DISTANT_HORIZONS"));
	}

	// ---- properties ----

	@Test
	void propertiesJoinContinuationsAndLaterKeysWin() {
		PropertiesText.Parsed p = PropertiesText.parse("a=1\nb=two \\\n  three\na=9\n");
		Map<String, String> m = p.lastWins();
		assertEquals("9", m.get("a"));
		assertEquals("two three", m.get("b"));
	}

	@Test
	void shaderPropertiesTypesItsDirectives() {
		String text = String.join("\n",
			"clouds=fancy",
			"shadowTerrain=false",
			"shadow.culling=reversed",
			"particles.ordering=mixed",
			"texture.composite.colortex1=textures/lut.dat TEXTURE_3D RGBA32F 64 64 64 RGBA FLOAT",
			"alphaTest.gbuffers_basic=GREATER 0.1",
			"alphaTest.gbuffers_water=false",
			"blend.composite=SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ZERO",
			"blend.gbuffers_water.colortex0=off",
			"scale.composite=0.5 0.25 0.25",
			"flip.composite.colortex0=true",
			"size.buffer.colortex2=0.5 0.5",
			"sliders=A B",
			"bufferObject.2=1024",
			"uniform.float.a=1.0",
			"variable.bool.b=a > 0.5",
			"noise.unknown=1",
			"");
		ShaderProperties s = ShaderProperties.from(PropertiesText.parse(text));
		assertEquals(Optional.of(ShaderProperties.Clouds.FANCY), s.clouds());
		assertEquals(Boolean.FALSE, s.booleans().get("shadowTerrain"));
		assertEquals(Optional.of(ShaderProperties.ShadowCulling.REVERSED), s.shadowCulling());
		assertEquals(Optional.of(ShaderProperties.ParticleOrdering.MIXED), s.particleOrdering());
		ShaderProperties.TextureBinding t = s.textures().get(0);
		assertEquals("composite", t.stage());
		assertEquals(List.of(64, 64, 64), t.raw().orElseThrow().dimensions());
		assertEquals("GREATER", s.alphaTests().get("gbuffers_basic").func());
		assertTrue(s.alphaTests().get("gbuffers_water").off(), "false reads as off");
		assertTrue(s.blends().get("gbuffers_water.colortex0").off());
		assertEquals(0.25, s.scales().get("composite").offsetY());
		assertTrue(s.bufferSizes().get("colortex2").relative(), "a decimal point makes the size relative");
		assertEquals(List.of("A", "B"), s.sliders());
		assertEquals(1024L, s.bufferObjects().get(0).bytes());
		assertEquals(List.of("a", "b"), s.customValues().stream().map(ShaderProperties.CustomValueSource::name).toList());
		assertEquals(List.of("noise.unknown"), s.unreadKeys());
		assertTrue(s.problems().isEmpty(), s.problems().toString());
	}

	/**
	 * {@code blend.<program>[.<buffer>]} reads two or four factors, in any case: two name the colour pair alone and
	 * mean the same pair for the alpha equation (glBlendFunc(src, dst)), four are the documented full form, and the
	 * names normalise to the documented upper case. Anything else stays a load problem.
	 */
	@Test
	void blendDirectivesReadTwoOrFourFactorsInAnyCase() {
		ShaderProperties s = ShaderProperties.from(PropertiesText.parse("""
			blend.gbuffers_terrain.colortex5=zero src_color
			blend.gbuffers_water=src_alpha one_minus_src_alpha ONE ONE_MINUS_SRC_ALPHA
			blend.gbuffers_entities=off
			"""));
		ShaderProperties.BlendState twoFactor = s.blends().get("gbuffers_terrain.colortex5");
		assertNotNull(twoFactor, "the two-factor form must parse");
		assertTrue(s.blends().get("gbuffers_entities").off());
		assertEquals("ZERO", twoFactor.src());
		assertEquals("SRC_COLOR", twoFactor.dst());
		assertEquals("ZERO", twoFactor.srcAlpha(), "two factors mean the same pair for alpha");
		assertEquals("SRC_COLOR", twoFactor.dstAlpha(), "two factors mean the same pair for alpha");
		ShaderProperties.BlendState fourFactor = s.blends().get("gbuffers_water");
		assertEquals("SRC_ALPHA", fourFactor.src());
		assertEquals("ONE_MINUS_SRC_ALPHA", fourFactor.dst());
		assertEquals("ONE", fourFactor.srcAlpha());
		assertEquals("ONE_MINUS_SRC_ALPHA", fourFactor.dstAlpha());

		ShaderProperties bad = ShaderProperties.from(PropertiesText.parse("""
			blend.composite=SRC_ALPHA
			blend.deferred=SRC_ALPHA ONE_MINUS_SRC_ALPHA EXTRA
			blend.final=SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE NOT_A_FACTOR
			"""));
		assertTrue(bad.blends().isEmpty(), bad.blends().toString());
		assertEquals(3, bad.problems().size(), bad.problems().toString());
	}

	@Test
	void shaderPropertiesReportsBadValues() {
		ShaderProperties s = ShaderProperties.from(PropertiesText.parse("shadowTerrain=yes\nclouds=thick\n"));
		assertEquals(2, s.problems().size(), s.problems().toString());
	}

	@Test
	void idEntriesReadNamespaceNameAndState() {
		assertEquals("minecraft", IdEntry.parse("oak_stairs").orElseThrow().namespace());
		assertEquals("botania", IdEntry.parse("botania:crate").orElseThrow().namespace());
		IdEntry state = IdEntry.parse("minecraft:oak_stairs:facing=east,west:half=bottom").orElseThrow();
		assertEquals("oak_stairs", state.name());
		assertEquals(List.of("east", "west"), state.state().get("facing"));
		assertEquals("minecraft", IdEntry.parse("oak_stairs:facing=east").orElseThrow().namespace(), "no namespace given");
		assertTrue(IdEntry.parse("a::b").isEmpty(), "an empty part is not a name");
	}

	@Test
	void idMapFilesRejectDuplicateIds() {
		IdMapFile f = IdMapFile.parse("block", PropertiesText.parse("block.1=stone\nblock.1=dirt\nlayer.solid=stone\n"));
		assertEquals(1, f.ids().size());
		assertEquals(1, f.problems().size(), f.problems().toString());
		assertEquals(1, f.layers().get("solid").size());
	}

	@Test
	void dimensionRulesFollowTheIrisOrder() {
		DimensionRules r = DimensionRules.parse(PropertiesText.parse("dimension.world_modded=*\ndimension.netherShaders=minecraft:the_nether\n"));
		assertEquals(Optional.of("netherShaders"), r.declaredFolder(Dimension.NETHER));
		assertEquals("world_modded", r.folderFor(Dimension.OVERWORLD, Set.of("world_modded", "netherShaders")));
		DimensionRules none = DimensionRules.parse(PropertiesText.parse(""));
		assertEquals("", none.folderFor(Dimension.END, Set.of()), "no worldN folder: the root serves");
		assertEquals("", none.folderFor(Dimension.END, Set.of("world0")), "a worldN folder without world1: the base folder serves");
	}

	// ---- profiles and screens ----

	private static OptionCatalog catalog(String... names) {
		Map<String, PackOption> m = new LinkedHashMap<>();
		for (String n : names) {
			m.put(n, new PackOption(n, OptionKind.BOOLEAN, "false", List.of(), false, "", "", List.of(), false));
		}
		return new OptionCatalog(m, List.of(), List.of());
	}

	@Test
	void profilesInheritAndDetectTheCurrentOne() {
		OptionCatalog c = catalog("A", "B");
		Map<String, String> raw = new LinkedHashMap<>();
		raw.put("BASE", "A");
		raw.put("MORE", "profile.BASE B !program.gbuffers_water");
		ProfileSet p = ProfileSet.parse(raw, c);
		ProfileSet.Resolved more = p.resolve("MORE");
		assertEquals("true", more.settings().get("A"));
		assertTrue(more.disabledPrograms().contains("gbuffers_water"));
		OptionValues values = new OptionValues(Map.of("A", "true", "B", "true"), Map.of(), List.of());
		assertEquals("MORE", p.detect(values).orElseThrow().name(), "the profile with more matching settings wins");
		OptionValues none = new OptionValues(Map.of("A", "false", "B", "false"), Map.of(), List.of());
		assertTrue(p.detect(none).isEmpty(), "no profile matches: Custom");
	}

	@Test
	void profilesRejectCyclesAndUnknownOptions() {
		OptionCatalog c = catalog("A");
		Map<String, String> raw = new LinkedHashMap<>();
		raw.put("X", "profile.Y A");
		raw.put("Y", "profile.X Z");
		ProfileSet p = ProfileSet.parse(raw, c);
		assertTrue(p.resolve("X").problems().stream().anyMatch(s -> s.contains("includes itself")), p.resolve("X").problems().toString());
		assertTrue(p.resolve("Y").problems().stream().anyMatch(s -> s.contains("unknown option \"Z\"")), p.resolve("Y").problems().toString());
	}

	@Test
	void screensExpandTheRestAndCheckLinks() {
		OptionCatalog c = catalog("A", "B", "C");
		Map<String, String> raw = new LinkedHashMap<>();
		raw.put("screen", "A [SUB] *");
		raw.put("screen.SUB", "B <empty>");
		raw.put("screen.SUB.columns", "3");
		ScreenTree t = ScreenTree.parse(raw, c);
		assertEquals(3, t.screens().get("SUB").columns());
		assertEquals(List.of(new ScreenTree.OptionItem("A"), new ScreenTree.LinkItem("SUB"), new ScreenTree.OptionItem("C")),
			t.screens().get("").items(), "the rest holds only options no screen names");
		assertTrue(t.problems().isEmpty(), t.problems().toString());
		ScreenTree broken = ScreenTree.parse(Map.of("screen", "[MISSING] NOPE"), c);
		assertEquals(2, broken.problems().size(), broken.problems().toString());
	}

	// ---- lang ----

	@Test
	void langFileNamesIgnoreCaseAndFallBackToEnUs() throws Exception {
		Path dir = Files.createTempDirectory("lang");
		Path shaders = dir.resolve("shaders");
		Files.createDirectories(shaders.resolve("lang"));
		Files.writeString(shaders.resolve("lang/en_US.lang"), "option.A=Alpha\n");
		try (PackSource pack = PackSource.open(dir)) {
			Lang fallback = Lang.select(pack, "de_de");
			assertEquals("Alpha", fallback.get("option.A").orElseThrow());
			assertEquals(Optional.of("lang/en_US.lang"), fallback.file());
			assertEquals("Alpha", Lang.select(pack, "EN_us").get("option.A").orElseThrow());
		}
	}

	@Test
	void targetIndexHandlesAliasesAndRange() {
		assertEquals(4, ProgramDirectives.targetIndex("gaux1"));
		assertEquals(-1, ProgramDirectives.targetIndex("colortex16"), "colortex16 is out of range");
	}
}
