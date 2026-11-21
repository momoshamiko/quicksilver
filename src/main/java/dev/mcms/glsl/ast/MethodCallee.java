// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/** A method call on an expression: GLSL's {@code array.length()}. The parser accepts any {@code recv.name(args)};
 * whether the method and its argument count are legal is semantic analysis's question. */
public record MethodCallee(Pos pos, Expr receiver, String name) implements Callee {
}
