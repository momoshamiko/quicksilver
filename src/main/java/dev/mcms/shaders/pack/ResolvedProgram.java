// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.util.List;
import java.util.Optional;

/**
 * One documented program in one dimension, after resolution.
 *
 * @param name        the documented name (composite2, gbuffers_water, ...)
 * @param definition  its catalog entry
 * @param source      where its files come from
 * @param servedBy    the program whose files run: this one, or the fallback it resolved to; empty when none
 * @param disabledBy  the rule or profile that switched this program off, when its own files exist; empty otherwise
 * @param stages      the preprocessed stages that run (empty for absent and virtual programs)
 * @param errors      preprocessor errors of the stages (a program with errors has no directives)
 * @param directives  its targets and constants; empty when there are no stages or errors
 */
public record ResolvedProgram(
	String name,
	ProgramCatalog.Program definition,
	Source source,
	Optional<String> servedBy,
	Optional<String> disabledBy,
	List<StageFile> stages,
	List<String> errors,
	Optional<ProgramDirectives> directives) {

	/** Where a program's files come from. */
	public enum Source {
		/** The program's own files run. */
		DIRECT,
		/** The program has no files of its own (or they are switched off); its fallback's files run. */
		FALLBACK,
		/** The program's own files exist but are switched off, and no fallback serves. */
		DISABLED,
		/** No files and no fallback serves it. */
		ABSENT,
		/** A documented virtual program: it only flips buffers. */
		VIRTUAL
	}

	public ResolvedProgram {
		stages = List.copyOf(stages);
		errors = List.copyOf(errors);
	}

	/** Whether the program runs some shader code in this dimension. */
	public boolean runs() {
		return source == Source.DIRECT || source == Source.FALLBACK;
	}
}
