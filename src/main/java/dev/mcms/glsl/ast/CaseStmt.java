// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** A {@code case} label inside a switch body. */
public record CaseStmt(Pos pos, Expr value) implements Stmt {
}
