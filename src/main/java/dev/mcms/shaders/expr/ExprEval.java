// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.expr;

/**
 * The per-frame evaluation context: the {@link ExprFrame} with every name's value, the time {@code smooth} and
 * {@code print} need, and the vector scratch space the compiled tree reuses.
 *
 * <p>One instance lives for the whole frame loop; nothing here allocates during evaluation. Create it with the maximum
 * {@link CompiledExpr#vectorScratch()} of the pack's expressions (plus one per vector result the caller evaluates into a
 * scratch instead of a slot).
 */
public final class ExprEval {
	/** Receives {@code print(id, n, x)} output; null disables printing. */
	public interface PrintSink {
		void print(int id, int everyN, String value);
	}

	public final ExprFrame frame;
	/** Seconds since the previous evaluation (the frame time); {@code smooth} decays by exp(-6.67 dt / fade) per evaluation. */
	public float deltaTime;
	/** Total time in seconds; {@code print} logs a value at most once per second of it. */
	public float timeSeconds;
	/** The print sink; null collects nothing. */
	public PrintSink print;

	/** Fixed for the evaluator's life: sized from the expressions at load, never grown on the frame path. */
	final float[] vecScratch;
	private int vecSp;

	/**
	 * @param vectorScratchCount scratch vectors to hold at once; use the maximum {@link CompiledExpr#vectorScratch()} of
	 *                           the expressions this evaluator runs. {@link CompiledExpr#requireScratch} checks it at load.
	 */
	public ExprEval(ExprFrame frame, int vectorScratchCount) {
		this.frame = frame;
		this.vecScratch = new float[Math.max(0, vectorScratchCount) * 4];
	}

	/** Scratch vectors this evaluator holds at once. */
	public int vectorCapacity() {
		return vecScratch.length / 4;
	}

	/**
	 * Pushes one scratch vector (four floats); returns its offset in {@link #vecScratch}. Package-private: nodes only.
	 * The scratch is sized at compile time ({@link CompiledExpr#vectorScratch()}), so an overflow is a sizing bug and
	 * fails loudly instead of growing: growing would allocate on the frame path.
	 */
	int vecPush() {
		if (vecSp + 4 > vecScratch.length) {
			throw new IllegalStateException("vector scratch overflow: the evaluator was sized smaller than the expression needs");
		}
		int p = vecSp;
		vecSp += 4;
		return p;
	}

	void vecPop() {
		vecSp -= 4;
	}
}
