// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.ast;

import java.util.List;

/**
 * A uniform, buffer, in or out interface block, for example
 * {@code layout(std430, binding = 1) buffer B { float data[]; } reduction;}. The qualifiers hold the layout, storage
 * and memory words; {@code instance} (with its position and optional dimensions) is null when the block has no
 * instance name.
 */
public record InterfaceBlock(Pos pos, List<Qualifier> qualifiers, String name, Pos namePos, List<StructMember> members,
		String instance, Pos instancePos, List<ArrayDim> instanceDims) implements Decl {
}
