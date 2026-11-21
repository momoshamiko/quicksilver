// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** Interpolation qualifier: {@code flat}, {@code smooth} or {@code noperspective}. */
public record InterpQual(Pos pos, InterpKind kind) implements Qualifier {
}
