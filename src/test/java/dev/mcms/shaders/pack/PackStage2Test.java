// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.shaders.pack.textures.TextureImage;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for Stage 2 pack loading, zip safety, directives, profiles, and options fast path. */
final class PackStage2Test {

	@Test
	void macOsFinderZipMetadataIsIgnored(@TempDir Path dir) throws Exception {
		Path zip = dir.resolve("finder-rezipped.zip");
		try (OutputStream out = Files.newOutputStream(zip);
				ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
			putZip(zos, "__MACOSX/MyPack/shaders/._final.fsh", "garbage");
			putZip(zos, "__MACOSX/shaders/._final.fsh", "garbage");
			putZip(zos, "MyPack/shaders/._final.fsh", "garbage");
			putZip(zos, "MyPack/shaders/final.fsh", "void main() {}\n");
		}
		try (PackSource pack = PackSource.open(zip)) {
			assertTrue(pack.exists("final.fsh"));
			assertEquals(List.of("final.fsh"), pack.list(""));
		}
	}

	@Test
	void cp437ZipEntryNamesFallBackCleanly(@TempDir Path dir) throws Exception {
		Path zip = dir.resolve("cp437-pack.zip");
		Charset cp437 = Charset.forName("CP437");
		try (OutputStream out = Files.newOutputStream(zip);
				ZipOutputStream zos = new ZipOutputStream(out, cp437)) {
			// Non-ASCII CP437 byte sequence (not valid UTF-8) in a sibling readme entry
			putZip(zos, "caf\u00e9.txt", "hello");
			putZip(zos, "shaders/final.fsh", "void main() {}\n");
		}
		try (PackSource pack = PackSource.open(zip)) {
			assertTrue(pack.exists("final.fsh"));
		}
	}

	@Test
	void langSelectSurvivesMalformedUnicodeEscape(@TempDir Path dir) throws Exception {
		Path shaders = dir.resolve("shaders");
		Files.createDirectories(shaders.resolve("lang"));
		Files.writeString(shaders.resolve("final.fsh"), "void main() {}\n");
		Files.writeString(shaders.resolve("lang/en_us.lang"), "option.FOO=Bad \\uZZZZ escape\n");
		try (PackSource pack = PackSource.open(dir)) {
			Lang lang = Lang.select(pack, "en_us");
			assertTrue(lang.entries().isEmpty());
		}
	}

	@Test
	void alphaTestAcceptsAlwaysAndGlAlwaysCaseInsensitively() {
		ShaderProperties props = ShaderProperties.from(PropertiesText.parse("""
			alphaTest.gbuffers_terrain = ALWAYS 0.1
			alphaTest.gbuffers_water = GL_ALWAYS 0.5
			alphaTest.gbuffers_sky = OFF
			"""));
		assertTrue(props.problems().isEmpty(), props.problems().toString());
		assertEquals("ALWAYS", props.alphaTests().get("gbuffers_terrain").func());
		assertEquals("ALWAYS", props.alphaTests().get("gbuffers_water").func());
		assertTrue(props.alphaTests().get("gbuffers_sky").off());
	}

	@Test
	void programDirectivesIgnoresSingleLineCommentedDrawbuffers() {
		ProgramDirectives d = ProgramDirectives.of(List.of(new StageFile('f', "composite.fsh", """
			/* DRAWBUFFERS:02 */
			// /* DRAWBUFFERS:0134 */
			// RENDERTARGETS: 0,1,2,3
			void main() {}
			""")));
		assertEquals(List.of(0, 2), d.writes());
		assertEquals("DRAWBUFFERS", d.writeSource());
	}

	@Test
	void profileSetApplyResetsOtherProfileKeysAndKeepsUnrelatedManualTweaks(@TempDir Path dir) throws Exception {
		Path shaders = dir.resolve("shaders");
		Files.createDirectories(shaders);
		Files.writeString(shaders.resolve("final.fsh"), """
			#define QUALITY 1 // [0 1 2]
			#define EXTRA_ULTRA 0 // [0 1]
			#define USER_TWEAK 5 // [1 5 10]
			void main() {}
			""");
		Files.writeString(shaders.resolve("shaders.properties"), """
			profile.HIGH = QUALITY:1
			profile.ULTRA = profile.HIGH EXTRA_ULTRA:1
			""");
		try (PackSource pack = PackSource.open(dir)) {
			PackModel.OptionsData data = PackModel.loadOptions(pack, StandardMacros.engine(), "en_us", new Properties());
			OptionValues base = data.values().with(data.options(), Map.of("USER_TWEAK", "10"));
			OptionValues ultra = data.profiles().apply(base, "ULTRA");
			assertEquals("ULTRA", data.profiles().detect(ultra).orElseThrow().name());
			assertEquals("1", ultra.get("EXTRA_ULTRA"));
			assertEquals("10", ultra.get("USER_TWEAK"));

			OptionValues high = data.profiles().apply(ultra, "HIGH");
			assertEquals("HIGH", data.profiles().detect(high).orElseThrow().name(),
				"cycling ULTRA -> HIGH must reset EXTRA_ULTRA so HIGH is detected instead of staying stuck on ULTRA");
			assertEquals("0", high.get("EXTRA_ULTRA"));
			assertEquals("10", high.get("USER_TWEAK"), "options untouched by any profile must keep manual values");
		}
	}

	@Test
	void loadOptionsMatchesFullLoadMetadataAndBooleanValueLabels(@TempDir Path dir) throws Exception {
		Path shaders = dir.resolve("shaders");
		Files.createDirectories(shaders.resolve("lang"));
		Files.writeString(shaders.resolve("final.fsh"), """
			#define BLOOM // [true false]
			#define STEPS 4 // [2 4 8]
			void main() {}
			""");
		Files.writeString(shaders.resolve("shaders.properties"), """
			screen = BLOOM STEPS
			sliders = STEPS
			profile.LOW = !BLOOM STEPS:2
			""");
		Files.writeString(shaders.resolve("lang/en_us.lang"), """
			option.BLOOM=Bloom Glow
			value.BLOOM.true=Fancy
			value.BLOOM.false=None
			""");
		Properties overrides = new Properties();
		overrides.setProperty("STEPS", "8");
		try (PackSource pack = PackSource.open(dir)) {
			PackModel full = PackModel.load(pack, StandardMacros.engine(), "en_us", overrides);
			PackModel.OptionsData fast = PackModel.loadOptions(pack, StandardMacros.engine(), "en_us", overrides);
			assertEquals(full.options().options().keySet(), fast.options().options().keySet());
			assertEquals(full.values().values(), fast.values().values());
			assertEquals(full.profiles().names(), fast.profiles().names());
			assertEquals(full.screens().screens().keySet(), fast.screens().screens().keySet());
			assertEquals(full.lang().entries(), fast.lang().entries());

			OptionLabels labels = new OptionLabels(fast.lang(), Lang.select(pack, Lang.FALLBACK));
			assertTrue(labels.hasValueLabel("BLOOM", "true"));
			assertFalse(labels.hasValueLabel("STEPS", "8"));
			assertEquals("Fancy", labels.displayValue("BLOOM", "true"));
		}
	}

	@Test
	void textureImageRejectsCorruptBytesCleanly() {
		assertEquals(Optional.empty(), TextureImage.decodeImage(new byte[] {1, 2, 3, 4}));
	}

	private static void putZip(ZipOutputStream zos, String name, String content) throws Exception {
		zos.putNextEntry(new ZipEntry(name));
		zos.write(content.getBytes(StandardCharsets.UTF_8));
		zos.closeEntry();
	}
}
