// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.util.List;

/**
 * The three vanilla dimensions, with the legacy folder names OptiFine defines (engine-pack-format.md section 2). The aliases
 * {@code minecraft:nether} and {@code minecraft:end} are used by some packs' dimension.properties; they are accepted here.
 */
public enum Dimension {
	OVERWORLD("minecraft:overworld", "world0", List.of("minecraft:overworld")),
	NETHER("minecraft:the_nether", "world-1", List.of("minecraft:the_nether", "minecraft:nether")),
	END("minecraft:the_end", "world1", List.of("minecraft:the_end", "minecraft:end"));

	/** The dimension id as Minecraft names it. */
	public final String id;
	/** The legacy folder under {@code shaders/}. */
	public final String legacyFolder;
	/** Ids that name this dimension in dimension.properties. */
	public final List<String> names;

	Dimension(String id, String legacyFolder, List<String> names) {
		this.id = id;
		this.legacyFolder = legacyFolder;
		this.names = names;
	}
}
