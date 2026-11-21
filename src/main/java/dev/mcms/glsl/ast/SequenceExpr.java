// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

import java.util.List;

/** The comma operator: two or more assignment expressions in sequence. */
public record SequenceExpr(Pos pos, List<Expr> items) implements Expr {
}
