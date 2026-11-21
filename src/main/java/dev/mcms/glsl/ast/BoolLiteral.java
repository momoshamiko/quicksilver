// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** {@code true} or {@code false}. */
public record BoolLiteral(Pos pos, boolean value) implements Expr {
}
