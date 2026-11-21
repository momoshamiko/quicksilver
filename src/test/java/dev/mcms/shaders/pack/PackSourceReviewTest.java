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

/** Review fixes for pack sources: zip entry names as stored, and folder listing after a case-only match. */
class PackSourceReviewTest {
	@TempDir
	Path temp;

	private Path zip(String name, Map<String, String> entries) throws IOException {
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
	void zipEntryWithBackslashesIsReadableByNormalizedPath() throws IOException {
		Path z = zip("Backslash.zip", Map.of("shaders\\world0\\composite.fsh", "void main() {}\n"));
		try (PackSource s = PackSource.open(z)) {
			assertEquals(Optional.of("void main() {}\n"), s.readText("world0/composite.fsh"));
			assertTrue(s.exists("world0/composite.fsh"));
		}
	}

	@Test
	void zipEntryWithDotSlashPrefixIsReadable() throws IOException {
		Path z = zip("DotSlash.zip", Map.of("./shaders/shaders.properties", "a=b\n"));
		try (PackSource s = PackSource.open(z)) {
			assertEquals(Optional.of("a=b\n"), s.readText("shaders.properties"));
		}
	}

	@Test
	void listingAfterCaseOnlyFolderMatchUsesTheRealFolder() throws IOException {
		Path z = zip("CaseFolder.zip", Map.of("shaders/Lib/common.glsl", "x\n", "shaders/Lib/util.glsl", "y\n"));
		try (PackSource s = PackSource.open(z)) {
			assertEquals(List.of("common.glsl", "util.glsl"), s.list("lib"));
			assertEquals(List.of("Lib/"), s.list(""));
		}
	}

	@Test
	void normalizeLeavesNoBackslashes() {
		assertEquals("world0/composite.fsh", PackPaths.normalize("world0\\composite.fsh"));
	}
}
