// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.util.List;

import dev.mcms.shaders.expr.ExprType;

/**
 * The frame slots of a pack's expressions (engine-pack-format.md section 13). The standard uniforms and parameters that the
 * pack's {@code uniform} and {@code variable} lines may read come first, each with its slot and type, in declaration order.
 * Then each custom value takes the next slot in file order ({@link PackModel.CustomValue#outputSlot()}). E3 sizes an
 * {@link dev.mcms.shaders.expr.ExprFrame} with {@link #slots()}, writes each parameter into its slot once per frame, and
 * reads each custom value back from its output slot.
 *
 * @param parameters the standard parameters; parameter {@code i} has slot {@code i}
 * @param slots      the slot count: the parameters and the custom values
 */
public record FrameLayout(List<Parameter> parameters, int slots) {
	/** A standard parameter: its name, its type and its slot. */
	public record Parameter(String name, ExprType type, int slot) {
	}

	public FrameLayout {
		parameters = List.copyOf(parameters);
	}
}
