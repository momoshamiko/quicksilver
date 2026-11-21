// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** The {@code #version} line: the number and the optional profile ({@code core}, {@code compatibility}, null when absent). */
public record VersionDirective(Pos pos, int number, String profile) implements Directive {
}
