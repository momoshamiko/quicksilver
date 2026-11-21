// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import dev.mcms.glsl.ast.StorageKind;
import dev.mcms.glsl.types.GlslType;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

/**
 * One built-in function signature of the GLSL specification's chapter 8 (generic families like
 * {@code genType} are expanded to concrete types in the table). Parameters carry their direction
 * ({@code out}/{@code inout} for {@code modf}, the atomic and image functions); {@code constParams} names
 * the parameters that must be constant expressions (texture offsets, {@code textureGather}'s component);
 * {@code stages} is empty for every stage. {@code compat} marks the legacy names of the compatibility
 * profile's texture functions; {@code extension} names the extension that introduces the function when the
 * core specification does not.
 */
public record BuiltinFunction(String name, List<Param> params, GlslType result, int since,
		EnumSet<Stage> stages, boolean compat, String extension) {

	public enum Direction {
		IN, OUT, INOUT
	}

	public record Param(GlslType type, Direction direction, boolean constant) {
		static Param in(GlslType type) {
			return new Param(type, Direction.IN, false);
		}

		static Param constIn(GlslType type) {
			return new Param(type, Direction.IN, true);
		}

		static Param out(GlslType type) {
			return new Param(type, Direction.OUT, false);
		}

		static Param inout(GlslType type) {
			return new Param(type, Direction.INOUT, false);
		}
	}

	public boolean available(int version) {
		return since <= version;
	}

	public boolean availableIn(Stage stage) {
		return stages.isEmpty() || stages.contains(stage);
	}

	public StorageKind direction(int index) {
		return switch (params.get(index).direction()) {
			case OUT -> StorageKind.OUT;
			case INOUT -> StorageKind.INOUT;
			default -> StorageKind.IN;
		};
	}

	public int paramCount() {
		return params.size();
	}

	@Override
	public String toString() {
		return name + Arrays.toString(params.stream().map(p -> p.type().toString()).toArray()) + " -> " + result;
	}
}
