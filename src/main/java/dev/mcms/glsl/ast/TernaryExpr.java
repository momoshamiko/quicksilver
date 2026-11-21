// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** The ternary operator (right-associative). */
public record TernaryExpr(Pos pos, Expr condition, Expr thenExpr, Expr elseExpr) implements Expr {
}
