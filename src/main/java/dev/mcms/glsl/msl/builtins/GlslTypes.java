// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl.builtins;

import java.util.Set;

/**
 * GLSL type strings as the built-in table sees them (W2-C, ADR 0006). A type is always a string ("float", "vec3",
 * "sampler2DShadow", "image2D(rgba16f)"); this class parses those strings into shape, so the table can branch on
 * "vector of float, 3 components" or "array texture, unsigned element" without any AST knowledge.
 */
public final class GlslTypes {
	/** The dimensionality and array-ness of a sampler or image type. */
	public enum Dim { D1, D2, D3, CUBE, D1_ARRAY, D2_ARRAY, CUBE_ARRAY, D2_RECT, D2_MS, D2_MS_ARRAY, BUFFER }

	/** Kind of a parsed type. */
	public enum Kind { FLOAT, INT, UINT, BOOL, DOUBLE, VECTOR, MATRIX, SAMPLER, IMAGE }

	/** A vector's scalar element. */
	public enum Scalar { FLOAT, INT, UINT, BOOL, DOUBLE }

	/** The shape of a sampler type: dimension, shadow comparison, element prefix ("", "i", "u"). */
	public record SamplerShape(Dim dim, boolean shadow, String element) {
	}

	/** The shape of an image type: dimension, element prefix and the optional GLSL format qualifier. */
	public record ImageShape(Dim dim, String element, String format) {
	}

	/** A parsed type. Fields not used by a kind are zero/null. */
	public record Type(Kind kind, Scalar scalar, int components, int cols, int rows,
					   SamplerShape sampler, ImageShape image) {

		public boolean isVectorOf(Scalar s) {
			return kind == Kind.VECTOR && scalar == s;
		}

		/** GLSL integer vector (ivecN/uvecN). */
		public boolean isIntVector() {
			return kind == Kind.VECTOR && (scalar == Scalar.INT || scalar == Scalar.UINT);
		}
	}

	private static final Set<String> SCALARS = Set.of("float", "int", "uint", "bool", "double");

	public static Type parse(String text) {
		String t = text.strip();
		int paren = t.indexOf('(');
		String base = paren > 0 ? t.substring(0, paren) : t;
		String extra = paren > 0 ? t.substring(paren + 1, t.endsWith(")") ? t.length() - 1 : t.length()).strip() : null;
		if (extra == null && SCALARS.contains(base))
			return new Type(scalarKind(base), scalarOf(base), 1, 0, 0, null, null);
		String imagePrefix = base.length() > 5 && (base.startsWith("iimage") || base.startsWith("uimage"))
			? base.substring(0, 1) : "";
		if (extra != null && !base.startsWith("image") && imagePrefix.isEmpty())
			throw new IllegalArgumentException("type parameters are only valid on image types: " + text);
		if (base.endsWith("vec2") || base.endsWith("vec3") || base.endsWith("vec4")) {
			String prefix = base.substring(0, base.length() - 4);
			if (prefix.isEmpty() || prefix.equals("i") || prefix.equals("u") || prefix.equals("b")
				|| prefix.equals("d")) {
				Scalar s = switch (prefix) {
					case "i" -> Scalar.INT;
					case "u" -> Scalar.UINT;
					case "b" -> Scalar.BOOL;
					case "d" -> Scalar.DOUBLE;
					default -> Scalar.FLOAT;
				};
				int n = base.charAt(base.length() - 1) - '0';
				return new Type(Kind.VECTOR, s, n, 0, 0, null, null);
			}
		}
		if (base.startsWith("mat")) {
			int cols, rows;
			if (base.length() == 4) {
				cols = rows = base.charAt(3) - '0';
			} else {
				int x = base.indexOf('x');
				cols = base.charAt(x - 1) - '0';
				rows = base.charAt(x + 1) - '0';
			}
			if (cols < 2 || cols > 4 || rows < 2 || rows > 4)
				throw new IllegalArgumentException("matrix size out of range: " + text);
			return new Type(Kind.MATRIX, Scalar.FLOAT, 0, cols, rows, null, null);
		}
		if (base.contains("sampler")) {
			SamplerShape shape = samplerShape(base, text);
			return new Type(Kind.SAMPLER, Scalar.FLOAT, 0, 0, 0, shape, null);
		}
		if (base.contains("image")) {
			ImageShape shape = imageShape(base, extra, text);
			return new Type(Kind.IMAGE, Scalar.FLOAT, 0, 0, 0, null, shape);
		}
		throw new IllegalArgumentException("not a GLSL type this table knows: " + text);
	}

	private static Kind scalarKind(String s) {
		return switch (s) {
			case "float" -> Kind.FLOAT;
			case "int" -> Kind.INT;
			case "uint" -> Kind.UINT;
			case "bool" -> Kind.BOOL;
			default -> Kind.DOUBLE;
		};
	}

	private static Scalar scalarOf(String s) {
		return switch (s) {
			case "float" -> Scalar.FLOAT;
			case "int" -> Scalar.INT;
			case "uint" -> Scalar.UINT;
			case "bool" -> Scalar.BOOL;
			default -> Scalar.DOUBLE;
		};
	}

