// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** An expression condition. */
public record ExprCond(Pos pos, Expr expr) implements Cond {
}
