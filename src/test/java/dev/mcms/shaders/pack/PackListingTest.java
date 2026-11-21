// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The shaderpacks listing: zips and folders with a shaders/ root, sorted without regard to case. */
class PackListingTest {
	@TempDir
	Path dir;

	private void folderPack(String name, boolean withShaders) throws IOException {
		Path p = dir.resolve(name);
		if (!withShaders) {
			Files.createDirectories(p);
			return;
		}
		Files.createDirectories(p.resolve("shaders"));
		Files.writeString(p.resolve("shaders/composite.fsh"), "void main() {}\n");
	}

	private void zipPack(String name, String... entries) throws IOException {
		try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(dir.resolve(name)))) {
			for (String e : entries) {
				z.putNextEntry(new ZipEntry(e));
				z.write("x".getBytes(StandardCharsets.UTF_8));
				z.closeEntry();
			}
		}
	}

	@Test
	void listsPacksWithAShadersRootSortedWithoutCase() throws IOException {
		folderPack("beta", true);
		folderPack("Alpha", true);
		folderPack("empty", false);
		zipPack("Gamma.zip", "shaders/composite.fsh");
		zipPack("delta.zip", "pack/shaders/composite.fsh");
		zipPack("readme-only.zip", "readme.txt");
		Files.writeString(dir.resolve("notes.txt"), "not a pack");
		Files.writeString(dir.resolve("corrupt.zip"), "not a zip");
		assertEquals(List.of("Alpha", "beta", "delta.zip", "Gamma.zip"), PackListing.list(dir));
	}

	@Test
	void missingFolderListsNothing() throws IOException {
		assertEquals(List.of(), PackListing.list(dir.resolve("absent")));
	}
}
