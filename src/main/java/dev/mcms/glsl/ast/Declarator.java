// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

import java.util.List;

/** One declarator of a declaration: name, array dimensions and optional initializer. */
public record Declarator(Pos pos, String name, List<ArrayDim> dims, Expr initializer) implements GlslNode {
}
