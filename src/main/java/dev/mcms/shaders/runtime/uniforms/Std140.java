// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.uniforms;

import java.util.ArrayList;
import java.util.List;

import dev.mcms.glsl.msl.ProgramRequest.UniformBlock;
import dev.mcms.glsl.msl.ProgramRequest.UniformField;

/**
 * The std140 layout of an engine uniform block (OpenGL 4.6 specification, section 4.5.4 "Standard Uniform Block
 * Layout"): the engine owns the block layout, so it computes the same offsets the pack's GLSL expects and hands them to
 * the translator through {@link UniformField#offset()}.
 *
 * <p>Rules for the types the engine's blocks use: a scalar ({@code float}, {@code int}, {@code bool}) takes four bytes;
 * a two-component vector eight; a three-component vector sixteen (aligned like a four-component vector, twelve bytes of
 * payload); a four-component vector sixteen; a matrix is an array of column vectors with the vec4 alignment, so
 * {@code mat4} is four {@code vec4} columns (16-byte stride), {@code mat3} three {@code vec4} columns (48 bytes)
 * and {@code mat2} two of them (32 bytes; the unused half of each column is padding).
 * The block's total size rounds up to its largest alignment (16 for any block that holds a vector of three or more or
 * a matrix; the engine's blocks always do).
 */
public final class Std140 {
	private Std140() {
	}

	/** One member of a block during layout: its GLSL name and type word. */
	public record Member(String name, String type) {
	}

	/** The aligned size of one type word in bytes. */
	public static int align(String type) {
		return switch (type) {
			case "float", "int", "uint", "bool" -> 4;
			case "vec2", "ivec2", "uvec2", "bvec2" -> 8;
			case "mat2" -> 16;
			default -> 16;
		};
	}

	/** The payload size of one type word in bytes (without the block's tail padding). */
	public static int size(String type) {
		return switch (type) {
			case "float", "int", "uint", "bool" -> 4;
			case "vec2", "ivec2", "uvec2", "bvec2" -> 8;
			case "vec3", "ivec3", "uvec3", "bvec3" -> 12;
			case "vec4", "ivec4", "uvec4", "bvec4" -> 16;
			case "mat2" -> 32;
			case "mat3" -> 48;
			default -> 16 * components(type);
		};
	}

	private static int components(String type) {
		return switch (type) {
			case "mat2" -> 2;
			default -> 4;
		};
	}

	/** Lays the members out in order and builds the block; the offsets follow the std140 rules above. */
	public static UniformBlock block(String name, int slot, List<Member> members) {
		List<UniformField> fields = new ArrayList<>(members.size());
		int cursor = 0;
		for (Member m : members) {
			int align = align(m.type());
			cursor = roundUp(cursor, align);
			fields.add(new UniformField(m.name(), m.type(), cursor));
			cursor += size(m.type());
		}
		return new UniformBlock(name, slot, fields);
	}

	/** The padded size of the block (what {@code write} fills). */
	public static int paddedSize(UniformBlock block) {
		if (block.fields().isEmpty()) {
			return 0;
		}
		UniformField last = block.fields().get(block.fields().size() - 1);
		int maxAlign = 16;
		return roundUp(last.offset() + size(last.type()), maxAlign);
	}

	private static int roundUp(int v, int align) {
		return (v + align - 1) / align * align;
	}
}
