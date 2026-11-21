// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

import java.util.List;

/** One function parameter. The name is null in a prototype ({@code vec4 f(vec4, in float);}); {@code namePos} is null
 * with it. {@code dims} are the dimensions on the parameter's name ({@code in float a[2]}). */
public record Param(Pos pos, Type type, String name, Pos namePos, List<ArrayDim> dims) implements GlslNode {
}
