// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

/**
 * The colour target a sampler or output name refers to (engine-pack-format.md section 7): {@code colortexN} for 0 to 15, and
 * the legacy aliases {@code gcolor}, {@code gdepth}, {@code gnormal}, {@code composite} and {@code gaux1} to {@code gaux4}.
 * Pure: the runtime's ProgramRequests delegates here, and the pack's texture table uses it without the runtime.
 */
public final class ColourNames {

	private ColourNames() {
	}

	/** The colour target index of a name (colortex4 and gaux1 are 4), or -1 for any other name. */
	public static int index(String name) {
		if (name.startsWith("colortex")) {
			int value = 0;
			for (int k = "colortex".length(); k < name.length(); k++) {
				char c = name.charAt(k);
				if (c < '0' || c > '9') {
					return -1;
				}
				value = value * 10 + (c - '0');
				if (value > 15) {
					return -1;
				}
			}
			return name.length() > "colortex".length() ? value : -1;
		}
		return switch (name) {
			case "gcolor" -> 0;
			case "gdepth" -> 1;
			case "gnormal" -> 2;
			case "composite" -> 3;
			case "gaux1" -> 4;
			case "gaux2" -> 5;
			case "gaux3" -> 6;
			case "gaux4" -> 7;
			default -> -1;
		};
	}
}
