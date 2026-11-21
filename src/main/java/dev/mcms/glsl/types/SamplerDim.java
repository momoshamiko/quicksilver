// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.types;

/**
 * The dimension part of a sampler or image type word: the texture dimension and whether it is a texture
 * array. The word is the part between {@code sampler}/{@code image} and {@code Shadow} in the GLSL spelling
 * ({@code 2DArray}, {@code Cube}, {@code 2DMS}, ...).
 */
public enum SamplerDim {
	D1("1D", 1, false),
	D2("2D", 2, false),
	D3("3D", 3, false),
	CUBE("Cube", 3, false),
	RECT("2DRect", 2, false),
	BUFFER("Buffer", 1, false),
	D1_ARRAY("1DArray", 1, true),
	D2_ARRAY("2DArray", 2, true),
	CUBE_ARRAY("CubeArray", 3, true),
	D2MS("2DMS", 2, false),
	D2MS_ARRAY("2DMSArray", 2, true);

	private final String word;
	private final int coords;
	private final boolean arrayed;

	SamplerDim(String word, int coords, boolean arrayed) {
		this.word = word;
		this.coords = coords;
		this.arrayed = arrayed;
	}

	/** The part of the type word after {@code sampler}/{@code image}. */
	public String word() {
		return word;
	}

	/** How many coordinates the texture has (an arrayed texture adds one layer coordinate on top). */
	public int coords() {
		return coords;
	}

	/** True for {@code 1DArray}, {@code 2DArray} and {@code CubeArray}. */
	public boolean arrayed() {
		return arrayed;
	}

	/** True for the multisample dimensions. */
	public boolean multisample() {
		return this == D2MS || this == D2MS_ARRAY;
	}
}
