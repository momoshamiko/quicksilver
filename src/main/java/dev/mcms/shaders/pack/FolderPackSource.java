// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/** A {@link PackSource} over a pack folder on disk. The file tree is read once when the source is opened. */
final class FolderPackSource extends IndexedPackSource {
	private final Path root;

	FolderPackSource(Path folder) throws IOException {
		super(folder.getFileName() == null ? folder.toString() : folder.getFileName().toString(), scan(folder));
		this.root = folder;
	}

	private static Map<String, Boolean> scan(Path folder) throws IOException {
		Map<String, Boolean> entries = new HashMap<>();
		try (Stream<Path> walk = Files.walk(folder)) {
			walk.forEach(p -> {
				boolean isDir = Files.isDirectory(p);
				Path rel = folder.relativize(p);
				if (rel.getNameCount() == 0) return;
				entries.put(rel.toString().replace('\\', '/'), isDir);
			});
		}
		return entries;
	}

	@Override
	protected byte[] readEntry(String packPath) {
		try {
			Path p = root.resolve(packPath);
			if (!Files.isRegularFile(p)) return null;
			return Files.readAllBytes(p);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
