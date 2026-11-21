// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** A declaration used as a statement. */
public record DeclStmt(Pos pos, Decl decl) implements Stmt {
}
