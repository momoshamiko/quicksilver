// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** An identifier: a variable, a function, a built-in like {@code gl_Vertex} or {@code ftransform}, or a type name in
 * an expression. Semantic analysis resolves it. */
public record Ident(Pos pos, String name) implements Expr {
}
