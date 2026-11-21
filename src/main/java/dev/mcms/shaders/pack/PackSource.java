// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import dev.mcms.glsl.preprocess.IncludeResolver;
import dev.mcms.glsl.preprocess.SourceText;

/**
 * Read access to one shader pack: a zip file or a folder with a {@code shaders/} root. Paths given to the methods are
 * relative to {@code shaders/} in normalized {@code /}-separated form ({@code "world0/composite.fsh"},
 * {@code "lib/common.glsl"}).
 */
public interface PackSource extends Closeable {
	/**
	 * Opens a pack zip or folder and finds its {@code shaders/} root: at the top, or inside exactly one top-level folder.
	 * Fails when there is no such root or the candidate is ambiguous.
	 */
	static PackSource open(Path zipOrFolder) throws IOException {
		if (Files.isDirectory(zipOrFolder)) return new FolderPackSource(zipOrFolder);
		return new ZipPackSource(zipOrFolder);
	}

	/** The zip's or folder's file name. */
	String name();

	/**
	 * Reads a text file: UTF-8 (invalid bytes replaced), BOM removed, CR and CRLF turned into LF.
	 *
	 * @return empty when the path names no file
	 */
	Optional<String> readText(String path);

	/** Reads a file's raw bytes; empty when the path names no file. */
	Optional<byte[]> readBytes(String path);

	/** Whether the path names a file or folder under {@code shaders/}. */
	boolean exists(String path);

	/**
	 * The direct children of a folder under {@code shaders/} ({@code ""} is the root), sorted by name; folder names end
	 * in {@code /}. An unknown folder gives an empty list.
	 */
	List<String> list(String folder);

	/** Non-fatal problems found while reading, for example a path that only matched by ignoring case (once per path). */
	List<String> warnings();

	/** An {@link IncludeResolver} over this pack, using the preprocessor's include rules through {@link PackPaths#resolve}. */
	default IncludeResolver includeResolver() {
		return (fromPath, includeName) -> {
			Optional<String> p = PackPaths.resolve(fromPath, includeName);
			if (p.isEmpty()) return Optional.empty();
			return readText(p.get()).map(t -> new SourceText(p.get(), t));
		};
	}
}
