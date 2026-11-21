// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.shadow;

import java.util.Map;

import org.joml.Matrix4f;

import dev.mcms.shaders.pack.ConstValue;
import dev.mcms.shaders.pack.ShaderProperties;

/**
 * The shadow camera of the light (E5; engine-pack-format.md section 16; the Iris shadow pages): {@code shadowModelView},
 * {@code shadowProjection} and their inverses, built once per frame from the light's direction, the camera's position
 * and the pack's shadow constants. Matrices are in OpenGL's convention like every pack matrix (clip z from -1 to 1).
 *
 * <p>The formulas, and where the docs say nothing each choice (every fit is fitted to the observed outputs of Vitrail's
 * shadow pass in the mcms-shadow test pack's map view: the 2026-11-14 runs in results/2026-11-14-e5-preview1-map,
 * results/2026-11-14-e5-preview3-map and results/2026-11-14-e5-d192; ShadowVitrailMatchTest holds the numbers):
 * <ul>
 * <li>The shadow view looks along the light: its Z axis is the light's direction (toward the light), so the scene lies
 * in front of it. Its Y axis is the sun path's normal, negated: for {@code sunPathRotation} p, Y = (0, -sin p, -cos p)
 * (the path's plane holds east and (0, cos p, -sin p), FrameUniforms' celestial tilt, E7). The sun and moon stay on that
 * plane (the moon is opposite the sun), so the up axis is never parallel to the light, for every p.
 * At p = 0, Y = (0, 0, -1) and X = Y x Z. [fit: the docs name no up axis; the sign is Vitrail's]</li>
 * <li>The centre is the centre of the camera's cell of the world grid of {@code shadowIntervalSize}, per world axis
 * (shaders.properties: "snaps geometry to the block grid ... the geometry in the shadow pass only shifts when the player
 * crosses the boundary"). The camera's offset from that centre, {@code mod(c, I) - I/2} with a positive modulus, is
 * carried into the view as R times the offset. [fit to Vitrail; the docs say "block grid" and give no centre]</li>
 * <li>{@code shadowDistance} is the radius of the shadow box, the orthographic half-width; it only scales the
 * projection (shaders.properties: shadowDistance "applies a scaling factor to the shadow projection matrix"). A
 * nonzero {@code shadowMapFov} (the docs: "setting the FOV ... changes the rendering to a projection") switches to a
 * perspective projection with that vertical FOV and aspect 1, from an eye {@link #EYE_OFFSET} toward the light, with
 * the near and far planes {@code shadowNearPlane} and {@code shadowFarPlane}. [choice: the docs name no eye position]</li>
 * <li>The depth reach does not depend on {@code shadowDistance}: {@code EYE_OFFSET + shadowNearPlane} toward the light
 * and {@code shadowFarPlane - EYE_OFFSET} away from it, -100.05 and 156 with the documented 0.05 and 256 (the
 * constants pages). [fit: Vitrail's shadowProjection at shadowDistance 128 and 192 both give n = -100.05, f = 156]</li>
 * <li>{@code shadowDistanceRenderMul} > 0 gives the render-distance culling radius {@code shadowDistance * mul}; the
 * matrices do not change. (shaders.properties: shadowDistanceRenderMul.)</li>
 * </ul>
 * Nothing on the per-frame path allocates: every matrix is preallocated and the arithmetic is on primitives.
 */
public final class ShadowCamera {

