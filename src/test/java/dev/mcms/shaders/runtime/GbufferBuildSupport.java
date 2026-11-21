// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import dev.mcms.shaders.pack.DimensionPrograms;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.runtime.gbuffers.PrologueFacts;

/** Helpers shared by the gbuffers build tests: the dimension a corpus pack's Overworld draws from, and Sodium's facts. */
final class GbufferBuildSupport {

	/** The Sodium terrain prologue facts the coverage test registers (region slot 26, globals slot 21, beta.1 numbers). */
	static final PrologueFacts TERRAIN_FACTS = new PrologueFacts(26, 0, 21, 0, -1);

	private GbufferBuildSupport() {
	}

	/** The Overworld's programs (the dimension folder the engine loads in the Overworld), else the root folder. */
	static DimensionPrograms overworld(PackModel model) {
		for (DimensionPrograms programs : model.dimensions()) {
			if (programs.dimension().isPresent() && programs.dimension().get().id.equals("minecraft:overworld")) {
				return programs;
			}
		}
		return model.rootFolder();
	}
}
