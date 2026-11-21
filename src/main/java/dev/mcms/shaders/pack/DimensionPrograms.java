// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * The programs one dimension resolves to.
 *
 * @param label        the row's name in the report ("Overworld", "Nether", ...)
 * @param dimension    the vanilla dimension, or empty for the wildcard's other dimensions
 * @param folder       the shader folder that serves it ("" for the root), or empty when no folder does
 * @param reason       why that folder
 * @param programs     every documented program with files or a served fallback, in catalog order
 * @param unrecognised the stage files of that folder whose names match no documented program (names without the extension)
 */
public record DimensionPrograms(
	String label,
	Optional<Dimension> dimension,
	Optional<String> folder,
	String reason,
	List<ResolvedProgram> programs,
	List<String> unrecognised) {

	public DimensionPrograms {
		programs = List.copyOf(programs);
		unrecognised = List.copyOf(unrecognised);
	}

	public long count(ResolvedProgram.Source source) {
		return programs.stream().filter(p -> p.source() == source).count();
	}

	/** The largest color target any program here writes or reads. */
	public OptionalInt largestTarget() {
		int max = -1;
		for (ResolvedProgram p : programs) {
			if (p.directives().isPresent()) {
				OptionalInt l = p.directives().get().largestTarget();
				if (l.isPresent()) max = Math.max(max, l.getAsInt());
			}
		}
		return max < 0 ? OptionalInt.empty() : OptionalInt.of(max);
	}

	/**
	 * The color targets that programs of a phase write or read, as their union: the opaque or the translucent gbuffers
	 * programs (the phase field of the catalog).
	 */
	public Set<Integer> targets(ProgramCatalog.Phase phase) {
		Set<Integer> out = new LinkedHashSet<>();
		for (ResolvedProgram p : programs) {
			if (!p.runs() || p.definition().phase() != phase || p.directives().isEmpty()) continue;
			ProgramDirectives d = p.directives().get();
			out.addAll(d.writes());
			out.addAll(d.reads());
		}
		return out;
	}

	/**
	 * The buffer settings the dimension's programs share: formats, clears, clear colours, mip flags and shadow constants, as
	 * one pack-wide union with conflicts reported (see {@link TargetUnion}). Constants are read per program; these are not.
	 */
	public TargetUnion union() {
		return TargetUnion.of(programs);
	}

	/** Names of the programs that run (directly or by fallback), in catalog order. */
	public List<String> runningNames() {
		List<String> out = new ArrayList<>();
		for (ResolvedProgram p : programs) {
			if (p.runs()) out.add(p.name());
		}
		return out;
	}
}
