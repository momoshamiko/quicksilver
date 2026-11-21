// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl;

import dev.mcms.glsl.ast.AssignExpr;
import dev.mcms.glsl.ast.AssignOp;
import dev.mcms.glsl.ast.BinaryExpr;
import dev.mcms.glsl.ast.BinaryOp;
import dev.mcms.glsl.ast.BuiltinTypeSpec;
import dev.mcms.glsl.ast.CallExpr;
import dev.mcms.glsl.ast.Decl;
import dev.mcms.glsl.ast.Expr;
import dev.mcms.glsl.ast.ExprStmt;
import dev.mcms.glsl.ast.FieldExpr;
import dev.mcms.glsl.ast.FloatLiteral;
import dev.mcms.glsl.ast.FunctionDefinition;
import dev.mcms.glsl.ast.GlslNode;
import dev.mcms.glsl.ast.Ident;
import dev.mcms.glsl.ast.IndexExpr;
import dev.mcms.glsl.ast.IntLiteral;
import dev.mcms.glsl.ast.PostfixExpr;
import dev.mcms.glsl.ast.ReturnStmt;
import dev.mcms.glsl.ast.Stmt;
import dev.mcms.glsl.ast.Trees;
import dev.mcms.glsl.ast.TypeCallee;
import dev.mcms.glsl.ast.UnaryExpr;
import dev.mcms.glsl.ast.UnaryOp;
import dev.mcms.glsl.ast.Unit;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether a full-screen program's vertex stage puts the engine's quad over the whole viewport (E9, the "don't care" load
 * actions): then the pass writes every texel of its targets, and their old texels need no load. The quad's corners are
 * (0, 0) to (1, 1) in {@code gl_Vertex} and {@code gl_ModelViewProjectionMatrix} maps them to the clip box (the engine's
 * full-screen contract). Packs shrink the quad to draw a corner of a buffer (Bliss's and Mellow's bloom tiles write
 * {@code gl_Position.xy} after {@code ftransform()}); the texels around such a quad must keep what they held.
 *
 * <p>Proven only for the forms the corpus uses, so any other program loads (a slower pass, never a wrong one): the
 * stage's one write of {@code gl_Position} is a statement of {@code main}'s own block (not inside a branch or a loop),
 * assigning the whole variable {@code ftransform()} (rewritten to {@code gl_ModelViewProjectionMatrix * gl_Vertex}),
 * the same product in two steps, or {@code vec4(gl_Vertex.xy * 2.0 - 1.0, 0.0, 1.0)}; and {@code main} has no
 * {@code return}. Any other write counts: an assignment to a component, an increment, or {@code gl_Position} passed to a
 * function.
 */
public final class ViewportCoverage {

	private ViewportCoverage() {
	}

	/** Whether the vertex stage (after the engine's rewrites) draws the quad over the whole viewport. */
	public static boolean covers(Unit vertex) {
		FunctionDefinition main = null;
		for (Decl decl : vertex.decls()) {
			if (decl instanceof FunctionDefinition f && f.proto().name().equals("main")) {
				if (main != null) {
					return false;
				}
				main = f;
			}
		}
		if (main == null) {
			return false;
		}
		List<GlslNode> writes = new ArrayList<>();
		Trees.transform(vertex, node -> {
			if (writesPosition(node)) {
				writes.add(node);
			}
			return node;
		});
		if (writes.size() != 1 || !(writes.get(0) instanceof AssignExpr assign) || assign.op() != AssignOp.ASSIGN
				|| !isPosition(assign.target()) || !coversFromCorners(assign.value())) {
			return false;
		}
		boolean topLevel = false;
		for (Stmt statement : main.body().statements()) {
			topLevel |= statement instanceof ExprStmt e && e.expr() == assign;
		}
		boolean[] returns = {false};
		Trees.transform(main.body(), node -> {
			returns[0] |= node instanceof ReturnStmt;
			return node;
		});
		return topLevel && !returns[0];
	}

	/** A write of {@code gl_Position} or a part of it, or {@code gl_Position} handed to a function (it may write it). */
	private static boolean writesPosition(GlslNode node) {
		if (node instanceof AssignExpr a) {
			return isPositionRoot(a.target());
		}
		if (node instanceof UnaryExpr u && (u.op() == UnaryOp.PRE_INC || u.op() == UnaryOp.PRE_DEC)) {
			return isPositionRoot(u.operand());
		}
		if (node instanceof PostfixExpr p) {
			return isPositionRoot(p.operand());
		}
		if (node instanceof CallExpr call) {
			for (Expr arg : call.args()) {
				if (isPositionRoot(arg)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean isPositionRoot(Expr e) {
		Expr root = e;
		while (true) {
			if (root instanceof FieldExpr f) {
				root = f.base();
			} else if (root instanceof IndexExpr i) {
				root = i.base();
			} else {
				break;
			}
		}
		return isPosition(root);
	}

	private static boolean isPosition(Expr e) {
		return e instanceof Ident i && i.name().equals("gl_Position");
	}

	/** The quad's corners mapped onto the clip box: the forms the class comment names. */
	private static boolean coversFromCorners(Expr value) {
		if (value instanceof BinaryExpr b && b.op() == BinaryOp.MUL) {
			// gl_ModelViewProjectionMatrix * gl_Vertex (ftransform), gl_ProjectionMatrix * (gl_ModelViewMatrix * gl_Vertex)
			// and (gl_ProjectionMatrix * gl_ModelViewMatrix) * gl_Vertex
			if (isName(b.left(), "gl_ModelViewProjectionMatrix") && isName(b.right(), "gl_Vertex")) {
				return true;
			}
			if (isName(b.left(), "gl_ProjectionMatrix") && b.right() instanceof BinaryExpr r && r.op() == BinaryOp.MUL
					&& isName(r.left(), "gl_ModelViewMatrix") && isName(r.right(), "gl_Vertex")) {
				return true;
			}
			return b.left() instanceof BinaryExpr l && l.op() == BinaryOp.MUL && isName(l.left(), "gl_ProjectionMatrix")
				&& isName(l.right(), "gl_ModelViewMatrix") && isName(b.right(), "gl_Vertex");
		}
		// vec4(gl_Vertex.xy * 2.0 - 1.0, 0.0, 1.0)
		if (value instanceof CallExpr call && call.callee() instanceof TypeCallee type
				&& type.spec() instanceof BuiltinTypeSpec spec && spec.name().equals("vec4") && type.dims().isEmpty()
				&& call.args().size() == 3) {
			return call.args().get(0) instanceof BinaryExpr sub && sub.op() == BinaryOp.SUB && isNumber(sub.right(), 1.0)
				&& sub.left() instanceof BinaryExpr mul && mul.op() == BinaryOp.MUL && isNumber(mul.right(), 2.0)
				&& mul.left() instanceof FieldExpr xy && xy.name().equals("xy") && isName(xy.base(), "gl_Vertex")
				&& isNumber(call.args().get(1), 0.0) && isNumber(call.args().get(2), 1.0);
		}
		return false;
	}

	private static boolean isName(Expr e, String name) {
		return e instanceof Ident i && i.name().equals(name);
	}

	private static boolean isNumber(Expr e, double value) {
		return e instanceof FloatLiteral f && f.value() == value || e instanceof IntLiteral i && i.value() == value;
	}
}
