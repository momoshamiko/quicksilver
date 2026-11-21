// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link PackSource} over folders and zips: root discovery, reading, listing, case fallback. */
class PackSourceTest {
	@TempDir
	Path temp;

	private void writePack(Path packRoot) throws IOException {
		Files.createDirectories(packRoot.resolve("shaders/lib"));
		Files.createDirectories(packRoot.resolve("shaders/world0"));
		Files.writeString(packRoot.resolve("shaders/shaders.properties"), "sliders=BLUR\n");
		Files.writeString(packRoot.resolve("shaders/block.properties"), "block.10001=minecraft:stone\n");
		Files.writeString(packRoot.resolve("shaders/world0/composite.fsh"), "#include \"/lib/common.glsl\"\nvoid main() {}\n");
		Files.writeString(packRoot.resolve("shaders/lib/common.glsl"), "uniform float foo;\n");
	}

	private Path zipOf(String name, Map<String, String> entries) throws IOException {
		Path zip = temp.resolve(name);
		try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
			for (Map.Entry<String, String> e : entries.entrySet()) {
				z.putNextEntry(new ZipEntry(e.getKey()));
				z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
				z.closeEntry();
			}
		}
		return zip;
	}

	@Test
	void folderPackWithShadersAtTop() throws IOException {
		Path pack = temp.resolve("MyPack");
		writePack(pack);
		try (PackSource s = PackSource.open(pack)) {
			assertEquals("MyPack", s.name());
			assertEquals(Optional.of("sliders=BLUR\n"), s.readText("shaders.properties"));
			assertEquals(Optional.of("#include \"/lib/common.glsl\"\nvoid main() {}\n"), s.readText("world0/composite.fsh"));
			assertTrue(s.readText("missing.glsl").isEmpty());
			assertTrue(s.exists("lib/common.glsl"));
			assertTrue(s.exists("lib"));
			assertFalse(s.exists("nope.glsl"));
			assertEquals(List.of("block.properties", "lib/", "shaders.properties", "world0/"), s.list(""));
			assertEquals(List.of("common.glsl"), s.list("lib"));
			assertEquals(List.of(), s.list("no-folder"));
			assertTrue(s.warnings().isEmpty());
		}
	}

	@Test
	void zipPackWithShadersAtTop() throws IOException {
		Path zip = zipOf("Pack.zip", Map.of(
				"readme.txt", "hello",
				"shaders/shaders.properties", "a=b\n",
				"shaders/world0/composite.fsh", "void main() {}\n",
				"shaders/lib/common.glsl", "uniform float foo;\n"));
		try (PackSource s = PackSource.open(zip)) {
			assertEquals("Pack.zip", s.name());
			assertEquals(Optional.of("void main() {}\n"), s.readText("world0/composite.fsh"));
			assertEquals(Optional.of("a=b\n"), s.readText("shaders.properties"));
			assertTrue(s.exists("lib/common.glsl"));
			assertEquals(List.of("lib/", "shaders.properties", "world0/"), s.list(""));
			byte[] raw = s.readBytes("shaders.properties").orElseThrow();
			assertEquals("a=b\n", new String(raw, StandardCharsets.UTF_8));
		}
	}

	@Test
	void zipPackWithoutDirectoryEntries() throws IOException {
		Path zip = zipOf("NoDirs.zip", Map.of(
				"shaders/world0/composite.fsh", "void main() {}\n",
				"shaders/lib/a.glsl", "x\n"));
		try (PackSource s = PackSource.open(zip)) {
			assertEquals(List.of("lib/", "world0/"), s.list(""));
			assertTrue(s.exists("lib"));
			assertEquals(List.of("a.glsl"), s.list("lib"));
		}
	}

	@Test
	void shadersOneFolderDown() throws IOException {
		Path pack = temp.resolve("wrapped/inner");
		writePack(pack);
		try (PackSource s = PackSource.open(pack.getParent())) {
			assertEquals("wrapped", s.name());
			assertEquals(Optional.of("sliders=BLUR\n"), s.readText("shaders.properties"));
		}
		Path zip = zipOf("Wrapped.zip", Map.of(
				"inner/shaders/shaders.properties", "a=b\n",
				"inner/shaders/final.fsh", "void main() {}\n",
				"other/readme.txt", "x"));
		try (PackSource s = PackSource.open(zip)) {
			assertEquals(Optional.of("a=b\n"), s.readText("shaders.properties"));
			assertEquals(Optional.of("void main() {}\n"), s.readText("final.fsh"));
		}
	}

	@Test
	void decoyFoldersWithoutShadersAreIgnored() throws IOException {
		// The corpus has packs with decoy top-level folders next to shaders/; only folders holding shaders count.
		Path zip = zipOf("Decoy.zip", Map.of(
				"shaders/composite.fsh", "void main() {}\n",
				"decoy/placebo.txt", "not a pack",
				"readme.txt", "top level"));
		try (PackSource s = PackSource.open(zip)) {
			assertEquals(Optional.of("void main() {}\n"), s.readText("composite.fsh"));
		}
	}

	@Test
	void missingShadersFolderFails() throws IOException {
		Path zip = zipOf("Bad.zip", Map.of("readme.txt", "no shaders here"));
		assertThrows(IOException.class, () -> PackSource.open(zip));
		Path folder = Files.createDirectories(temp.resolve("empty"));
		assertThrows(IOException.class, () -> PackSource.open(folder));
	}

	@Test
	void twoCandidateWrapperFoldersFail() throws IOException {
		Path zip = zipOf("Two.zip", Map.of(
				"a/shaders/composite.fsh", "x",
				"b/shaders/composite.fsh", "y"));
		assertThrows(IOException.class, () -> PackSource.open(zip));
	}

	@Test
	void caseInsensitiveFallbackWarnsOncePerPath() throws IOException {
		Path pack = temp.resolve("CasePack");
		Files.createDirectories(pack.resolve("shaders/lib"));
		Files.writeString(pack.resolve("shaders/lib/Common.GLSL"), "uniform float foo;\n");
		try (PackSource s = PackSource.open(pack)) {
			assertEquals(Optional.of("uniform float foo;\n"), s.readText("lib/common.glsl"));
			assertEquals(Optional.of("uniform float foo;\n"), s.readText("lib/common.glsl"));
			assertEquals(1, s.warnings().size());
			assertTrue(s.warnings().get(0).contains("common.glsl"));
			assertTrue(s.exists("lib/COMMON.glsl"));
			assertEquals(List.of("Common.GLSL"), s.list("lib"));
			// A second distinct path gets its own warning.
			assertEquals(Optional.empty(), s.readText("lib/nope.glsl"));
			assertEquals(1, s.warnings().size());
		}
	}

	@Test
	void readTextStripsBomAndNormalizesLineEndings() throws IOException {
		Path pack = temp.resolve("BomPack");
		Files.createDirectories(pack.resolve("shaders"));
		Files.write(pack.resolve("shaders/a.fsh"), new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a', '\r', '\n', 'b', '\r', 'c', '\n'});
		try (PackSource s = PackSource.open(pack)) {
			assertEquals(Optional.of("a\nb\nc\n"), s.readText("a.fsh"));
		}
	}

	@Test
	void includeResolverResolvesAndReads() throws IOException {
		Path pack = temp.resolve("IncPack");
		writePack(pack);
		try (PackSource s = PackSource.open(pack)) {
			dev.mcms.glsl.preprocess.IncludeResolver r = s.includeResolver();
			Optional<dev.mcms.glsl.preprocess.SourceText> hit = r.resolve("world0/composite.fsh", "/lib/common.glsl");
			assertTrue(hit.isPresent());
			assertEquals("lib/common.glsl", hit.get().path());
			assertEquals("uniform float foo;\n", hit.get().text());
			assertTrue(r.resolve("world0/composite.fsh", "../lib/common.glsl").isPresent());
			assertTrue(r.resolve("world0/composite.fsh", "/missing.glsl").isEmpty());
			assertTrue(r.resolve("world0/composite.fsh", "../../outside.glsl").isEmpty());
		}
	}
}
