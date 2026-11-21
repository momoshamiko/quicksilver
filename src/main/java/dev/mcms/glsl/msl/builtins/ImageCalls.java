// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl.builtins;

import dev.mcms.glsl.msl.builtins.GlslTypes.Dim;
import dev.mcms.glsl.msl.builtins.GlslTypes.ImageShape;
import java.util.List;

/**
 * The image functions of GLSL 4.60 chapter 8.12 as MSL read/write texture member calls (W2-C, ADR 0006). GLSL
 * integer coordinates become the unsigned coordinates Metal wants; the image's element type comes from the GLSL type
 * string (uimage/iimage prefix, or the format qualifier: image2D(r32ui) reads and writes uint). Atomics use Metal
 * 3.1's texture atomics, which exist for integer channel types only, and return the whole texel vector, of which the
 * red component carries the value.
 *
 * <p>1D images are 2D images of height 1 and 1D array images are 2D array images of height 1 (the runtime contract in
 * {@link BuiltinTable}): the 1D coordinate x becomes (x, 0), and imageSize returns the width (the array form returns
 * (width, layers)). Image buffers are texture_buffer objects addressed by index. Cube images take the face from the
 * third coordinate, face = z (imageCube) and face = z % 6, layer = z / 6 (imageCubeArray, GLSL 4.60 8.12 layer-face
 * order). Multisample images are refused: Metal has no read-write multisample texture.
 */
final class ImageCalls {

	static Mapping map(BuiltinCall call) {
		ImageShape shape = call.type(0).image();
		String tex = call.args().get(0);
		Dim d = shape.dim();
		Mapping.Unsupported refused = refusal(d);
		if (refused != null) return refused;
		return switch (call.name()) {
			case "imageLoad" -> load(call, tex, d);
			case "imageStore" -> store(call, tex, d);
			case "imageSize" -> size(tex, d);
			case "imageSamples" -> Mapping.Mapped.of("int(" + tex + ".get_num_samples())");
			default -> atomic(call, shape, tex, d);
		};
	}

	/** The reason a read or write of this image kind is refused, or null when the kind is mapped. */
	private static Mapping.Unsupported refusal(Dim d) {
		if (d == Dim.D2_MS || d == Dim.D2_MS_ARRAY)
			return new Mapping.Unsupported("Metal has no read-write multisample texture (image2DMS); no corpus pack "
				+ "uses one");
		return null;
	}

	private static Mapping load(BuiltinCall call, String tex, Dim d) {
		String p = call.args().get(1);
		StringBuilder msl = new StringBuilder(tex).append(".read(").append(coord(d, p));
		String slice = slice(d, p);
		if (slice != null) msl.append(", ").append(slice);
		msl.append(')');
		return Mapping.Mapped.of(msl.toString());
	}

	private static Mapping store(BuiltinCall call, String tex, Dim d) {
		String p = call.args().get(1);
		String data = call.args().get(call.args().size() - 1);
		StringBuilder msl = new StringBuilder(tex).append(".write(").append(data).append(", ").append(coord(d, p));
		String slice = slice(d, p);
		if (slice != null) msl.append(", ").append(slice);
		msl.append(')');
		return Mapping.Mapped.of(msl.toString());
	}

	private static Mapping size(String tex, Dim d) {
		return switch (d) {
			case D1 -> Mapping.Mapped.of("int(" + tex + ".get_width())");
			case D1_ARRAY -> Mapping.Mapped.of("int2(int(" + tex + ".get_width()), int(" + tex + ".get_array_size()))");
			case BUFFER -> Mapping.Mapped.of("int(" + tex + ".get_width())");
			default -> Mapping.Mapped.of("qs::imageSize(" + tex + ")", "imageSize");
		};
	}

