// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** A call by name: a function, or a constructor the parser saw as a name ({@code S(...)} for a struct). */
public record NameCallee(Pos pos, String name) implements Callee {
}
