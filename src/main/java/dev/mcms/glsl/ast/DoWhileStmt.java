// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** A {@code do ... while (...);} statement. */
public record DoWhileStmt(Pos pos, Stmt body, Expr condition) implements Stmt {
}
