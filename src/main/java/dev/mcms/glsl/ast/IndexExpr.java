// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** Array indexing {@code base[index]}. */
public record IndexExpr(Pos pos, Expr base, Expr index) implements Expr {
}
