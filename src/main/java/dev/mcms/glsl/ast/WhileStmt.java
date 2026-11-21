// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** A {@code while} statement; the condition may be an expression or a declaration (GLSL 4.40). */
public record WhileStmt(Pos pos, Cond condition, Stmt body) implements Stmt {
}
