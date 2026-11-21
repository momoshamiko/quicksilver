// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * {@link PackModel#declaresDistantPrograms}: the pack has a {@code dh_terrain} or {@code dh_water} program in some folder. The
 * engine reads it before the pack loads, to set the DISTANT_HORIZONS define (plan.md E7). {@code dh_shadow} is not one: DH stays
 * out of the shadow pass.
 */
class PackModelDistantProgramsTest {

	/** A pack whose files are the given paths under {@code shaders/}. */
	private record Files(Set<String> paths) implements PackSource {
		@Override
		public String name() {
			return "test";
		}

		@Override
		public void close() {
		}

		@Override
		public Optional<String> readText(String path) {
			return Optional.empty();
		}

		@Override
		public Optional<byte[]> readBytes(String path) {
			return Optional.empty();
		}

		@Override
		public boolean exists(String path) {
			return this.paths.contains(path);
		}

		@Override
		public List<String> list(String folder) {
			String prefix = folder.isEmpty() ? "" : folder + "/";
			Set<String> out = new TreeSet<>();
			for (String path : this.paths) {
				if (!path.startsWith(prefix)) {
					continue;
				}
				String rest = path.substring(prefix.length());
				int slash = rest.indexOf('/');
				out.add(slash < 0 ? rest : rest.substring(0, slash + 1));
			}
			return List.copyOf(out);
		}

		@Override
		public List<String> warnings() {
			return List.of();
		}
	}

	private static boolean declares(String... paths) {
		return PackModel.declaresDistantPrograms(new Files(Set.of(paths)));
	}

	@Test
	void aDhTerrainProgramAtTheRootCounts() {
		assertTrue(declares("dh_terrain.vsh", "dh_terrain.fsh"));
	}

	@Test
	void aDhWaterProgramInADimensionFolderCounts() {
		assertTrue(declares("world0/dh_water.vsh", "world0/dh_water.fsh"));
	}

	@Test
	void aPackWithoutDistantProgramsDoesNotCount() {
		assertFalse(declares("gbuffers_terrain.vsh", "composite.fsh", "world0/gbuffers_water.fsh"));
	}

	@Test
	void aDhShadowProgramDoesNotCount() {
		assertFalse(declares("world0/dh_shadow.vsh", "world0/dh_shadow.fsh"));
	}

	@Test
	void anEmptyPackDoesNotCount() {
		assertFalse(declares());
	}
}
