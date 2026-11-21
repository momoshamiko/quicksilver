// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import java.util.List;

/**
 * What the analyzer needs: the stage the program compiles for, whether driver leniencies are accepted with a warning
 * (the corpus needs them; strict mode turns each one into an error), and the line map (entry i is the original
 * origin of line i + 1 of the preprocessed text; empty when no map is given).
 */
public record SemaOptions(Stage stage, boolean lenient, List<SourceLine> lineMap) {
	public SemaOptions {
		lineMap = lineMap == null ? List.of() : List.copyOf(lineMap);
	}

	public static SemaOptions lenient(Stage stage, List<SourceLine> lineMap) {
		return new SemaOptions(stage, true, lineMap);
	}

	public static SemaOptions strict(Stage stage) {
		return new SemaOptions(stage, false, List.of());
	}
}
