// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl;

import dev.mcms.glsl.types.ArrayType;
import dev.mcms.glsl.types.GlslType;
import dev.mcms.glsl.types.MatrixType;
import dev.mcms.glsl.types.ScalarKind;
import dev.mcms.glsl.types.ScalarType;
import dev.mcms.glsl.types.StructField;
import dev.mcms.glsl.types.StructType;
import dev.mcms.glsl.types.VectorType;
import java.util.List;

/**
 * Byte layouts of GLSL blocks (GLSL 4.60 section 7.6.2.2 std140 and 7.6.2.4 std430) and of their Metal mirrors. A block
 * keeps the GLSL offsets exactly: the emitter writes explicit padding where Metal's natural layout would place a member
 * elsewhere, and a vec3 member that is not an array is written as {@code packed_} so its three floats take twelve bytes.
 */
public final class Layouts {

	/** The block layout rule. */
	public enum Std { STD140, STD430 }

	private Layouts() {
	}

	/** GLSL size of a type in bytes under a rule (arrays and matrices as the specification defines them). */
	public static long size(GlslType t, Std std) {
		return switch (t) {
			case ScalarType s -> scalarBytes(s.kind());
			case VectorType v -> scalarBytes(v.component()) * v.size();
			case MatrixType m -> columns(m, std) * stride(m.columnType(), std);
			case ArrayType a -> (a.size() == null ? 0 : a.size()) * arrayStride(a.element(), std);
			case StructType s -> roundUp(structEnd(s.fields(), std), align(s, std));
			default -> throw new IllegalArgumentException("no block layout for " + t);
		};
	}

	/** GLSL base alignment of a type in bytes. */
	public static long align(GlslType t, Std std) {
		return switch (t) {
			case ScalarType s -> scalarBytes(s.kind());
			case VectorType v -> scalarBytes(v.component()) * (v.size() == 3 ? 4 : v.size());
			case MatrixType m -> std == Std.STD140 ? 16 : align(m.columnType(), std);
			case ArrayType a -> std == Std.STD140 ? roundUp(align(a.element(), std), 16) : align(a.element(), std);
			case StructType s -> {
				long max = 1;
				for (StructField f : s.fields()) {
					max = Math.max(max, align(f.type(), std));
				}
				yield std == Std.STD140 ? roundUp(max, 16) : max;
			}
			default -> throw new IllegalArgumentException("no block layout for " + t);
		};
	}

	/** Byte offsets of the fields of a struct or block, in declaration order. */
	public static long[] offsets(List<StructField> fields, Std std) {
		long[] out = new long[fields.size()];
		long cursor = 0;
		for (int i = 0; i < fields.size(); i++) {
			GlslType t = fields.get(i).type();
			cursor = roundUp(cursor, align(t, std));
			out[i] = cursor;
			cursor += size(t, std);
		}
		return out;
	}

	/** The distance between two elements of an array of this element type. */
	public static long arrayStride(GlslType element, Std std) {
		// std140 rounds an array element's base alignment up to 16 (GLSL 4.60 section 7.6.2.2); std430 does not.
		long alignment = std == Std.STD140 ? roundUp(align(element, std), 16) : align(element, std);
		return roundUp(size(element, std), alignment);
	}

	/** The stride of matrix columns and of the vectors that make them: vec2 columns keep 8 bytes under std430. */
	private static long stride(VectorType column, Std std) {
		long natural = size(column, std);
		return std == Std.STD140 ? roundUp(natural, 16) : roundUp(natural, align(column, std));
	}

	private static long columns(MatrixType m, Std std) {
		return m.cols();
	}

	private static long structEnd(List<StructField> fields, Std std) {
		long[] offs = offsets(fields, std);
		if (fields.isEmpty()) {
			return 0;
		}
		int last = fields.size() - 1;
		return offs[last] + size(fields.get(last).type(), std);
	}

	private static long scalarBytes(ScalarKind k) {
		return switch (k) {
			case DOUBLE -> 8;
			case BOOL, INT, UINT, FLOAT -> 4;
		};
	}

	/** Rounds {@code value} up to a multiple of {@code unit} (a positive number). */
	public static long roundUp(long value, long unit) {
		return (value + unit - 1) / unit * unit;
	}

	/**
	 * Metal's natural size of a type (MSL 3.1 section 2.2): a packed vec3 takes three floats and aligns to four bytes,
	 * a plain float3 takes sixteen, and a matrix holds its columns at their natural stride. Structs and arrays follow
	 * their members. Used to check that the GLSL layout and the Metal layout agree.
	 */
	public static long mslSize(GlslType t, boolean packedVec3) {
		return switch (t) {
			case ScalarType s -> scalarBytes(s.kind());
			case VectorType v -> v.size() == 3 && packedVec3 ? 12 : scalarBytes(v.component()) * (v.size() == 3 ? 4 : v.size());
			case MatrixType m -> m.cols() * mslColumnStride(m.columnType());
			case ArrayType a -> (a.size() == null ? 1 : a.size()) * roundUp(mslSize(a.element(), false),
				mslAlign(a.element()));
			case StructType s -> {
				long end = 0;
				long max = 1;
				for (StructField f : s.fields()) {
					boolean packed = !(f.type() instanceof ArrayType) && f.type() instanceof VectorType v && v.size() == 3;
					end = roundUp(end, mslAlign(f.type())) + mslSize(f.type(), packed);
					max = Math.max(max, mslAlign(f.type()));
				}
				yield roundUp(end, max);
			}
			default -> throw new IllegalArgumentException("no Metal layout for " + t);
		};
	}

	/** Metal's natural alignment of a type. */
	public static long mslAlign(GlslType t) {
		return switch (t) {
			case ScalarType s -> scalarBytes(s.kind());
			case VectorType v -> scalarBytes(v.component()) * (v.size() == 3 ? 4 : v.size());
			case MatrixType m -> mslColumnStride(m.columnType()) >= 16 ? 16 : mslAlign(m.columnType());
			case ArrayType a -> mslAlign(a.element());
			case StructType s -> {
				long max = 1;
				for (StructField f : s.fields()) {
					max = Math.max(max, mslAlign(f.type()));
				}
				yield max;
			}
			default -> throw new IllegalArgumentException("no Metal layout for " + t);
		};
	}

	/** Metal's stride of a matrix column: float2 takes 8 bytes, float3 and float4 take 16. */
	private static long mslColumnStride(VectorType column) {
		return scalarBytes(column.component()) * (column.size() == 2 ? 2 : 4);
	}
}
