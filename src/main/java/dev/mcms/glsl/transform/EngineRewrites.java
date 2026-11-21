// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.transform;

import dev.mcms.glsl.ast.BinaryExpr;
import dev.mcms.glsl.ast.BinaryOp;
import dev.mcms.glsl.ast.CallExpr;
import dev.mcms.glsl.ast.Expr;
import dev.mcms.glsl.ast.GlslNode;
import dev.mcms.glsl.ast.Ident;
import dev.mcms.glsl.ast.NameCallee;
import dev.mcms.glsl.ast.Trees;
import dev.mcms.glsl.ast.Unit;

/**
 * The AST rewrites the engine applies to a unit before semantic analysis (ADR 0006 section 2). They run first because
 * the analyzer types the rewritten tree: every node a rewrite adds is typed like any other node.
 *
 * <p>{@code ftransform()} is {@code gl_ModelViewProjectionMatrix * gl_Vertex} (GLSL 4.60 section 7.1 compatibility
 * built-ins: the compatibility profile defines it that way, and the engine serves both names from its prologue).
 */
public final class EngineRewrites {

	private EngineRewrites() {
	}

	/** Returns the unit with every {@code ftransform()} call replaced; the unit itself when there is none. */
	public static Unit apply(Unit unit) {
		GlslNode out = Trees.transform(unit, EngineRewrites::rewriteNode);
		return (Unit) out;
	}

	private static GlslNode rewriteNode(GlslNode node) {
		if (node instanceof CallExpr call && call.callee() instanceof NameCallee callee
				&& callee.name().equals("ftransform") && call.args().isEmpty()) {
			Expr matrix = new Ident(call.pos(), "gl_ModelViewProjectionMatrix");
			Expr vertex = new Ident(call.pos(), "gl_Vertex");
			return new BinaryExpr(call.pos(), BinaryOp.MUL, matrix, vertex);
		}
		return node;
	}
}
