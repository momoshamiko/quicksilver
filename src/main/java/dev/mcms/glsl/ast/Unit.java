// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One shader source file (a translation unit): the {@code #version} line, the {@code #extension} and {@code #pragma}
 * lines, and the external declarations.
 *
 * <p>{@link #decls()} is the declaration order the tree holds, and rewrites may reorder or insert declarations there.
 * {@link #itemsInOrder()} keeps that list order and places each directive before the first declaration (in list order)
 * whose position follows the directive's position. Declarations without a position (line 0, synthesized) never pull
 * a directive forward, so they stay where the list puts them.
 */
public record Unit(Pos pos, VersionDirective version, List<Directive> directives, List<Decl> decls) implements GlslNode {
	private static final Comparator<Directive> BY_POSITION = Comparator.<Directive>comparingInt(n -> n.pos().line())
			.thenComparingInt(n -> n.pos().column());

	/** Directives slotted into the declaration list; the result is what the printer emits, in order. */
	public List<GlslNode> itemsInOrder() {
		List<Directive> pending = new ArrayList<>(directives);
		pending.sort(BY_POSITION);
		List<GlslNode> items = new ArrayList<>(directives.size() + decls.size());
		int next = 0;
		for (Decl d : decls) {
			if (isPositioned(d.pos())) {
				while (next < pending.size() && isBefore(pending.get(next).pos(), d.pos())) {
					items.add(pending.get(next++));
				}
			}
			items.add(d);
		}
		while (next < pending.size()) {
			items.add(pending.get(next++));
		}
		return List.copyOf(items);
	}

	private static boolean isPositioned(Pos p) {
		return p != null && p.line() > 0;
	}

	private static boolean isBefore(Pos a, Pos b) {
		return a.line() < b.line() || (a.line() == b.line() && a.column() < b.column());
	}
}
