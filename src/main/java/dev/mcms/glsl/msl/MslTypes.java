// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl;

import dev.mcms.glsl.msl.builtins.GlslTypes;
import dev.mcms.glsl.types.ArrayType;
import dev.mcms.glsl.types.AtomicCounterType;
import dev.mcms.glsl.types.GlslType;
import dev.mcms.glsl.types.ImageFormat;
import dev.mcms.glsl.types.ImageType;
import dev.mcms.glsl.types.MatrixType;
import dev.mcms.glsl.types.SamplerType;
import dev.mcms.glsl.types.ScalarKind;
import dev.mcms.glsl.types.ScalarType;
import dev.mcms.glsl.types.StructType;
import dev.mcms.glsl.types.VectorType;

/**
 * Metal spellings of GLSL types. Scalars and vectors keep their names (float3, int2, bool4); matrices are
 * {@code floatCxR} (columns first, as MSL names them); arrays are {@code metal::array}; structs and interface blocks
 * are {@code u_<name>}. Textures and images use the built-in table's own spelling ({@link GlslTypes}), so the texture
 * classes the emitter declares are the ones the table's calls expect.
 */
final class MslTypes {

	private MslTypes() {
	}

	static String scalar(ScalarKind k) {
		return switch (k) {
			case BOOL -> "bool";
			case INT -> "int";
			case UINT -> "uint";
			case FLOAT -> "float";
			case DOUBLE -> "double";
		};
	}

	static String vector(VectorType v) {
		return scalar(v.component()) + v.size();
	}

	/** The member spelling of a vec3 in a block: packed, three components in twelve bytes. */
	static String packedVector(VectorType v) {
		return v.size() == 3 ? "packed_" + vector(v) : vector(v);
	}

	static String matrix(MatrixType m) {
		String base = m.component() == ScalarKind.DOUBLE ? "double" : "float";
		return base + m.cols() + "x" + m.rows();
	}

	static String structName(StructType s) {
		return s.name() != null ? "u_" + s.name() : "u_anon" + s.id();
	}

	/** The value spelling of a type: sized arrays as {@code array<T, N>}. Unsized arrays need the caller's layout. */
	static String value(GlslType t) {
		return switch (t) {
			case ScalarType s -> scalar(s.kind());
			case VectorType v -> vector(v);
			case MatrixType m -> matrix(m);
			case StructType s -> structName(s);
			case ArrayType a -> {
				if (a.size() == null) {
					throw new IllegalArgumentException("unsized array has no value type: " + t);
				}
				yield "array<" + value(a.element()) + ", " + a.size() + ">";
			}
			case SamplerType s -> texture(s);
			case ImageType i -> image(i, null, "read_write");
			case AtomicCounterType a -> throw new IllegalArgumentException("atomic counters have no Metal type");
			default -> throw new IllegalArgumentException("no Metal type for " + t);
		};
	}

	/** The Metal texture class of a sampler type (a depth class for shadow samplers). */
	static String texture(SamplerType s) {
		return GlslTypes.mslTexture(GlslTypes.parse(s.toString()).sampler());
	}

	/** The Metal texture class of an image type with its GLSL format word (null when the format is omitted). */
	static String image(ImageType i, ImageFormat format, String access) {
		String word = i.toString() + (format == null ? "" : "(" + format.word() + ")");
		return GlslTypes.mslImage(GlslTypes.parse(word).image(), access);
	}

	/** The GLSL spelling used to look a type up in the built-in table (image types carry their format). */
	static String glslWord(GlslType t, ImageFormat format) {
		if (t instanceof ImageType i && format != null) {
			return i.toString() + "(" + format.word() + ")";
		}
		return t.toString();
	}

	/** True for a vector of three components (the members that take the packed spelling in blocks). */
	static boolean isVec3(GlslType t) {
		return t instanceof VectorType v && v.size() == 3;
	}

	/** The zero value of a type: {@code {}} initializes every type we emit. */
	static String zero() {
		return "{}";
	}

	/** Boolean-typed vector of the same size, used by the vector comparison helpers. */
	static String boolVector(int size) {
		return "bool" + size;
	}
}
