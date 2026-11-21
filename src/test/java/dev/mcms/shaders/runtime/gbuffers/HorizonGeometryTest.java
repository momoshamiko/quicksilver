// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The horizon fan's coverage (plan.md E4, the horizon draw): every direction below the horizon must meet the fan within
 * the far plane, no direction above it may, and every triangle must face the eye (the fan is drawn with the game's cull
 * state; its winding is part of the contract). Ray casts against the emitted vertices, not the formula.
 */
class HorizonGeometryTest {

	private static final int N = HorizonGeometry.SEGMENTS;

	/** The game's sky disc: flat at height 16, radius 512 (buildSkyDisc, javap 2026-11-14), so its edge sits atan(16/512) up. */
	private static final double DISC_EDGE = Math.toDegrees(Math.atan(16.0 / 512.0));

	@Test
	void everyDirectionUpToTheSkyDiscEdgeMeetsTheFanWithinTheFarPlane() {
		for (double far : new double[] {96, 192, 1024}) {
			float[] fan = fan(far);
			Random random = new Random(7);
			for (int i = 0; i < 4000; i++) {
				// Elevations from -90 (nadir) to just below the disc's edge: the gap between the fan's rim and the
				// disc's edge is the black band the game showed, so the fan must reach past the edge.
				double elevation = Math.toRadians(-90 + (90 + DISC_EDGE - 0.05) * random.nextDouble());
				double azimuth = 2 * Math.PI * random.nextDouble();
				double t = nearestHit(fan, dir(elevation, azimuth));
				assertTrue(t > 0 && t <= far, "far " + far + ": no fan hit within the far plane at elevation "
					+ Math.toDegrees(elevation) + " azimuth " + Math.toDegrees(azimuth) + " (t " + t + ")");
			}
		}
	}

	@Test
	void nothingAboveTheFansRimMeetsTheFan() {
		float[] fan = fan(192);
		double rim = HorizonGeometry.rimElevation();
		Random random = new Random(11);
		for (int i = 0; i < 4000; i++) {
			double elevation = Math.toRadians(rim + 0.05 + (89.95 - rim - 0.05) * random.nextDouble());
			double azimuth = 2 * Math.PI * random.nextDouble();
			assertEquals(Double.POSITIVE_INFINITY, nearestHit(fan, dir(elevation, azimuth)),
				"the fan must stay below its rim, hit at elevation " + Math.toDegrees(elevation));
		}
	}


	@Test
	void everyTriangleFacesTheEyeAtTheOrigin() {
		float[] fan = fan(192);
		int vertices = HorizonGeometry.vertexCount(N);
		assertEquals((N + 2) * 3, fan.length, "a triangle fan: the apex, N rim vertices and the closing rim vertex");
		for (int i = 1; i + 1 < vertices; i++) {
			double[] a = vertex(fan, 0), b = vertex(fan, i), c = vertex(fan, i + 1);
			double[] ab = sub(b, a), ac = sub(c, a);
			double[] normal = cross(ab, ac);
			double toEye = dot(normal, sub(new double[] {0, 0, 0}, a));
			assertTrue(toEye > 0, "triangle " + i + " faces away from the eye (the cull state would drop it)");
		}
	}

	@Test
	void theFanDoesNotReachBeyondTheFarPlane() {
		float[] fan = fan(192);
		for (int i = 0; i < HorizonGeometry.vertexCount(N); i++) {
			double[] v = vertex(fan, i);
			double distance = Math.sqrt(dot(v, v));
			assertTrue(distance <= 192, "vertex " + i + " lies beyond the far plane at distance " + distance);
		}
	}

	private static float[] fan(double far) {
		float[] out = new float[HorizonGeometry.vertexCount(N) * 3];
		HorizonGeometry.fan(far, N, out);
		return out;
	}

	/** A unit direction from an elevation (negative below the horizon) and an azimuth in the x-z plane. */
	private static double[] dir(double elevation, double azimuth) {
		double c = Math.cos(elevation);
		return new double[] {c * Math.cos(azimuth), Math.sin(elevation), c * Math.sin(azimuth)};
	}

	/** The nearest positive ray parameter at which the ray from the origin meets a triangle of the fan (Moeller-Trumbore). */
	private static double nearestHit(float[] fan, double[] d) {
		double best = Double.POSITIVE_INFINITY;
		int vertices = HorizonGeometry.vertexCount(N);
		double[] apex = vertex(fan, 0);
		for (int i = 1; i + 1 < vertices; i++) {
			double t = rayTriangle(d, apex, vertex(fan, i), vertex(fan, i + 1));
			if (t > 0 && t < best) {
				best = t;
			}
		}
		return best;
	}

	private static double rayTriangle(double[] d, double[] v0, double[] v1, double[] v2) {
		double[] e1 = sub(v1, v0), e2 = sub(v2, v0);
		double[] p = cross(d, e2);
		double det = dot(e1, p);
		if (Math.abs(det) < 1e-12) {
			return Double.NaN;
		}
		double inv = 1 / det;
		double[] s = sub(new double[] {0, 0, 0}, v0);
		double u = dot(s, p) * inv;
		if (u < -1e-9 || u > 1 + 1e-9) {
			return Double.NaN;
		}
		double[] q = cross(s, e1);
		double v = dot(d, q) * inv;
		if (v < -1e-9 || u + v > 1 + 1e-9) {
			return Double.NaN;
		}
		return dot(e2, q) * inv;
	}

	private static double[] vertex(float[] fan, int i) {
		return new double[] {fan[3 * i], fan[3 * i + 1], fan[3 * i + 2]};
	}

	private static double[] sub(double[] a, double[] b) {
		return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
	}

	private static double[] cross(double[] a, double[] b) {
		return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
	}

	private static double dot(double[] a, double[] b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}
}
