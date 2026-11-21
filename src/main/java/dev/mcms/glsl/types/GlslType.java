// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.types;

/**
 * Every type of GLSL 1.10 to 4.60: scalars, vectors, matrices, structs, arrays, samplers, images and atomic
 * counters (GLSL 4.60 specification, chapter 4). {@link #toString()} is the GLSL spelling of the type
 * ({@code vec3}, {@code usampler2D}, {@code mat4x3}, {@code float[3]}); the built-in function mapping table
 * and the emitter's reflection key on those strings. {@link ErrorType} appears only in expressions the
 * analyzer rejected; the emitter never sees a program with errors.
 */
public sealed interface GlslType permits VoidType, ErrorType, ScalarType, VectorType, MatrixType, StructType,
		ArrayType, SamplerType, ImageType, AtomicCounterType {

	/** The GLSL spelling of this type. */
	@Override
	String toString();

	/** True for {@code void}. */
	default boolean isVoid() {
		return this instanceof VoidType;
	}

	/** True for the error type (an expression the analyzer rejected). */
	default boolean isError() {
		return this instanceof ErrorType;
	}

	/** True for opaque types (samplers, images, atomic counters). */
	default boolean isOpaque() {
		return this instanceof SamplerType || this instanceof ImageType || this instanceof AtomicCounterType;
	}

	/** The scalar kind every component of this type has, or null for types without components (void, error,
	 * opaque, struct, array). */
	default ScalarKind componentKind() {
		return null;
	}

	/** True if this type or any member of it (arrays, structs) is opaque. */
	default boolean containsOpaque() {
		return switch (this) {
			case SamplerType s -> true;
			case ImageType i -> true;
			case AtomicCounterType a -> true;
			case ArrayType a -> a.element().containsOpaque();
			case StructType s -> s.fields().stream().anyMatch(f -> f.type().containsOpaque());
			default -> false;
		};
	}
}
