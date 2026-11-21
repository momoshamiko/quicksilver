// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** One {@code #extension} line: the extension or {@code all}, and the behavior ({@code require}, {@code enable},
 * {@code warn}, {@code disable}). */
public record ExtensionDirective(Pos pos, String name, String behavior) implements Directive {
}
