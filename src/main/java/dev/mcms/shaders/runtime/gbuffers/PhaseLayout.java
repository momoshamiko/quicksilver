// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * One gbuffers phase's colour attachment layout (plan.md E4, "Pipeline mapping" and ADR 0006 section 3): the union of
 * the targets the phase's programs write, {@code colortex0} pinned at attachment 0 (a game-pipeline variant writes it),
 * the rest in ascending target order. Metal allows {@link #MAX_ATTACHMENTS} colour attachments per pass. {@link #of} takes
 * a union that fits and throws {@link GbufferPlans.LayoutException} for a larger one, naming the phase and its targets.
 *
 * <p>A gbuffers phase whose union is larger than that is split into attachment groups ({@link #groups}, 2026-11-15): each
 * group's union fits, the pass opens on group 0, and a draw whose program writes another group's targets switches the
 * open pass onto that group's attachments.
 *
 * <p>The shadow pass has its own layout ({@link #ofShadow}, E5): its targets are {@code shadowcolor} indices, and nothing
 * is pinned, since {@code shadowcolor0} is attachment 0 only because it is the first written.
 *
 * @param targets the target index at each attachment; {@code targets[0] == 0} for a gbuffers phase
 */
public record PhaseLayout(int[] targets) {

	/** Metal's colour attachment limit per pass (MTLRenderPassDescriptor colorAttachments: 8). */
	public static final int MAX_ATTACHMENTS = 8;

	public PhaseLayout {
		targets = targets.clone();
	}

	/**
	 * The layout of a phase whose programs write {@code written}; {@code phase} names the phase in the error.
	 *
	 * @throws GbufferPlans.LayoutException when the union has more than {@link #MAX_ATTACHMENTS} targets
	 */
	public static PhaseLayout of(String phase, Set<Integer> written) {
		TreeSet<Integer> union = new TreeSet<>(written);
		union.add(0);  // colortex0 at attachment 0 for every phase: game-pipeline variants write it
		if (union.size() > MAX_ATTACHMENTS) {
			throw new GbufferPlans.LayoutException("phase " + phase + " writes " + union.size()
				+ " colour targets " + union + "; Metal allows " + MAX_ATTACHMENTS + " attachments per pass");
		}
		List<Integer> ordered = new ArrayList<>(union.size());
		ordered.add(0);
		for (int target : union) {
			if (target != 0) {
				ordered.add(target);
			}
		}
		return new PhaseLayout(ordered.stream().mapToInt(Integer::intValue).toArray());
	}

	/**
	 * The attachment groups of a gbuffers phase (ADR 0006 section 3, the phase split): {@code layouts} in group order, where
	 * group 0 is the layout the phase's pass opens with, and {@code groupOf} the group of each program by its name.
	 */
	public record Groups(List<PhaseLayout> layouts, Map<String, Integer> groupOf) {
		public Groups {
			layouts = List.copyOf(layouts);
			groupOf = Map.copyOf(groupOf);
		}
	}

	/**
	 * Partitions a phase's programs into attachment groups (ADR 0006 section 3). A phase whose union of targets fits in
	 * {@link #MAX_ATTACHMENTS} is one group, exactly {@link #of}'s layout, so a phase that fits keeps its layout. Otherwise
	 * the outliers move to an overflow list until the main group fits: at each step the program whose removal shrinks the
	 * union the most moves out (the targets only it writes; a tie goes to the larger set, then to the later name, which
	 * keeps the Bliss pack's water and hand_water together). The overflow is partitioned the same way, one group after another.
	 *
	 * @param phase  the phase's name, for the error
	 * @param writes each program's written targets by program name (an empty set writes colortex0 only)
	 * @throws GbufferPlans.LayoutException a program's own set, with colortex0, has more than {@link #MAX_ATTACHMENTS} targets
	 */
	public static Groups groups(String phase, Map<String, Set<Integer>> writes) {
		TreeMap<String, Set<Integer>> own = new TreeMap<>();
		for (Map.Entry<String, Set<Integer>> program : writes.entrySet()) {
			TreeSet<Integer> set = new TreeSet<>(program.getValue());
			set.add(0);  // colortex0 at attachment 0 for every program: a game-pipeline variant writes it
			if (set.size() > MAX_ATTACHMENTS) {
				throw new GbufferPlans.LayoutException("phase " + phase + " program " + program.getKey() + " writes "
					+ set.size() + " colour targets " + set + "; Metal allows " + MAX_ATTACHMENTS + " attachments per pass");
			}
			own.put(program.getKey(), set);
		}
		List<PhaseLayout> layouts = new ArrayList<>();
		Map<String, Integer> groupOf = new TreeMap<>();
		Map<String, Set<Integer>> remaining = own;
		while (true) {
			TreeMap<String, Set<Integer>> main = new TreeMap<>(remaining);
			TreeMap<String, Set<Integer>> overflow = new TreeMap<>();
			while (unionOf(main).size() > MAX_ATTACHMENTS) {
				String outlier = outlier(main);
				overflow.put(outlier, main.remove(outlier));
			}
			int group = layouts.size();
			layouts.add(of(phase, unionOf(main)));
			for (String name : main.keySet()) {
				groupOf.put(name, group);
			}
			if (overflow.isEmpty()) {
				return new Groups(layouts, groupOf);
			}
			remaining = overflow;
		}
	}

	/** The union of the programs' targets with colortex0 (the group's targets, ascending). */
	private static TreeSet<Integer> unionOf(Map<String, Set<Integer>> programs) {
		TreeSet<Integer> union = new TreeSet<>();
		union.add(0);
		for (Set<Integer> written : programs.values()) {
			union.addAll(written);
		}
		return union;
	}

	/** The program whose removal shrinks the union of {@code main} the most (see {@link #groups} for the tie rule). */
	private static String outlier(Map<String, Set<Integer>> main) {
		String best = null;
		int bestExclusive = -1, bestSize = -1;
		for (Map.Entry<String, Set<Integer>> program : main.entrySet()) {
			int exclusive = 0;
			for (int target : program.getValue()) {
				if (target != 0 && writersOf(main, target) == 1) {
					exclusive++;
				}
			}
			int size = program.getValue().size();
			boolean wins = best == null || exclusive > bestExclusive
				|| exclusive == bestExclusive && (size > bestSize || size == bestSize && program.getKey().compareTo(best) > 0);
			if (wins) {
				best = program.getKey();
				bestExclusive = exclusive;
				bestSize = size;
			}
		}
		return best;
	}

	/** How many of the programs write {@code target}. */
	private static int writersOf(Map<String, Set<Integer>> programs, int target) {
		int writers = 0;
		for (Set<Integer> written : programs.values()) {
			if (written.contains(target)) {
				writers++;
			}
		}
		return writers;
	}

	/**
	 * The shadow pass's layout (E5): the {@code shadowcolor} targets the shadow programs write, in ascending index order,
	 * with no pinned target (an empty layout is a depth-only pass).
	 *
	 * @throws GbufferPlans.LayoutException when more than {@link #MAX_ATTACHMENTS} targets are written
	 */
	public static PhaseLayout ofShadow(Set<Integer> written) {
		TreeSet<Integer> union = new TreeSet<>(written);
		if (union.size() > MAX_ATTACHMENTS) {
			throw new GbufferPlans.LayoutException("the shadow pass writes " + union.size() + " shadowcolor targets "
				+ union + "; Metal allows " + MAX_ATTACHMENTS + " attachments per pass");
		}
		return new PhaseLayout(union.stream().mapToInt(Integer::intValue).toArray());
	}

	/** The attachment index a target holds in this layout; -1 when the layout has none. */
	public int attachmentOf(int target) {
		for (int i = 0; i < this.targets.length; i++) {
			if (this.targets[i] == target) {
				return i;
			}
		}
		return -1;
	}

	/** The number of attachments. */
	public int size() {
		return this.targets.length;
	}

	/**
	 * The feedback targets of a phase: the targets a program both samples and writes (the engine copies each before the
	 * phase, from its read texture into its write texture, so the sampler sees the pre-phase content; ADR 0006 section 3).
	 */
	public static Set<Integer> feedback(Set<Integer> read, Set<Integer> written) {
		TreeSet<Integer> both = new TreeSet<>(read);
		both.retainAll(written);
		return Set.copyOf(both);
	}
}
