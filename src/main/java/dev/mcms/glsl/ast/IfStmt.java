// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** {@code if} with optional {@code else}; the condition may be a declaration (GLSL 4.40). */
public record IfStmt(Pos pos, Cond condition, Stmt thenStmt, Stmt elseStmt) implements Stmt {
}
