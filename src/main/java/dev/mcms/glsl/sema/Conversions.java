// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import dev.mcms.glsl.types.GlslType;
import dev.mcms.glsl.types.MatrixType;
import dev.mcms.glsl.types.ScalarKind;
import dev.mcms.glsl.types.ScalarType;
import dev.mcms.glsl.types.VectorType;

/**
 * The implicit conversions of GLSL (section 4.1.10 of each specification). {@link #convertsIn} is the table of one
 * {@code #version}: no conversion before 1.20, int to float from 1.20, uint to float from 1.30, and int to uint and the
 * double targets from 4.00. {@link #converts} is the 4.00 table, which holds at every {@code #version}: the engine runs
 * the feature set of a GL 4.6 driver whatever the unit says (ADR 0006 E6, the SRC_GL_460 decision), so a pack written for
 * an older version gets the conversions the driver has (the a081cf97 compatibility rule). Scalar conversions lift
 * component-wise to vectors of the same size and float matrices to the double matrix of the same shape. Arrays and
 * structs never convert, and no conversion narrows.
 */
public final class Conversions {
	/** The version whose table holds at every {@code #version} (the compatibility rule of a081cf97). */
	public static final int COMPATIBILITY_VERSION = 400;

	private Conversions() {
	}

	/** True when {@code from} implicitly converts to {@code to} under the 4.00 table, at every version (the error type
	 * converts to everything, so one error does not cascade). */
	public static boolean converts(GlslType from, GlslType to) {
		return convertsIn(from, to, COMPATIBILITY_VERSION);
	}

	/** True when {@code from} implicitly converts to {@code to} under the conversions of GLSL {@code version} (the error
	 * type converts to everything, so one error does not cascade). */
	public static boolean convertsIn(GlslType from, GlslType to, int version) {
		if (from.isError() || to.isError()) {
			return true;
		}
		if (from.equals(to)) {
			return false; // no conversion needed
		}
		if (from instanceof ScalarType f && to instanceof ScalarType t) {
			return scalarConverts(f.kind(), t.kind(), version);
		}
		if (from instanceof VectorType f && to instanceof VectorType t && f.size() == t.size()) {
			return scalarConverts(f.component(), t.component(), version);
		}
		if (from instanceof MatrixType f && to instanceof MatrixType t && f.cols() == t.cols()
				&& f.rows() == t.rows()) {
			return f.component() == ScalarKind.FLOAT && t.component() == ScalarKind.DOUBLE && version >= 400;
		}
		return false;
	}

	private static boolean scalarConverts(ScalarKind from, ScalarKind to, int version) {
		if (from == to || version < 120) {
			return false;
		}
		// GLSL 1.20 has int to float only; 1.30 and 3.30 add uint to float and have no conversion between signed and
		// unsigned integers; 4.00 adds int to uint, int to double, uint to double and float to double.
		return switch (from) {
			case INT -> switch (to) {
				case FLOAT -> true;
				case UINT, DOUBLE -> version >= 400;
				default -> false;
			};
			case UINT -> switch (to) {
				case FLOAT -> version >= 130;
				case DOUBLE -> version >= 400;
				default -> false;
			};
			case FLOAT -> to == ScalarKind.DOUBLE && version >= 400;
			default -> false;
		};
	}
}
