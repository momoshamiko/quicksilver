// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal.translator;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import java.util.List;

/** Shared helpers for translator tests: one-stage requests and a checked translation. */
final class TranslateSupport {
	private TranslateSupport() {
	}

	/** Lines map to the pack file "test.csh" with the same numbers. */
	static List<SourceLine> lines(String text) {
		int n = text.split("\n", -1).length;
		return java.util.stream.IntStream.rangeClosed(1, n).mapToObj(i -> new SourceLine("test", i)).toList();
	}

	static ProgramRequest.StageSource stage(Stage stage, String text) {
		return new ProgramRequest.StageSource(stage, text, lines(text));
	}

	/** A translation that must succeed; on failure the diagnostics go into the message. */
	static ProgramTranslation translateOk(ProgramRequest request) {
		ProgramTranslation t = Translator.translate(request);
		if (!t.ok()) {
			throw new AssertionError("translation failed: " + t.diagnostics());
		}
		return t;
	}

	static ProgramRequest compute(String text, List<ProgramRequest.BufferBinding> buffers,
			List<ProgramRequest.TextureBinding> textures, List<ProgramRequest.ImageBinding> images,
			List<ProgramRequest.ColorTarget> outputs) {
		return new ProgramRequest("test", ProgramRequest.Family.COMPUTE, List.of(stage(Stage.COMPUTE, text)), null,
			outputs, textures, images, buffers, ProgramRequest.UniformSchema.none(), null, false);
	}
}
