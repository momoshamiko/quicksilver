// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.expr;

import java.util.List;

/**
 * The immutable expression tree produced by {@link ExprParser}. Every node carries its 0-based character offset so
 * {@link ExprCompiler} can name the column of every error.
 */
public sealed interface Expr {
	/** 0-based character offset of this node's first token. */
	int pos();

	record Bool(boolean value, int pos) implements Expr {}

	/** A number literal; {@code integral} is true for an int literal ("2"), false for a float literal ("2.5", ".5", "1e3"). */
	record Num(double value, boolean integral, int pos) implements Expr {}

	record Name(String name, int pos) implements Expr {}

	/** Element access on a name: {@code v.x}, {@code color.r}, {@code matrix.0.1}. One or more selectors, in source order. */
	record Access(Expr base, List<String> selectors, int pos) implements Expr {}

	record Unary(String op, Expr operand, int pos) implements Expr {}

	record Binary(String op, Expr left, Expr right, int pos) implements Expr {}

	record Call(String function, List<Expr> args, int pos) implements Expr {}
}
