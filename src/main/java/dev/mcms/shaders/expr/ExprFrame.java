// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.expr;

/**
 * The per-frame values the expressions read, one slot per name (the slot a {@link ExprBinding.Frame} names). The runtime
 * fills it once per frame before evaluating the pack's variables and uniforms in file order, and writes each result back
 * into its slot so later expressions read it.
 *
 * <p>All arrays are reused between frames; setters and getters allocate nothing. Every slot stores its value in the
 * array of its kind; vectors always occupy four floats ({@code vecs[slot * 4 + c]}), matrices sixteen floats
 * ({@code mats[slot * 16]}), in GLSL column-major order, so evaluation never branches on the layout.
 */
public final class ExprFrame {
	float[] floats = {};
	int[] ints = {};
	boolean[] bools = {};
	float[] vecs = {};
	float[] mats = {};

	/** Grows the slot arrays; call once per pack load with the number of names the pack uses. */
	public void ensureSlots(int slots) {
		if (slots > floats.length) {
			floats = new float[slots];
			ints = new int[slots];
			bools = new boolean[slots];
			vecs = new float[slots * 4];
			mats = new float[slots * 16];
		}
	}

	public void setFloat(int slot, float v) {
		floats[slot] = v;
	}

	public void setInt(int slot, int v) {
		ints[slot] = v;
	}

	public void setBool(int slot, boolean v) {
		bools[slot] = v;
	}

	public void setVec2(int slot, float x, float y) {
		int o = slot * 4;
		vecs[o] = x;
		vecs[o + 1] = y;
		vecs[o + 2] = 0f;
		vecs[o + 3] = 0f;
	}

	public void setVec3(int slot, float x, float y, float z) {
		int o = slot * 4;
		vecs[o] = x;
		vecs[o + 1] = y;
		vecs[o + 2] = z;
		vecs[o + 3] = 0f;
	}

	public void setVec4(int slot, float x, float y, float z, float w) {
		int o = slot * 4;
		vecs[o] = x;
		vecs[o + 1] = y;
		vecs[o + 2] = z;
		vecs[o + 3] = w;
	}

	/**
	 * Writes a 4x4 matrix in GLSL column-major order: element {@code M[col][row]} sits at {@code col * 4 + row}. An
	 * expression's {@code name.A.B} reads {@code M[A][B]}, column A and row B (the engine's reading, see
	 * {@code ExprCompiler}). This contradicts the wording of both docs: OptiFine says "row and column index", and the Iris
	 * page calls {@code gbufferModelView.0.3} the "1st row 4th column". The engine chose column first on corpus evidence
	 * (engine-pack-format.md section 13): the inverse-matrix products in three packs sum over the first index with the
	 * second fixed, a matrix-vector product only when the first index is the column; and one pack reads the view
	 * translation as the {@code .3.0} element, the GLSL position of a translation (column 3, row 0). The array is copied,
	 * so the caller may reuse it.
	 */
	public void setMat4(int slot, float[] columnMajor16) {
		System.arraycopy(columnMajor16, 0, mats, slot * 16, 16);
	}

	/** Writes a 3x3 matrix in GLSL column-major order ({@code M[col][row]} at {@code col * 3 + row}). */
	public void setMat3(int slot, float[] columnMajor9) {
		System.arraycopy(columnMajor9, 0, mats, slot * 16, 9);
	}

	public float getFloat(int slot) {
		return floats[slot];
	}

	public int getInt(int slot) {
		return ints[slot];
	}

	public boolean getBool(int slot) {
		return bools[slot];
	}

	/** Copies the slot's four vector floats into {@code out} (at least four elements). */
	public void getVec(int slot, float[] out) {
		int o = slot * 4;
		out[0] = vecs[o];
		out[1] = vecs[o + 1];
		out[2] = vecs[o + 2];
		out[3] = vecs[o + 3];
	}
}
