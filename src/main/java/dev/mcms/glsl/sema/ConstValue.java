// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import dev.mcms.glsl.types.GlslType;
import dev.mcms.glsl.types.ScalarKind;
import dev.mcms.glsl.types.ScalarType;
import dev.mcms.glsl.types.VectorType;

import java.util.Arrays;

/**
 * A folded constant: a scalar, or a composite (vector, matrix, array or struct) of constants. Scalar payloads:
 * {@code Boolean} for bool, {@code Long} for int and uint (a 32-bit value, uint kept as its bit pattern), and
 * {@code Double} for float and double (float results are rounded to float precision). Composite parts are
 * vector components, matrix columns, array elements or struct fields in declaration order.
 */
public final class ConstValue {
	private final GlslType type;
	private final Object data;

	private ConstValue(GlslType type, Object data) {
		this.type = type;
		this.data = data;
	}

	public static ConstValue of(boolean b) {
		return new ConstValue(ScalarType.BOOL, b);
	}

	/** An int constant, wrapped to 32 bits as GLSL integers are. */
	public static ConstValue ofInt(long v) {
		return new ConstValue(ScalarType.INT, (long) (int) v);
	}

	/** A uint constant, wrapped to 32 bits (the value is kept as its unsigned bit pattern). */
	public static ConstValue ofUint(long v) {
		return new ConstValue(ScalarType.UINT, v & 0xFFFFFFFFL);
	}

	public static ConstValue ofFloat(double v) {
		return new ConstValue(ScalarType.FLOAT, (double) (float) v);
	}

	public static ConstValue ofDouble(double v) {
		return new ConstValue(ScalarType.DOUBLE, v);
	}

	/** A scalar of the given kind from a double (used by folding and conversions). */
	static ConstValue scalarOf(ScalarKind kind, double real) {
		return switch (kind) {
			case BOOL -> of(real != 0.0);
			case INT -> ofInt((long) real);
			case UINT -> ofUint((long) real);
			case FLOAT -> ofFloat(real);
			case DOUBLE -> ofDouble(real);
		};
	}

	public static ConstValue vector(GlslType type, ConstValue... components) {
		return new ConstValue(type, components.clone());
	}

	public static ConstValue matrix(GlslType type, ConstValue... columns) {
		return new ConstValue(type, columns.clone());
	}

	public static ConstValue array(GlslType type, ConstValue... elements) {
		return new ConstValue(type, elements.clone());
	}

	public static ConstValue struct(GlslType type, ConstValue... fields) {
		return new ConstValue(type, fields.clone());
	}

	public GlslType type() {
		return type;
	}

	public boolean isScalar() {
		return type instanceof ScalarType;
	}

	/** True for an int or uint scalar, the constants {@link #integerValue()} reads. */
	public boolean isIntegral() {
		return isScalar() && (type.componentKind() == ScalarKind.INT || type.componentKind() == ScalarKind.UINT);
	}

	public boolean booleanValue() {
		return (Boolean) data;
	}

	/** The signed value of an int scalar. */
	public long intValue() {
		return (Long) data;
	}

	/** The bit pattern of a uint scalar. */
	public long uintValue() {
		return (Long) data;
	}

	/** The value of a float or double scalar. */
	public double floatValue() {
		return (Double) data;
	}

	/** The integer value of an int or uint scalar, as a long (uint values are not negative). */
	public long integerValue() {
		return type.componentKind() == ScalarKind.UINT ? uintValue() : intValue();
	}

	/** Components, columns, elements or fields. Do not mutate. */
	public ConstValue[] parts() {
		return (ConstValue[]) data;
	}

	/** All scalar leaves in order (vector: components; matrix: column by column). */
	public ConstValue[] flatten() {
		if (isScalar()) {
			return new ConstValue[] {this};
		}
		ConstValue[] parts = parts();
		if (type instanceof VectorType) {
			return parts;
		}
		return Arrays.stream(parts).flatMap(p -> Arrays.stream(p.flatten())).toArray(ConstValue[]::new);
	}

	/** The scalar kind of every leaf. */
	public ScalarKind kind() {
		return type.componentKind();
	}

	/** The value converted to a scalar kind, with the constructor rules (GLSL 4.60 section 5.4.1). */
	public ConstValue convert(ScalarKind target) {
		if (kind() == target) {
			return this;
		}
		if (kind() == ScalarKind.BOOL) {
			return scalarOf(target, booleanValue() ? 1.0 : 0.0);
		}
		if (kind().isFloating() && target.isInteger()) {
			// float to int truncates toward zero; out-of-range values are undefined in GLSL
			double d = floatValue();
			return target == ScalarKind.INT ? ofInt((long) d) : ofUint((long) d);
		}
		if (target == ScalarKind.BOOL) {
			return of(!isZero());
		}
		return switch (kind()) {
			case INT -> scalarOf(target, (double) intValue());
			case UINT -> scalarOf(target, (double) uintValue());
			default -> scalarOf(target, floatValue());
		};
	}

	/** The value converted for an implicit conversion to {@code target}, component by component. */
	public ConstValue implicitTo(GlslType target) {
		if (target instanceof ScalarType st) {
			return convert(st.kind());
		}
		if (target instanceof VectorType tv && type instanceof VectorType) {
			ConstValue[] out = new ConstValue[tv.size()];
			for (int i = 0; i < out.length; i++) {
				out[i] = parts()[i].convert(tv.component());
			}
			return vector(tv, out);
		}
		if (target instanceof dev.mcms.glsl.types.MatrixType tm && type instanceof dev.mcms.glsl.types.MatrixType) {
			ConstValue[] out = new ConstValue[tm.cols()];
			for (int i = 0; i < out.length; i++) {
				out[i] = parts()[i].implicitTo(tm.columnType());
			}
			return matrix(tm, out);
		}
		return this;
	}

	public boolean isZero() {
		return switch (kind()) {
			case BOOL -> !booleanValue();
			case INT, UINT -> integerValue() == 0;
			case FLOAT, DOUBLE -> floatValue() == 0.0;
		};
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof ConstValue other) || !type.equals(other.type)) {
			return false;
		}
		if (isScalar()) {
			return data.equals(other.data);
		}
		return Arrays.equals(parts(), other.parts());
	}

	@Override
	public int hashCode() {
		return isScalar() ? 31 * type.hashCode() + data.hashCode() : 31 * type.hashCode() + Arrays.hashCode(parts());
	}

	@Override
	public String toString() {
		if (isScalar()) {
			return switch (kind()) {
				case BOOL -> String.valueOf(booleanValue());
				case INT -> String.valueOf(intValue());
				case UINT -> uintValue() + "u";
				case FLOAT -> floatValue() + "f";
				case DOUBLE -> floatValue() + "lf";
			};
		}
		return type + Arrays.toString(parts());
	}
}
