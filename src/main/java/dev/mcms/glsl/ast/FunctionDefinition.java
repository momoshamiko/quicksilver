// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** A function definition: prototype plus body. */
public record FunctionDefinition(Pos pos, FunctionPrototype proto, BlockStmt body) implements Decl {
}
