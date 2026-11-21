// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.standardroad;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramTranslation;

/** Shared helpers of the standard-road tests. */
final class TestSupport {

	private TestSupport() {
	}

	/** The translator's translation, which must succeed; a failure carries its diagnostics. */
	static ProgramTranslation translateOk(ProgramRequest request) {
		ProgramTranslation t = Translator.translate(request);
		if (!t.ok()) {
			throw new AssertionError("translation failed: " + t.diagnostics());
		}
		return t;
	}
}
