// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The packs in a {@code shaderpacks} folder: every zip file and every folder that {@link PackSource#open} accepts (a
 * {@code shaders/} root inside it, the same rule the engine uses to load a pack). Names are file names, sorted without
 * regard to case.
 */
public final class PackListing {
	/** The order pack names are listed in: without regard to case, then by the exact name. */
	public static final Comparator<String> ORDER = Comparator.comparing((String n) -> n.toLowerCase(Locale.ROOT))
		.thenComparing(Comparator.naturalOrder());

	private PackListing() {
	}

	/** The pack file names in the folder, in {@link #ORDER}; empty when the folder does not exist. */
	public static List<String> list(Path shaderpacks) throws IOException {
		if (!Files.isDirectory(shaderpacks)) return List.of();
		List<String> out = new ArrayList<>();
		List<Path> entries;
		try (Stream<Path> s = Files.list(shaderpacks)) {
			entries = s.toList();
		}
		for (Path p : entries) {
			String name = p.getFileName().toString();
			if (name.startsWith(".")) continue;
			boolean candidate = Files.isDirectory(p)
				|| (Files.isRegularFile(p) && name.toLowerCase(Locale.ROOT).endsWith(".zip"));
			if (candidate && opens(p)) out.add(name);
		}
		out.sort(ORDER);
		return out;
	}

	private static boolean opens(Path pack) {
		try (PackSource source = PackSource.open(pack)) {
			return true;
		} catch (IOException | UncheckedIOException e) {
			return false;
		}
	}
}