	private static Mapping atomic(BuiltinCall call, ImageShape shape, String tex, Dim d) {
		String element = elementOf(shape);
		if (!element.equals("int") && !element.equals("uint"))
			return new Mapping.Unsupported("Metal 3.1 texture atomics exist for integer formats only "
				+ "(imageAtomicExchange on r32f included)");
		if (d == Dim.CUBE || d == Dim.CUBE_ARRAY)
			return new Mapping.Unsupported("Metal cube textures have no atomic functions (measured: no atomic_fetch_add "
				+ "member on texturecube); no corpus pack uses imageCube atomics");
		if (d != Dim.D1 && d != Dim.D1_ARRAY && d != Dim.D2 && d != Dim.D3 && d != Dim.D2_ARRAY
			&& d != Dim.D2_RECT && d != Dim.BUFFER)
			return new Mapping.Unsupported("image atomics are mapped for 1D, 2D, 3D, rectangle, array and buffer integer "
				+ "images (measured); " + d + " is not");
		String op = switch (call.name()) {
			case "imageAtomicAdd" -> "atomic_fetch_add";
			case "imageAtomicMin" -> "atomic_fetch_min";
			case "imageAtomicMax" -> "atomic_fetch_max";
			case "imageAtomicAnd" -> "atomic_fetch_and";
			case "imageAtomicOr" -> "atomic_fetch_or";
			case "imageAtomicXor" -> "atomic_fetch_xor";
			case "imageAtomicExchange" -> "atomic_exchange";
			case "imageAtomicCompSwap" -> null;
			default -> throw new IllegalStateException("not an image atomic: " + call.name());
		};
		String p = call.args().get(1);
		String slice = slice(d, p);
		String coord = coord(d, p) + (slice == null ? "" : ", " + slice);
		// 2D member functions take the scalar operand and return the texel vector; 3D takes and returns vec4.
		// The GLSL argument order is (image, P, data) or (image, P, compare, data); the array layer is inside P.
		if (call.name().equals("imageAtomicCompSwap")) {
			String compare = call.args().get(2);
			String data = call.args().get(3);
			String msl = "qs::imageCompSwap(" + tex + ", " + coord + ", " + compare + ", " + data + ")";
			return new Mapping.Mapped(msl, List.of("imageCompSwap"));
		}
		String data = call.args().get(2);
		String operand = (elementOf(shape).equals("int") ? "int4(" : "uint4(") + data + ")";
		return Mapping.Mapped.of(tex + "." + op + "(" + coord + ", " + operand + ").x");
	}

	/**
	 * The MSL coordinate argument (unsigned) for the image's dimension; 1D is (x, 0) of the height-1 texture, an image
	 * buffer is the texel index, and cube images use the (x, y) of the coordinate (the face is {@link #slice}).
	 */
	private static String coord(Dim d, String p) {
		return switch (d) {
			case D1 -> "uint2(uint(" + p + "), 0u)";
			case D1_ARRAY -> "uint2(uint(" + p + ".x), 0u)";
			case D2, D2_RECT, D2_MS -> "uint2(" + p + ")";
			case D2_ARRAY, D2_MS_ARRAY, CUBE, CUBE_ARRAY -> "uint2(" + p + ".xy)";
			case D3 -> "uint3(" + p + ")";
			case BUFFER -> "uint(" + p + ")";
			default -> throw new IllegalStateException("image coord " + d);
		};
	}

	/**
	 * The MSL arguments after the coordinate that select the array layer (1D and 2D arrays), the cube face (imageCube:
	 * z), or the cube face and layer of a cube array (face = z % 6, layer = z / 6); null for the other kinds.
	 */
	private static String slice(Dim d, String p) {
		return switch (d) {
			case D1_ARRAY -> "uint(" + p + ".y)";
			case D2_ARRAY, D2_MS_ARRAY, CUBE -> "uint(" + p + ".z)";
			case CUBE_ARRAY -> "uint(" + p + ".z) % 6u, uint(" + p + ".z) / 6u";
			default -> null;
		};
	}

	private static String elementOf(ImageShape shape) {
		if (!shape.element().isEmpty()) return shape.element().equals("i") ? "int" : "uint";
		return shape.format() == null ? "float" : ImageFormats.element(shape.format());
	}

	private ImageCalls() {
	}
}
