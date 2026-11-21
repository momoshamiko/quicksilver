// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

import java.util.List;

/** A function prototype: return type with qualifiers, name (with its position) and parameters. */
public record FunctionPrototype(Pos pos, Type returnType, String name, Pos namePos, List<Param> params) implements Decl {
}
