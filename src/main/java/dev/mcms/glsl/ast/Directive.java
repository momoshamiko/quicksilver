// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** A preprocessor line the parser keeps: {@code #version}, {@code #extension} and {@code #pragma}. */
public sealed interface Directive extends GlslNode permits VersionDirective, ExtensionDirective, PragmaDirective {
}
