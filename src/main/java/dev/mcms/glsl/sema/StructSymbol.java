// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import dev.mcms.glsl.ast.Pos;
import dev.mcms.glsl.types.StructType;

/** A struct type name in scope. */
public record StructSymbol(String name, StructType struct, Pos pos, Object declaration) implements Symbol {
}
