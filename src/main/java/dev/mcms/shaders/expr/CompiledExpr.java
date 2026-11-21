// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.expr;

/**
 * One expression after parsing, name resolution, type checking and compilation. Names are already frame slots, so
 * evaluation does no name lookup, no boxing and no allocation; the per-frame state of {@code smooth}, {@code random}
 * and {@code print} lives in the tree (per call site) and survives between frames.
 */
public final class CompiledExpr {
	private final ExprNode root;

	CompiledExpr(ExprNode root) {
		this.root = root;
	}

	public ExprType type() {
		return root.type();
	}

	/** Scratch vectors this expression holds at once; size the {@link ExprEval} with the maximum of an expression set. */
	public int vectorScratch() {
		return root.scratch();
	}

	/**
	 * Fails at load when {@code e} holds fewer scratch vectors than this expression needs, so an undersized evaluator
	 * never reaches a frame (where it would throw). Call it once per evaluator and expression set.
	 */
	public void requireScratch(ExprEval e) {
		if (e.vectorCapacity() < root.scratch()) {
			throw new IllegalArgumentException("evaluator holds " + e.vectorCapacity()
				+ " scratch vectors, the expression needs " + root.scratch());
		}
	}

	public float evalF(ExprEval e) {
		return root.evalF(e);
	}

	public int evalI(ExprEval e) {
		return root.evalI(e);
	}

	public boolean evalB(ExprEval e) {
		return root.evalB(e);
	}

	/** Writes the vector's components to {@code out[off]} onwards ({@link ExprType#components()} floats). */
	public void evalV(ExprEval e, float[] out, int off) {
		root.evalV(e, out, off);
	}
}