	/**
	 * The pack constants that shape the shadow camera. {@code distance} is {@code shadowDistance} (blocks, the radius),
	 * {@code fovDegrees} is {@code shadowMapFov} (0 keeps the orthographic projection), {@code intervalSize} is
	 * {@code shadowIntervalSize} (0 disables the snapping), {@code distanceRenderMul} is {@code shadowDistanceRenderMul}
	 * (a positive value enables render-distance culling), {@code pathRotationDegrees} is {@code sunPathRotation},
	 * {@code resolution} is {@code shadowMapResolution}, {@code nearPlane} and {@code farPlane} are
	 * {@code shadowNearPlane} and {@code shadowFarPlane}, and {@code voxelDistance} is the half-width {@code reversed}
	 * keeps from the caster cull ({@link CasterCull}).
	 */
	public record Settings(int resolution, float fovDegrees, float distance, float intervalSize,
			float distanceRenderMul, float pathRotationDegrees, float nearPlane, float farPlane,
			ShaderProperties.ShadowCulling culling, float voxelDistance) {

		/** The documented defaults (OptiFine "Fragment Shader Configuration"; Iris constants pages). */
		public static final Settings DEFAULTS = new Settings(1024, 0f, 160f, 2f, -1f, 0f, 0.05f, 256f,
			ShaderProperties.ShadowCulling.TRUE, 160f);

		/** Settings with the documented near and far planes (0.05 and 256), {@code voxelDistance} and {@code shadow.culling}. */
		public Settings(int resolution, float fovDegrees, float distance, float intervalSize, float distanceRenderMul,
				float pathRotationDegrees) {
			this(resolution, fovDegrees, distance, intervalSize, distanceRenderMul, pathRotationDegrees, 0.05f, 256f,
				ShaderProperties.ShadowCulling.TRUE, DEFAULTS.voxelDistance());
		}

		/**
		 * The settings of a set: each shadow constant the pack declares (the first running program wins, as
		 * {@code TargetUnion} gives it), the documented default for the rest.
		 *
		 * @param shadowConstants {@code TargetUnion.shadowConstants()}
		 * @param pathRotation    the pack's {@code sunPathRotation} in degrees
		 */
		public static Settings of(Map<String, ConstValue> shadowConstants, float pathRotation) {
			return of(shadowConstants, pathRotation, ShaderProperties.ShadowCulling.TRUE);
		}

		/**
		 * The settings of a set with the pack's {@code shadow.culling} as well: {@code false} makes the light's list use the
		 * render distance and no distance cull for entities ({@link #cullRadius}) and culls no casters; {@code true} and
		 * {@code reversed} cull the terrain casters that cannot shade the view ({@link CasterCull}), and {@code reversed}
		 * keeps them within {@code voxelDistance} of the camera.
		 */
		public static Settings of(Map<String, ConstValue> shadowConstants, float pathRotation,
				ShaderProperties.ShadowCulling culling) {
			return new Settings(
				(int) value(shadowConstants, "shadowMapResolution", DEFAULTS.resolution()),
				value(shadowConstants, "shadowMapFov", DEFAULTS.fovDegrees()),
				value(shadowConstants, "shadowDistance", DEFAULTS.distance()),
				value(shadowConstants, "shadowIntervalSize", DEFAULTS.intervalSize()),
				value(shadowConstants, "shadowDistanceRenderMul", DEFAULTS.distanceRenderMul()),
				pathRotation,
				value(shadowConstants, "shadowNearPlane", DEFAULTS.nearPlane()),
				value(shadowConstants, "shadowFarPlane", DEFAULTS.farPlane()), culling,
				value(shadowConstants, "voxelDistance", DEFAULTS.voxelDistance()));
		}

		private static float value(Map<String, ConstValue> constants, String name, float dflt) {
			ConstValue v = constants.get(name);
			return v == null || v.values().isEmpty() ? dflt : v.values().get(0).floatValue();
		}
	}

	/**
	 * How far the shadow eye stands from the box centre toward the light, in blocks. The orthographic depth reach is
	 * {@code EYE_OFFSET + nearPlane} toward the light and {@code farPlane - EYE_OFFSET} away from it. [fit: Vitrail's
	 * shadowProjection has -2/(f-n) = -0.007811 and -(f+n)/(f-n) = -0.218512 at shadowDistance 128 and at 192 alike,
	 * so n = -100.05 and f = 156 with the documented planes 0.05 and 256]
	 */
	public static final float EYE_OFFSET = 100f;

	private Settings settings = Settings.DEFAULTS;
	/** The up axis of the shadow view: (0, -sin p, -cos p) for the sun's path tilt p (see the class comment). */
	private float upX, upY, upZ;
	private final Matrix4f rotation = new Matrix4f();
	private final Matrix4f modelView = new Matrix4f();
	private final Matrix4f modelViewInverse = new Matrix4f();
	private final Matrix4f projection = new Matrix4f();
	private final Matrix4f projectionInverse = new Matrix4f();
	private final Matrix4f viewProjection = new Matrix4f();
	private float cullRadius = -1f;

	/** Starts with the documented defaults; {@link #configure} replaces them at a set's install. */
	public ShadowCamera() {
		configure(Settings.DEFAULTS);
	}

