// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.transform;

import dev.mcms.glsl.ast.Ident;
import dev.mcms.glsl.ast.IndexExpr;
import dev.mcms.glsl.ast.IntLiteral;
import dev.mcms.glsl.ast.Trees;
import dev.mcms.glsl.ast.UIntLiteral;
import dev.mcms.glsl.ast.Unit;
import dev.mcms.glsl.sema.ConstValue;
import dev.mcms.glsl.sema.VariableSymbol;
import dev.mcms.glsl.types.ArrayType;
import dev.mcms.glsl.types.GlslType;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fragment outputs' locations (engine-pack-format section 9), shared by both roads. A named out sits at its
 * explicit {@code layout(location = N)}, else at the index its {@code outColor<n>} name carries. An out with neither
 * takes the first free run of locations in declaration order. Explicit and named locations are claimed first, so an
 * unnamed out never takes a number an explicit one asks for. An array takes one location per element, from its base.
 *
 * <p>A location is a listed target when it is below the request's target count. An out, or an array element, past the
 * listed targets takes no location and is dropped with a warning. Two outs claiming one listed location, a negative
 * location and a dual-source {@code index} are errors.
 *
 * <p>Only the {@code gl_FragData[i]} indices the source indexes exist as outputs: a {@code DRAWBUFFERS} set that skips a
 * buffer leaves that target unwritten, not materialised with zeros.
 */
public final class FragmentOutputs {
	private FragmentOutputs() {
	}

	private static final Pattern OUT_COLOR = Pattern.compile("outColor([0-9]+)");

	/** A location no request lists: the value an {@code outColor} name too large for a long carries. */
	private static final long BEYOND = Long.MAX_VALUE;

	/** A problem with an out: an error the pack must fix, or a warning the program runs with. */
	public record Problem(VariableSymbol var, boolean error, String code, String message) {
	}

	/**
	 * What a listed target holds: an out, or one element of an out array ({@code element} is -1 for a scalar or
	 * vector out).
	 */
	public record Slot(VariableSymbol var, int element) {
		/** The GLSL type the target's value has: the out's type, or an array's element type. */
		public GlslType type() {
			return element < 0 ? var.type() : ((ArrayType) var.type()).element();
		}
	}

	/**
	 * The outputs by listed target (only targets below the count; in target order) and the problems found, in
	 * declaration order.
	 */
	public record Assignment(Map<Integer, Slot> byTarget, List<Problem> problems) {
		public Assignment {
			byTarget = new TreeMap<>(byTarget);
			problems = List.copyOf(problems);
		}
	}

	/** One listed location's owner: an out, or one element of an out array (-1 for a scalar). */
	private record Claim(VariableSymbol var, int element) {
	}

	/**
	 * Assigns the fragment outputs to the request's targets. {@code outs} holds the fragment's out declarations (not
	 * builtins), in declaration order; {@code targets} is the number of listed targets. Every loop is bounded by the
	 * target count, so an array of any size costs at most that many claims.
	 */
	public static Assignment assign(List<VariableSymbol> outs, int targets) {
		List<Problem> problems = new ArrayList<>();
		TreeMap<Long, Claim> claims = new TreeMap<>();
		Set<VariableSymbol> beyond = new LinkedHashSet<>();
		List<VariableSymbol> unnamed = new ArrayList<>();
		for (VariableSymbol v : outs) {
			if (v.quals().layout().get("index") instanceof ConstValue c && c.isIntegral() && c.integerValue() != 0) {
				problems.add(new Problem(v, true, "output-dual-source",
					"'" + v.name() + "' has index " + c.integerValue()
						+ "; dual-source outputs (index) are not supported"));
				continue;
			}
			Long explicit = explicitLocation(v);
			if (explicit != null && explicit < 0) {
				problems.add(new Problem(v, true, "output-location-invalid",
					"'" + v.name() + "' has the negative location " + explicit));
				continue;
			}
			Long named = explicit != null ? explicit : outColorIndex(v.name());
			if (named == null) {
				unnamed.add(v);
			} else {
				claim(claims, problems, beyond, v, named, size(v), targets);
			}
		}
		for (VariableSymbol v : unnamed) {
			int count = size(v);
			long start = 0;
			while (!free(claims, start, count, targets)) {
				start++;
			}
			claim(claims, problems, beyond, v, start, count, targets);
		}
		Map<Integer, Slot> byTarget = new TreeMap<>();
		for (Map.Entry<Long, Claim> e : claims.entrySet()) {
			byTarget.put((int) (long) e.getKey(), new Slot(e.getValue().var(), e.getValue().element()));
		}
		for (VariableSymbol v : beyond) {
			problems.add(new Problem(v, false, "output-target-missing",
				"'" + v.name() + "' writes a location past the " + targets + " listed targets; the part past them is "
					+ "dropped"));
		}
		return new Assignment(byTarget, problems);
	}

