// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** Storage or parameter qualifier: {@code const}, {@code in}, {@code out}, {@code uniform}, {@code buffer}, ... */
public record StorageQual(Pos pos, StorageKind kind) implements Qualifier {
}