	/** Sets the pack's shadow constants (set install; not per frame). */
	public void configure(Settings settings) {
		this.settings = settings;
		double phi = Math.toRadians(settings.pathRotationDegrees());
		this.upX = 0f;
		this.upY = (float) -Math.sin(phi);
		this.upZ = (float) -Math.cos(phi);
		float distance = settings.distance();
		if (settings.fovDegrees() > 0f) {
			this.projection.setPerspective((float) Math.toRadians(settings.fovDegrees()), 1f, settings.nearPlane(),
				settings.farPlane());
		} else {
			this.projection.setOrtho(-distance, distance, -distance, distance, -(EYE_OFFSET + settings.nearPlane()),
				settings.farPlane() - EYE_OFFSET);
		}
		this.projection.invert(this.projectionInverse);
		this.cullRadius = settings.culling() == ShaderProperties.ShadowCulling.FALSE || settings.distanceRenderMul() <= 0f
			? -1f : distance * settings.distanceRenderMul();
	}

	/** The settings in effect. */
	public Settings settings() {
		return settings;
	}

	/**
	 * Computes this frame's matrices. Allocates nothing.
	 *
	 * @param cameraX the camera's world X (the raw position, not the floating origin)
	 * @param cameraY the camera's world Y (the eye: feet plus 1.62 when standing)
	 * @param cameraZ the camera's world Z
	 * @param lightX  the light's direction in player space, unit length, pointing toward the light (the sun by day, the
	 *                moon by night; FrameUniforms' shadowLightPosition)
	 * @param lightY  the light's direction, Y
	 * @param lightZ  the light's direction, Z
	 */
	public void update(double cameraX, double cameraY, double cameraZ, float lightX, float lightY, float lightZ) {
		double len = Math.sqrt((double) lightX * lightX + (double) lightY * lightY + (double) lightZ * lightZ);
		double zx = lightX / len, zy = lightY / len, zz = lightZ / len;
		// X = normalize(up x Z), Y = Z x X: the light's view basis (rows of the rotation).
		double ax = upY * zz - upZ * zy, ay = upZ * zx - upX * zz, az = upX * zy - upY * zx;
		double al = Math.sqrt(ax * ax + ay * ay + az * az);
		ax /= al;
		ay /= al;
		az /= al;
		double bx = zy * az - zz * ay, by = zz * ax - zx * az, bz = zx * ay - zy * ax;
		this.rotation.identity()
			.m00((float) ax).m10((float) ay).m20((float) az)
			.m01((float) bx).m11((float) by).m21((float) bz)
			.m02((float) zx).m12((float) zy).m22((float) zz);
		// The camera's offset from the centre of its grid cell, per world axis; the view carries R times it.
		double interval = settings.intervalSize();
		double ox = offset(cameraX, interval), oy = offset(cameraY, interval), oz = offset(cameraZ, interval);
		double tx = ax * ox + ay * oy + az * oz;
		double ty = bx * ox + by * oy + bz * oz;
		double tz = zx * ox + zy * oy + zz * oz;
		if (settings.fovDegrees() > 0f) {
			tz -= EYE_OFFSET;
		}
		this.modelView.identity().translate((float) tx, (float) ty, (float) tz).mul(this.rotation);
		this.modelView.invert(this.modelViewInverse);
		this.projection.mul(this.modelView, this.viewProjection);
	}

	/**
	 * The camera's offset from the centre of its grid cell on one axis: {@code c % I - I/2} with the remainder taking the
	 * coordinate's sign (Java's {@code %}), so the cells of negative coordinates mirror the positive ones. Iris 1.11.7 snaps so
	 * (mcms-uniforms, 2026-11-16: at x = -100.5 and z = -274.5 its shadowModelView translation equals this, and a positive
	 * modulus put the cell one interval over). Zero when the snapping is off.
	 */
	private static double offset(double c, double interval) {
		if (interval <= 0.0) {
			return 0.0;
		}
		return c % interval - interval / 2.0;
	}

	/** {@code shadowModelView}: player space to shadow view space (this frame). */
	public Matrix4f modelView() {
		return modelView;
	}

	/** {@code shadowModelViewInverse}. */
	public Matrix4f modelViewInverse() {
		return modelViewInverse;
	}

	/** {@code shadowProjection}: shadow view space to shadow clip space (OpenGL convention). */
	public Matrix4f projection() {
		return projection;
	}

	/** {@code shadowProjectionInverse}. */
	public Matrix4f projectionInverse() {
		return projectionInverse;
	}

	/** The product {@code projection * modelView} the light's frustum is built from (player space to clip space). */
	public Matrix4f viewProjection() {
		return viewProjection;
	}

	/** The render-distance culling radius in blocks ({@code shadowDistance * shadowDistanceRenderMul}), or -1 for none. */
	public float cullRadius() {
		return cullRadius;
	}
}