	/** The elements an out occupies: an array's size, or one for a scalar or vector. */
	private static int size(VariableSymbol v) {
		return v.type() instanceof ArrayType at && at.size() != null ? at.size() : 1;
	}

	/** True when no listed location from {@code start} for {@code count} elements is claimed. */
	private static boolean free(TreeMap<Long, Claim> claims, long start, int count, int targets) {
		for (long i = 0; i < count && start < targets && start + i < targets; i++) {
			if (claims.containsKey(start + i)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Claims the listed locations of {@code count} consecutive elements from {@code start}. An element whose location is
	 * already claimed is a clash; an element past the listed targets is dropped and the out is recorded as beyond.
	 */
	private static void claim(TreeMap<Long, Claim> claims, List<Problem> problems, Set<VariableSymbol> beyond,
			VariableSymbol v, long start, int count, int targets) {
		boolean array = v.type() instanceof ArrayType;
		for (int i = 0; i < count; i++) {
			if (start >= targets || start + i >= targets) {
				beyond.add(v);
				return;
			}
			long location = start + i;
			Claim other = claims.get(location);
			if (other != null) {
				String mine = array ? v.name() + "[" + i + "]" : v.name();
				String theirs = other.element() < 0 ? other.var().name()
					: other.var().name() + "[" + other.element() + "]";
				problems.add(new Problem(v, true, "output-location-clash",
					"'" + mine + "' and '" + theirs + "' both write location " + location));
				continue;
			}
			claims.put(location, new Claim(v, array ? i : -1));
		}
	}

	/** The explicit {@code location} of an out, or null when it has none. */
	private static Long explicitLocation(VariableSymbol v) {
		if (v.quals().layout().get("location") instanceof ConstValue c && c.isIntegral()) {
			return c.integerValue();
		}
		return null;
	}

	/**
	 * The number an {@code outColor<n>} name carries (leading zeros parse as the number), or null for any other name. A
	 * number too large for a long is {@link #BEYOND}: past every listed target.
	 */
	private static Long outColorIndex(String name) {
		Matcher m = OUT_COLOR.matcher(name);
		if (!m.matches()) {
			return null;
		}
		BigInteger n = new BigInteger(m.group(1));
		return n.bitLength() < Long.SIZE ? n.longValue() : BEYOND;
	}

	/**
	 * The gl_FragData indices the source indexes, ascending. A non-constant index (or one past the int range) makes the
	 * set unknowable here: null, and the caller falls back to every index of the array.
	 */
	public static TreeSet<Integer> fragDataIndices(Unit unit) {
		boolean[] nonConstant = {false};
		TreeSet<Integer> indices = new TreeSet<>();
		Trees.transform(unit, node -> {
			if (node instanceof IndexExpr ix && ix.base() instanceof Ident id && id.name().equals("gl_FragData")) {
				if (ix.index() instanceof IntLiteral l && !l.exceedsInt32()) {
					indices.add((int) l.value());
				} else if (ix.index() instanceof UIntLiteral u && !u.exceedsUInt32()) {
					indices.add((int) u.value());
				} else {
					nonConstant[0] = true;
				}
			}
			return node;
		});
		return nonConstant[0] ? null : indices;
	}
}
