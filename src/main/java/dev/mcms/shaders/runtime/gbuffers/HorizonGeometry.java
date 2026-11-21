// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

/**
 * The horizon: the geometry that covers the directions below the horizon in the sky phase (plan.md E4).
 *
 * <p>Facts (2026-11-14, javap of the 26.3 jar and the game's shaders): the game's sky disc is one flat fan at height +16
 * blocks, and nothing draws below the horizon in the overworld: the main target keeps the clear colour, which is the fog
 * colour. The Iris reference lists {@code gbuffers_skybasic}'s geometry as "sky color/horizon, stars, void", so a pack
 * sees no horizon unless the engine draws one.
 *
 * <p>Decision (E4, the rim's height): the rim rises past the sky disc's edge. The disc is flat at height 16 with radius 512
 * ({@code buildSkyDisc}, javap 2026-11-14), so from the eye its edge sits {@code atan(16 / 512)} (about 1.79 degrees) above
 * horizontal: directions between the horizon and the disc's edge are covered by neither the disc nor a horizon-level rim,
 * which showed as a black band between them (2026-11-14, preview 9: about 25-40 px). The fan's rim therefore sits
 * {@link #RIM_CLEARANCE_DEGREES} above the disc's edge, so its coverage overlaps the disc; the game's sky pass draws the
 * disc after the clear hook's horizon, so the overlap is harmless (the disc's fog-blended colour wins).
 *
 * <p>Decision (E4): the horizon is a triangle fan that the game's {@code sky} pipeline draws (topology TRIANGLE_FAN, so the
 * fan is the only shape the pipeline's state allows). Its apex sits at the nadir, {@code (0, -R, 0)}, and its rim is a
 * circle of radius {@code R} at the rim elevation above, with {@code R = 0.999 x far plane}. Every ray leaving the eye
 * below the rim crosses the fan's lateral surface once, so the fan covers every direction up to the disc's edge out to
 * the far plane (within the polygon's 0.12% inset from the circle, see {@link #RIM_FRACTION}). No ray above the rim
 * meets it. Its triangles face the eye at the
 * origin, so the game's cull state keeps them. The fan is not a sphere because the pipeline is a fan. It sits at a finite
 * distance, so its depth would be wrong to write: the game's sky pipeline has no depth state (its builder default is
 * empty: neither test nor write, javap 2026-11-14), and the horizon draws in the same pass shape, without a depth
 * attachment ({@link Horizon}).
 *
 * <p>The colour is the fog colour: the game clears the main target to it, so the horizon is the same colour the game
 * shows there, and the sky pipeline takes its colour from {@code DynamicTransforms.ColorModulator} (the caller sets it).
 */
public final class HorizonGeometry {

	/** The rim's segment count: the fan's triangles (one per segment). */
	public static final int SEGMENTS = 64;

	/** The rim radius as a fraction of the far plane: 0.999, so nothing reaches past the far plane (clipping) at the rim. */
	public static final double RIM_FRACTION = 0.999;

	/** The game's sky disc: flat at this height, with this radius ({@code buildSkyDisc}, javap 2026-11-14). */
	public static final double DISC_HEIGHT = 16, DISC_RADIUS = 512;

	/**
	 * How far the fan's rim rises above the disc's edge, in degrees: the overlap that closes the gap between them (the
	 * polygon's chords sit a little inside the rim circle, and the clearance keeps them above the edge too).
	 */
	public static final double RIM_CLEARANCE_DEGREES = 0.3;

	/** The fan's rim elevation in degrees: the disc's edge ({@code atan(16 / 512)}, about 1.79) plus the clearance. */
	public static double rimElevation() {
		return Math.toDegrees(Math.atan(DISC_HEIGHT / DISC_RADIUS)) + RIM_CLEARANCE_DEGREES;
	}

	private HorizonGeometry() {
	}

	/** The fan's vertex count: the apex, the {@code segments} rim vertices and the closing rim vertex. */
	public static int vertexCount(int segments) {
		return segments + 2;
	}

	/**
	 * Writes the fan's positions (x, y, z per vertex, eye-relative blocks) for a far plane in blocks into {@code out}, which
	 * must hold {@code 3 * vertexCount(segments)} floats. Allocates nothing (the frame path reuses the buffer).
	 *
	 * <p>Winding: the apex, then the rim with its azimuth decreasing, so each triangle's normal points at the eye.
	 */
	public static void fan(double farPlane, int segments, float[] out) {
		if (out.length < 3 * vertexCount(segments)) {
			throw new IllegalArgumentException("the fan needs " + 3 * vertexCount(segments) + " floats, got " + out.length);
		}
		double radius = farPlane * RIM_FRACTION;
		out[0] = 0f;
		out[1] = (float) -radius;
		out[2] = 0f;
		for (int i = 0; i <= segments; i++) {
			// The closing vertex repeats the first rim vertex (i == segments), so the fan closes without a wrap test.
			double azimuth = -2 * Math.PI * (i % segments) / segments;
			int at = 3 * (i + 1);
			out[at] = (float) (radius * Math.cos(azimuth));
			out[at + 1] = (float) (radius * Math.tan(Math.toRadians(rimElevation())));
			out[at + 2] = (float) (radius * Math.sin(azimuth));
		}
	}
}
