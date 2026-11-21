// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.lex;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Which words are keywords and which are reserved, per GLSL version (110 to 460). Built from the keyword and
 * reserved-word lists of the GLSL 1.10, 1.20, 1.30, 1.40, 1.50, 3.30, 4.00, 4.10, 4.20, 4.30 and 4.60 specifications
 * (section "Keywords" of each).
 *
 * <p>Engine rules on top of the specifications:
 * <ul>
 * <li>A word that a later GLSL version made a keyword is a plain identifier before that version (packs must be free to
 * use it as a name; for example {@code sample}, {@code patch}, {@code buffer}, {@code shared}, {@code precise},
 * {@code subroutine}, the image and the newer sampler type words).
 * <li>A word the specifications reserve for the future is an error in every version in which it is reserved, even when
 * a later version goes on to make it a keyword ({@code switch} and {@code default} before 1.30, {@code double} and
 * {@code dvec*} before 4.00, {@code volatile} before 4.20). Words first reserved in 1.30 ({@code filter},
 * {@code active}, {@code common}, {@code partition}, {@code superp}) stay identifiers in 1.10 and 1.20, and
 * {@code resource} is first reserved in 4.20 (it is an identifier before that). {@code dmat*} was never reserved and
 * becomes a keyword in 4.00; {@code case} was never reserved before 1.30. The precision and invariant words and
 * {@code centroid} are keywords from 1.20 (a deliberate leniency, see the table below). {@code flat}, {@code smooth}
 * and {@code noperspective} are keywords from 1.10 for the same reason: drivers accept {@code flat varying} in 1.20
 * units, so packs use it; semantic analysis records the leniency ({@code interp-qualifier}) below GLSL 1.30.
 * <li>{@code texture} is never a keyword: the GLSL specifications never list it as a keyword or reserved word, it is
 * only the name of a built-in function, and packs declare {@code uniform sampler2D texture;} (observed in a
 * {@code #version 130} pack).
 * </ul>
 */
public final class Keywords {
	private Keywords() {
	}

	/** Word to the first GLSL version in which it is a keyword. */
	private static final Map<String, Integer> KEYWORD_SINCE = new HashMap<>();

	/** Words reserved for future use in every GLSL version: using them is an error. */
	private static final Set<String> RESERVED_ALWAYS = Set.of(
			"asm", "class", "union", "enum", "typedef", "template", "this", "goto",
			"inline", "noinline", "public", "static", "extern", "external", "interface",
			"long", "short", "half", "fixed", "unsigned", "input", "output",
			"hvec2", "hvec3", "hvec4", "fvec2", "fvec3", "fvec4",
			"sizeof", "cast", "namespace", "using", "sampler3DRect");

	/** Word to the first GLSL version in which it is reserved (plain identifiers before that). */
	private static final Map<String, Integer> RESERVED_FROM = new HashMap<>();

	/** Word to the last GLSL version (inclusive) in which it is reserved before becoming a keyword. */
	private static final Map<String, Integer> RESERVED_UNTIL = new HashMap<>();

	/** Every built-in type word of GLSL 1.10 to 4.60 (the parser treats these words as type specifiers). */
	private static final Set<String> TYPE_WORDS = Set.of(
			"void", "float", "int", "bool", "uint", "double",
			"vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4", "bvec2", "bvec3", "bvec4",
			"uvec2", "uvec3", "uvec4", "dvec2", "dvec3", "dvec4",
			"mat2", "mat3", "mat4",
			"mat2x2", "mat2x3", "mat2x4", "mat3x2", "mat3x3", "mat3x4", "mat4x2", "mat4x3", "mat4x4",
			"dmat2", "dmat3", "dmat4",
			"dmat2x2", "dmat2x3", "dmat2x4", "dmat3x2", "dmat3x3", "dmat3x4", "dmat4x2", "dmat4x3", "dmat4x4",
			"sampler1D", "sampler2D", "sampler3D", "samplerCube",
			"sampler1DShadow", "sampler2DShadow", "samplerCubeShadow",
			"sampler2DRect", "sampler2DRectShadow",
			"sampler1DArray", "sampler2DArray", "sampler1DArrayShadow", "sampler2DArrayShadow",
			"isampler1D", "isampler2D", "isampler3D", "isamplerCube",
			"isampler2DRect", "isampler1DArray", "isampler2DArray",
			"usampler1D", "usampler2D", "usampler3D", "usamplerCube",
			"usampler2DRect", "usampler1DArray", "usampler2DArray",
			"samplerBuffer", "isamplerBuffer", "usamplerBuffer",
			"sampler2DMS", "sampler2DMSArray", "isampler2DMS", "isampler2DMSArray",
			"usampler2DMS", "usampler2DMSArray",
			"samplerCubeArray", "samplerCubeArrayShadow", "isamplerCubeArray", "usamplerCubeArray",
			"atomic_uint",
			"image1D", "image2D", "image3D", "imageCube", "image2DRect",
			"image1DArray", "image2DArray", "imageCubeArray", "imageBuffer", "image2DMS", "image2DMSArray",
			"iimage1D", "iimage2D", "iimage3D", "iimageCube", "iimage2DRect",
			"iimage1DArray", "iimage2DArray", "iimageCubeArray", "iimageBuffer", "iimage2DMS", "iimage2DMSArray",
			"uimage1D", "uimage2D", "uimage3D", "uimageCube", "uimage2DRect",
			"uimage1DArray", "uimage2DArray", "uimageCubeArray", "uimageBuffer", "uimage2DMS", "uimage2DMSArray");

	/** Every qualifier word of GLSL 1.10 to 4.60 (storage, interpolation, memory, precision, layout, invariant, precise). */
	private static final Set<String> QUALIFIER_WORDS = Set.of(
			"const", "attribute", "varying", "uniform", "buffer", "shared",
			"in", "out", "inout", "centroid", "sample", "patch",
			"flat", "smooth", "noperspective",
			"coherent", "volatile", "restrict", "readonly", "writeonly",
			"highp", "mediump", "lowp",
			"layout", "invariant", "precise");

	/** Words that start or shape statements and literals. */
	private static final Set<String> STATEMENT_WORDS = Set.of(
			"if", "else", "for", "while", "do", "switch", "case", "default",
			"break", "continue", "return", "discard", "struct", "precision", "true", "false");

	static {
		version(110, "const", "uniform", "attribute", "varying",
				"break", "continue", "do", "for", "while", "if", "else", "in", "out", "inout",
				"return", "discard", "true", "false",
				"struct", "void", "float", "int", "bool",
				"vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4", "bvec2", "bvec3", "bvec4",
				"mat2", "mat3", "mat4",
				"sampler1D", "sampler2D", "sampler3D", "samplerCube",
				"sampler1DShadow", "sampler2DShadow");
		// GLSL 1.20 introduced lowp/mediump/highp/precision/invariant and centroid. Treating them as keywords from 1.20
		// (rather than from the 1.30 boundary some lists show) is a deliberate leniency so packs that use them in
		// 1.20 sources parse; the exact specification boundary is not verified in this table.
		version(120, "lowp", "mediump", "highp", "precision", "invariant", "centroid",
				"mat2x2", "mat2x3", "mat2x4", "mat3x2", "mat3x3", "mat3x4", "mat4x2", "mat4x3", "mat4x4",
				"sampler2DRect", "sampler2DRectShadow");
		// interpolation qualifiers: GLSL 1.30 defines them, and drivers accept "flat varying" earlier, so packs use them
		// from 1.20 (semantic analysis records the leniency below 1.30)
		version(110, "flat", "smooth", "noperspective");
		version(130, "uint", "uvec2", "uvec3", "uvec4",
				"switch", "case", "default", "layout",
				"sampler1DArray", "sampler2DArray", "samplerCubeShadow",
				"sampler1DArrayShadow", "sampler2DArrayShadow",
				"isampler1D", "isampler2D", "isampler3D", "isamplerCube", "isampler1DArray", "isampler2DArray",
				"usampler1D", "usampler2D", "usampler3D", "usamplerCube", "usampler1DArray", "usampler2DArray");
		version(140, "samplerBuffer", "isamplerBuffer", "usamplerBuffer");
		version(150, "sampler2DMS", "sampler2DMSArray",
				"isampler2DMS", "isampler2DMSArray", "usampler2DMS", "usampler2DMSArray",
				"isampler2DRect", "usampler2DRect");
		version(400, "double", "dvec2", "dvec3", "dvec4",
				"dmat2", "dmat3", "dmat4",
				"dmat2x2", "dmat2x3", "dmat2x4", "dmat3x2", "dmat3x3", "dmat3x4", "dmat4x2", "dmat4x3", "dmat4x4",
				"patch", "sample", "subroutine", "precise",
				"samplerCubeArray", "samplerCubeArrayShadow", "isamplerCubeArray", "usamplerCubeArray");
		version(420, "coherent", "volatile", "restrict", "readonly", "writeonly", "atomic_uint",
				"image1D", "image2D", "image3D", "imageCube", "image2DRect",
				"image1DArray", "image2DArray", "imageCubeArray", "imageBuffer", "image2DMS", "image2DMSArray",
				"iimage1D", "iimage2D", "iimage3D", "iimageCube", "iimage2DRect",
				"iimage1DArray", "iimage2DArray", "iimageCubeArray", "iimageBuffer", "iimage2DMS", "iimage2DMSArray",
				"uimage1D", "uimage2D", "uimage3D", "uimageCube", "uimage2DRect",
				"uimage1DArray", "uimage2DArray", "uimageCubeArray", "uimageBuffer", "uimage2DMS", "uimage2DMSArray");
		version(430, "buffer", "shared");

		reservedFrom(130, "common", "partition", "active", "filter", "superp");
		reservedFrom(420, "resource");
		reservedUntil(120, "switch", "default");
		reservedUntil(399, "double", "dvec2", "dvec3", "dvec4");
		reservedUntil(419, "volatile");
	}

	private static void version(int since, String... words) {
		for (String w : words) {
			KEYWORD_SINCE.put(w, since);
		}
	}

	private static void reservedFrom(int firstVersion, String... words) {
		for (String w : words) {
			RESERVED_FROM.put(w, firstVersion);
		}
	}

	private static void reservedUntil(int lastVersion, String... words) {
		for (String w : words) {
			RESERVED_UNTIL.put(w, lastVersion);
		}
	}

	/** True when {@code word} is a keyword in GLSL {@code version}. */
	public static boolean isKeyword(String word, int version) {
		Integer since = KEYWORD_SINCE.get(word);
		return since != null && since <= version;
	}

	/** True when {@code word} is reserved (an error to use) in GLSL {@code version} but is not yet a keyword. */
	public static boolean isReserved(String word, int version) {
		if (version <= 0) {
			return false; // version 0 is the parser's version-finding pass: every word is a plain identifier.
		}
		if (isKeyword(word, version)) {
			return false;
		}
		if (RESERVED_ALWAYS.contains(word)) {
			return true;
		}
		Integer from = RESERVED_FROM.get(word);
		if (from != null && from <= version) {
			return true;
		}
		Integer until = RESERVED_UNTIL.get(word);
		return until != null && version <= until;
	}

	/** True when {@code word} is a built-in type name in some GLSL version 1.10 to 4.60. */
	public static boolean isTypeWord(String word) {
		return TYPE_WORDS.contains(word);
	}

	/** True when {@code word} is a qualifier word in some GLSL version 1.10 to 4.60. */
	public static boolean isQualifierWord(String word) {
		return QUALIFIER_WORDS.contains(word);
	}

	/** True when {@code word} shapes a statement or is a boolean literal in some GLSL version. */
	public static boolean isStatementWord(String word) {
		return STATEMENT_WORDS.contains(word);
	}

	/**
	 * The words an enabled extension makes keywords under any {@code #version} (GLSL 4.20 and 4.30 extension lists): only
	 * these words, not the whole keyword set of the version the extension belongs to (an enabled extension does not turn a
	 * later version's other words into keywords). Empty for an extension that adds no words.
	 */
	public static Set<String> extensionWords(String extension) {
		return switch (extension) {
			case "GL_ARB_shader_image_load_store" -> Set.of("coherent", "volatile", "restrict", "readonly", "writeonly",
				"image1D", "image2D", "image3D", "imageCube", "image2DRect", "image1DArray", "image2DArray",
				"imageCubeArray", "imageBuffer", "image2DMS", "image2DMSArray",
				"iimage1D", "iimage2D", "iimage3D", "iimageCube", "iimage2DRect", "iimage1DArray", "iimage2DArray",
				"iimageCubeArray", "iimageBuffer", "iimage2DMS", "iimage2DMSArray",
				"uimage1D", "uimage2D", "uimage3D", "uimageCube", "uimage2DRect", "uimage1DArray", "uimage2DArray",
				"uimageCubeArray", "uimageBuffer", "uimage2DMS", "uimage2DMSArray");
			case "GL_ARB_shader_atomic_counters" -> Set.of("atomic_uint");
			case "GL_ARB_shader_storage_buffer_object" -> Set.of("buffer");
			case "GL_ARB_compute_shader" -> Set.of("shared");
			default -> Set.of();
		};
	}
}
