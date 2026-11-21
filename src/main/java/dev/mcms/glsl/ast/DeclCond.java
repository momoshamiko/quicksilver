// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** A declaration used as a condition ({@code if (bool ok = test();} GLSL 4.40): exactly one declarator with its
 * initializer. */
public record DeclCond(Pos pos, VarDecl decl) implements Cond {
}
