// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

/**
 * Every expression form of GLSL. Field selection does not decide between a member and a swizzle
 * ({@link FieldExpr}); function and constructor calls share {@link CallExpr} and the callee tells a name from a type
 * with array dimensions or a method. {@link InitializerList} is here only to sit in a declarator's initializer slot.
 */
public sealed interface Expr extends GlslNode permits IntLiteral, UIntLiteral, FloatLiteral, DoubleLiteral,
		BoolLiteral, Ident, UnaryExpr, PostfixExpr, BinaryExpr, AssignExpr, TernaryExpr, SequenceExpr, CallExpr,
		IndexExpr, FieldExpr, InitializerList {
}