	private static SamplerShape samplerShape(String base, String text) {
		boolean shadow = base.endsWith("Shadow");
		String body = shadow ? base.substring(0, base.length() - 6) : base;
		String element = "";
		if (body.startsWith("i") || body.startsWith("u")) {
			element = body.substring(0, 1);
			body = body.substring(1);
		}
		if (!body.startsWith("sampler"))
			throw new IllegalArgumentException("not a sampler type: " + text);
		Dim dim = switch (body.substring(7)) {
			case "1D" -> Dim.D1;
			case "2D" -> Dim.D2;
			case "3D" -> Dim.D3;
			case "Cube" -> Dim.CUBE;
			case "1DArray" -> Dim.D1_ARRAY;
			case "2DArray" -> Dim.D2_ARRAY;
			case "CubeArray" -> Dim.CUBE_ARRAY;
			case "2DRect" -> Dim.D2_RECT;
			case "2DMS" -> Dim.D2_MS;
			case "2DMSArray" -> Dim.D2_MS_ARRAY;
			case "Buffer" -> Dim.BUFFER;
			default -> throw new IllegalArgumentException("unknown sampler dimension: " + text);
		};
		if (shadow && !element.isEmpty())
			throw new IllegalArgumentException("integer shadow samplers do not exist: " + text);
		return new SamplerShape(dim, shadow, element);
	}

	private static ImageShape imageShape(String base, String format, String text) {
		String element = "";
		String body = base;
		if (body.startsWith("iimage") || body.startsWith("uimage")) {
			element = body.substring(0, 1);
			body = body.substring(1);
		}
		if (!body.startsWith("image"))
			throw new IllegalArgumentException("not an image type: " + text);
		Dim dim = switch (body.substring(5)) {
			case "1D" -> Dim.D1;
			case "2D" -> Dim.D2;
			case "3D" -> Dim.D3;
			case "Cube" -> Dim.CUBE;
			case "1DArray" -> Dim.D1_ARRAY;
			case "2DArray" -> Dim.D2_ARRAY;
			case "CubeArray" -> Dim.CUBE_ARRAY;
			case "2DRect" -> Dim.D2_RECT;
			case "2DMS" -> Dim.D2_MS;
			case "2DMSArray" -> Dim.D2_MS_ARRAY;
			case "Buffer" -> Dim.BUFFER;
			default -> throw new IllegalArgumentException("unknown image dimension: " + text);
		};
		if (format != null && !format.isEmpty() && !ImageFormats.isKnown(format))
			throw new IllegalArgumentException("unknown image format qualifier: " + text);
		return new ImageShape(dim, element, format == null || format.isEmpty() ? null : format);
	}

	/** The MSL name of a scalar or vector type, e.g. ({@code UINT}, 3) = "uint3". */
	public static String mslVector(Scalar s, int n) {
		String base = switch (s) {
			case FLOAT -> "float";
			case INT -> "int";
			case UINT -> "uint";
			case BOOL -> "bool";
			case DOUBLE -> throw new IllegalArgumentException("no double on Metal");
		};
		return n == 1 ? base : base + n;
	}

	/** The MSL matrix name for a GLSL matCxR (cols first, like GLSL; MSL 4.3.2 "matrix<float, C, R>"). */
	public static String mslMatrix(int cols, int rows) {
		return "float" + cols + 'x' + rows;
	}

	/**
	 * The MSL texture class for a sampler or image type, e.g. sampler2DShadow = "depth2d<float>". A 1D texture is the
	 * 2D class of height 1 (texture2d, depth2d, texture2d_array, depth2d_array): the runtime contract of
	 * {@link BuiltinTable}.
	 */
	public static String mslTexture(SamplerShape s) {
		String element = s.element().isEmpty() ? "float" : s.element().equals("i") ? "int" : "uint";
		String name = switch (s.dim()) {
			case D1, D2, D2_RECT -> "texture2d";
			case D3 -> "texture3d";
			case CUBE -> "texturecube";
			case D1_ARRAY, D2_ARRAY -> "texture2d_array";
			case CUBE_ARRAY -> "texturecube_array";
			case D2_MS -> "texture2d_ms";
			case D2_MS_ARRAY -> "texture2d_ms_array";
			case BUFFER -> "texture_buffer";
		};
		if (s.shadow()) {
			name = switch (s.dim()) {
				case D1, D2, D2_RECT -> "depth2d";
				case D1_ARRAY, D2_ARRAY -> "depth2d_array";
				case CUBE -> "depthcube";
				case CUBE_ARRAY -> "depthcube_array";
				case D2_MS -> "depth2d_ms";
				case D2_MS_ARRAY -> "depth2d_ms_array";
				default -> throw new IllegalArgumentException("Metal has no depth texture for " + s);
			};
		}
		return name + '<' + element + '>';
	}

	/**
	 * The MSL texture class for an image type, read-write: image2D(rgba16f) = "texture2d<float, access::read_write>",
	 * image1DArray(r32ui) = "texture2d_array<uint, access::read_write>", imageBuffer(r32f) = "texture_buffer<float,
	 * access::read_write>". Cube kinds are texturecube (array: texturecube_array); the element comes from the qualifier.
	 */
	public static String mslImage(ImageShape s) {
		return mslImage(s, "read_write");
	}

	/** The same class at an access: "read", "write" or "read_write" (the MSL access:: spelling). */
	public static String mslImage(ImageShape s, String access) {
		String element = s.element().isEmpty()
			? (s.format() == null ? "float" : ImageFormats.element(s.format()))
			: s.element().equals("i") ? "int" : "uint";
		String name = switch (s.dim()) {
			case D1, D2, D2_RECT -> "texture2d";
			case D3 -> "texture3d";
			case CUBE -> "texturecube";
			case D1_ARRAY, D2_ARRAY -> "texture2d_array";
			case CUBE_ARRAY -> "texturecube_array";
			case BUFFER -> "texture_buffer";
			case D2_MS, D2_MS_ARRAY -> throw new IllegalArgumentException("Metal has no read-write multisample texture");
		};
		return name + '<' + element + ", access::" + access + ">";
	}

	private GlslTypes() {
	}
}
