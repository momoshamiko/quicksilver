// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** Memory qualifier: {@code coherent}, {@code volatile}, {@code restrict}, {@code readonly} or {@code writeonly}. */
public record MemoryQual(Pos pos, MemoryKind kind) implements Qualifier {
}
