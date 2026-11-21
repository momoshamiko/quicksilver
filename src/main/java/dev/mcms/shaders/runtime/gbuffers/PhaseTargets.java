// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import java.util.Arrays;
import java.util.TreeSet;

/**
 * One gbuffers phase's targets for the frame (2026-11-15, the phase split). {@code groups} is each attachment group's layout
 * in group order (colortex0 at attachment 0 of every group); group 0 is the layout the phase's pass opens with, and the
 * phase hook's split and the sky horizon use the groups. {@code all} is every target any group writes, ascending: the
 * phase's load copies and flips cover all of them, whichever group a draw uses.
 *
 * @param groups the attachment groups' layouts (target index per attachment), group 0 first
 * @param all    every target the phase writes, ascending
 */
public record PhaseTargets(int[][] groups, int[] all) {

	public PhaseTargets {
		groups = Arrays.stream(groups).map(int[]::clone).toArray(int[][]::new);
		all = all.clone();
	}

	/** A phase with one group: its layout is every target it writes. */
	public static PhaseTargets single(int[] layout) {
		TreeSet<Integer> targets = new TreeSet<>();
		for (int target : layout) {
			targets.add(target);
		}
		return new PhaseTargets(new int[][] {layout}, targets.stream().mapToInt(Integer::intValue).toArray());
	}

	/** The layout a pass of the phase opens with (group 0). */
	public int[] open() {
		return this.groups[0].clone();
	}
}
