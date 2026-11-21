// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.types;

/** One of the five scalar types. Instances are shared: {@link #of} returns the same object per kind. */
public record ScalarType(ScalarKind kind) implements GlslType {
	public static final ScalarType BOOL = new ScalarType(ScalarKind.BOOL);
	public static final ScalarType INT = new ScalarType(ScalarKind.INT);
	public static final ScalarType UINT = new ScalarType(ScalarKind.UINT);
	public static final ScalarType FLOAT = new ScalarType(ScalarKind.FLOAT);
	public static final ScalarType DOUBLE = new ScalarType(ScalarKind.DOUBLE);

	public static ScalarType of(ScalarKind kind) {
		return switch (kind) {
			case BOOL -> BOOL;
			case INT -> INT;
			case UINT -> UINT;
			case FLOAT -> FLOAT;
			case DOUBLE -> DOUBLE;
		};
	}

	@Override
	public String toString() {
		return kind.word();
	}

	@Override
	public ScalarKind componentKind() {
		return kind;
	}
}
