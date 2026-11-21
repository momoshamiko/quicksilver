// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.types;

/**
 * An image type (GLSL 4.20, {@code GL_ARB_shader_image_load_store}): dimension and the component type of the
 * texels ({@code image*} float, {@code iimage*} int, {@code uimage*} uint). The memory format is a layout
 * qualifier on the variable, not part of the type; the analyzer records it on the symbol.
 */
public record ImageType(SamplerDim dim, ScalarKind texel) implements GlslType {
	@Override
	public String toString() {
		return switch (texel) {
			case INT -> "iimage" + dim.word();
			case UINT -> "uimage" + dim.word();
			default -> "image" + dim.word();
		};
	}
}
