// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** A float literal, with or without the {@code f}/{@code F} suffix. */
public record FloatLiteral(Pos pos, String text, double value) implements Expr {
}
