// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** One {@code #pragma} line; {@code text} is everything after the word {@code pragma}, verbatim. */
public record PragmaDirective(Pos pos, String text) implements Directive {
}
