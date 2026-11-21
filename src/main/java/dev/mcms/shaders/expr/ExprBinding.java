// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.expr;

/**
 * How a name resolves: a per-frame value read from an {@link ExprFrame} slot, or a constant known when the pack loads.
 * The runtime supplies biome ids ({@code BIOME_PLAINS}), categories ({@code CAT_*}) and precipitation
 * ({@code PPT_*}) as constants (OptiFine doc shaders.properties, "Custom uniforms", Constants).
 */
public sealed interface ExprBinding {
	ExprType type();

	/** A value the runtime writes into its frame slot before evaluation (standard uniforms, variables, entity flags). */
	record Frame(int slot, ExprType type) implements ExprBinding {}

	record ConstFloat(float value) implements ExprBinding {
		@Override
		public ExprType type() {
			return ExprType.FLOAT;
		}
	}

	record ConstInt(int value) implements ExprBinding {
		@Override
		public ExprType type() {
			return ExprType.INT;
		}
	}

	record ConstBool(boolean value) implements ExprBinding {
		@Override
		public ExprType type() {
			return ExprType.BOOL;
		}
	}
	/** {@code type} is VEC2, VEC3 or VEC4; unused components are ignored. */
	record ConstVec(ExprType type, float x, float y, float z, float w) implements ExprBinding {}
}
