// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.uniforms;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import dev.mcms.glsl.msl.ProgramRequest.UniformBlock;
import dev.mcms.glsl.msl.ProgramRequest.UniformField;
import dev.mcms.shaders.expr.ExprFrame;
import dev.mcms.shaders.pack.FrameLayout;
import dev.mcms.shaders.runtime.distant.DistantFrame;
import dev.mcms.shaders.runtime.distant.DistantUniforms;
import dev.mcms.shaders.runtime.shadow.ShadowCamera;

/**
 * The engine's frame block {@code QsFrame}: every standard uniform the game state gives without per-draw hooks
 * (engine-pack-format.md section 10; OptiFine doc shaders.txt "Uniforms"; the Iris uniform pages for the extras and
 * their units), in std140, filled once per frame on the render thread. One instance lives for the whole engine run;
 * nothing on the frame path allocates.
 *
 * <p>Contract (E3-A): {@link #schema(int)} builds the block for a {@code ProgramRequest.UniformSchema};
 * {@link FrameInputs#capture} reads the game; {@link #update} computes every value; {@link #write} puts the block's
 * bytes (little-endian, the backend's buffer order); {@link #fill} writes the expression parameters the pack's custom
 * uniforms read. The translator serves a uniform by field name over the request's blocks, so the field names and GLSL
 * type words here are the names packs declare; a uniform no block serves reads as zero and the translator's reflection
 * lists it (E3-A logs those once per program).
 *
 * <p>The block provides the fields the translator builds {@code gl_Fog} from ({@code fogColor}, {@code fogDensity},
 * {@code fogStart}, {@code fogEnd}) and {@code alphaTestRef} (the per-pass value; E3-A sets it through
 * {@link #setAlphaTestRef(float)} before a program that declares it). The per-draw matrices ({@code modelViewMatrix},
 * {@code projectionMatrix}, {@code textureMatrix}, {@code normalMatrix} and the per-draw inverses), the per-draw ids
 * ({@code entityId}, {@code blockEntityId}, {@code renderStage}, ...) and the Distant Horizons matrices are later
 * milestones, not this block. The shadow matrices come from {@link ShadowCamera}.
 */
public final class FrameUniforms {
	/** The frame block's name. */
	public static final String BLOCK_NAME = "QsFrame";

	/** One game tick in seconds; the smoothing half-lives are documented in ticks (OptiFine doc, section "Fragment Shader Configuration"). */
	private static final float TICK_SECONDS = 1f / 20f;
	/** {@code frameTimeCounter} wraps at 3600 s; {@code frameCounter} counts 0 to 720719 then resets (OptiFine doc). */
	private static final float CLOCK_WRAP = 3600f;
	private static final int FRAME_WRAP = 720720;
	/** The near plane is fixed at 0.05 blocks (the game's {@code Camera.PROJECTION_Z_NEAR}; Iris "Rendering" page: near). */
	private static final float NEAR = 0.05f;
	/** dhNearPlane and dhFarPlane while Distant Horizons draws nothing (Iris 1.11.7, measured with mcms-uniforms). */
	static final float NO_DISTANT_PLANE = 0.01f;
	/** The GL fog-mode codes (OptiFine doc: GL_LINEAR, GL_EXP, GL_EXP2; the values from the Iris "Rendering" page). */
	private static final int FOG_LINEAR = 9729;
	/** The camera wrap: cameraPosition is relative to a floating origin that re-anchors at 30000 blocks or on a jump over 1000 blocks (Iris "Camera" page). */
	private static final double WRAP_DISTANCE = 30000.0, JUMP_DISTANCE = 1000.0;

	/** The block's fields, in block order. Every entry's source and unit is the comment; §10 is engine-pack-format.md section 10. */
	private static final List<Std140.Member> MEMBERS = List.of(
		// --- Time and clock. frameTimeCounter/frameTime/frameCounter are the engine's own clock (§10 "Time");
		// worldTime/worldDay/moonPhase from the level's overworld clock (§10; "/time query daytime" semantics);
		// sunAngle 0 sunrise, 0.25 noon, 0.5 sunset, 0.75 midnight (Iris "World and Weather"); shadowAngle from the
		// highest celestial body, 0 to 0.5; moonPhase ordinal, 0 full moon.
		new Std140.Member("frameTimeCounter", "float"),
		new Std140.Member("frameTime", "float"),
		new Std140.Member("frameCounter", "int"),
		new Std140.Member("worldTime", "int"),
		new Std140.Member("worldDay", "int"),
		new Std140.Member("sunAngle", "float"),
		new Std140.Member("shadowAngle", "float"),
		new Std140.Member("moonPhase", "int"),
		// --- Weather. rainStrength/thunderLevel interpolated by the game between ticks (§10); wetness is rainStrength
		// smoothed with drynessHalflife while rising and wetnessHalflife while falling, defaults 200/600 ticks.
		new Std140.Member("rainStrength", "float"),
		new Std140.Member("wetness", "float"),
		new Std140.Member("thunderStrength", "float"),
		// --- Screen and system (§10; Iris "Screen and System"): sizes in pixels, brightness the video setting 0-1,
		// near fixed 0.05, far the render distance in blocks (render distance x 16; "not the far clipping plane"),
		// alphaTestRef per pass, centerDepthSmooth smoothed with centerDepthHalflife (default 1 tick), the system
		// date/time from the OS.
		new Std140.Member("aspectRatio", "float"),
		new Std140.Member("viewWidth", "float"),
		new Std140.Member("viewHeight", "float"),
		new Std140.Member("screenBrightness", "float"),
		new Std140.Member("near", "float"),
		new Std140.Member("far", "float"),
		new Std140.Member("alphaTestRef", "float"),
		new Std140.Member("centerDepthSmooth", "float"),
		new Std140.Member("currentDate", "ivec3"),
		new Std140.Member("currentTime", "ivec3"),
		new Std140.Member("currentYearTime", "ivec2"),
		// --- Fog and sky (§10 "Fog"; OptiFine shaders.txt fogStart/fogEnd/fogShape; Iris "Rendering"): fogColor the horizon
		// fog colour, skyColor the upper sky colour. The game's terrain fog is max(spherical, cylindrical) of its two
		// linear ranges (26.3 fog.glsl, total_fog_value). One range is reported: the one that starts closer, with its shape
		// (0 sphere, 1 cylinder). gl_Fog reads the same four fields, so gl_Fog.start/end equal fogStart/fogEnd. The game uses
		// linear fog, so density is 0.
		new Std140.Member("fogColor", "vec3"),
		new Std140.Member("fogDensity", "float"),
		new Std140.Member("fogStart", "float"),
		new Std140.Member("fogEnd", "float"),
		new Std140.Member("fogMode", "int"),
		new Std140.Member("fogShape", "int"),
		new Std140.Member("skyColor", "vec3"),
		// --- Positions (§10 "Positions"): sun/moon/shadowLight/up in view space at length 100; the celestial angle
		// turns east -> up -> west (sunAngle 0 = sunrise at +X). cameraPosition relative to the floating origin;
		// Fract/Int of the exact position (Iris "Camera"); eyeAltitude the camera's Y.
		new Std140.Member("sunPosition", "vec3"),
		new Std140.Member("moonPosition", "vec3"),
		new Std140.Member("shadowLightPosition", "vec3"),
		new Std140.Member("upPosition", "vec3"),
		new Std140.Member("cameraPosition", "vec3"),
		new Std140.Member("previousCameraPosition", "vec3"),
		new Std140.Member("cameraPositionFract", "vec3"),
		new Std140.Member("cameraPositionInt", "ivec3"),
		new Std140.Member("previousCameraPositionFract", "vec3"),
		new Std140.Member("previousCameraPositionInt", "ivec3"),
		new Std140.Member("eyeAltitude", "float"),
		new Std140.Member("eyePosition", "vec3"),
		new Std140.Member("relativeEyePosition", "vec3"),
		new Std140.Member("playerLookVector", "vec3"),
		new Std140.Member("playerBodyVector", "vec3"),
		// --- Matrices in OpenGL's convention (ADR 0006 section 2: the engine supplies its own GL projection; the
		// game's is zero-to-one and reversed). gbufferProjection is built from the camera's vertical FOV, the aspect,
		// near 0.05 and the game's far plane; gbufferModelView is the camera's view matrix (no bobbing: that is applied
		// per draw). Previous values are the previous frame's.
		new Std140.Member("gbufferModelView", "mat4"),
		new Std140.Member("gbufferModelViewInverse", "mat4"),
		new Std140.Member("gbufferProjection", "mat4"),
		new Std140.Member("gbufferProjectionInverse", "mat4"),
		new Std140.Member("gbufferPreviousModelView", "mat4"),
		new Std140.Member("gbufferPreviousProjection", "mat4"),
		// --- Shadow matrices (E5; engine-pack-format.md section 10): the light's view and projection, built by
		// ShadowCamera from the documented constants (see its class comment for each choice the docs leave open).
		new Std140.Member("shadowModelView", "mat4"),
		new Std140.Member("shadowModelViewInverse", "mat4"),
		new Std140.Member("shadowProjection", "mat4"),
		new Std140.Member("shadowProjectionInverse", "mat4"),
		// --- Player state (§10 "Player"): isEyeInWater 0/1/2/3 from the camera's fluid; nightVision/blindness/
		// darkness as the game's effect strengths 0-1; eyeBrightness the lightmap value at the camera, 0-240
		// (block, sky), smoothed with eyeBrightnessHalflife (default 10 ticks); heldBlockLightValue the held items'
		// emission; the Iris relative stats (current/max pairs; -1 in creative and spectator, except the hunger and
		// armor maxima, which every mode reports).
		new Std140.Member("isEyeInWater", "int"),
		new Std140.Member("nightVision", "float"),
		new Std140.Member("blindness", "float"),
		new Std140.Member("darknessFactor", "float"),
		new Std140.Member("darknessLightFactor", "float"),
		new Std140.Member("playerMood", "float"),
		new Std140.Member("constantMood", "float"),
		new Std140.Member("eyeBrightness", "ivec2"),
		new Std140.Member("eyeBrightnessSmooth", "ivec2"),
		new Std140.Member("hideGUI", "int"),
		new Std140.Member("heldItemId", "int"),
		new Std140.Member("heldItemId2", "int"),
		new Std140.Member("heldBlockLightValue", "int"),
		new Std140.Member("heldBlockLightValue2", "int"),
		new Std140.Member("currentPlayerHealth", "float"),
		new Std140.Member("maxPlayerHealth", "float"),
		new Std140.Member("currentPlayerArmor", "float"),
		new Std140.Member("maxPlayerArmor", "float"),
		new Std140.Member("currentPlayerAir", "float"),
		new Std140.Member("maxPlayerAir", "float"),
		new Std140.Member("currentPlayerHunger", "float"),
		new Std140.Member("maxPlayerHunger", "float"),
		new Std140.Member("isSpectator", "bool"),
		new Std140.Member("isRightHanded", "bool"),
		new Std140.Member("firstPersonCamera", "bool"),
		new Std140.Member("isRiding", "bool"),
		new Std140.Member("inSwimmingAnimation", "bool"),
		new Std140.Member("feetInWater", "bool"),
		new Std140.Member("isElytraFlying", "bool"),
		new Std140.Member("vehicleInWater", "bool"),
		new Std140.Member("is_alive", "bool"),
		new Std140.Member("is_burning", "bool"),
		new Std140.Member("is_child", "bool"),
		new Std140.Member("is_glowing", "bool"),
		new Std140.Member("is_hurt", "bool"),
		new Std140.Member("is_in_lava", "bool"),
		new Std140.Member("is_in_water", "bool"),
		new Std140.Member("is_invisible", "bool"),
		new Std140.Member("is_on_ground", "bool"),
		new Std140.Member("is_ridden", "bool"),
		new Std140.Member("is_riding", "bool"),
		new Std140.Member("is_sneaking", "bool"),
		new Std140.Member("is_sprinting", "bool"),
		new Std140.Member("is_wet", "bool"),
		// --- Weather and world extras (Iris "World and Weather", "Biome and Dimension"): lightningBoltPosition the
		// player-space offset of one lightning bolt from the camera (w 1 when one exists); the End flash; the
		// camera's biome as the custom-uniform constants number it; the dimension's limits.
		new Std140.Member("lightningBoltPosition", "vec4"),
		new Std140.Member("endFlashPosition", "vec3"),
		new Std140.Member("endFlashIntensity", "float"),
		new Std140.Member("previousEndFlashIntensity", "float"),
		new Std140.Member("biome", "int"),
		new Std140.Member("biome_category", "int"),
		new Std140.Member("biome_precipitation", "int"),
		new Std140.Member("temperature", "float"),
		new Std140.Member("rainfall", "float"),
		new Std140.Member("ambientLight", "float"),
		new Std140.Member("bedrockLevel", "int"),
		new Std140.Member("cloudHeight", "float"),
		new Std140.Member("hasCeiling", "bool"),
		new Std140.Member("hasSkylight", "bool"),
		new Std140.Member("heightLimit", "int"),
		new Std140.Member("logicalHeightLimit", "int"),
		// --- ADR 0008: the render scale's LOD bias for the block atlas, normal and specular lookups (log2(1 / scale), 0 at the
		// window size); an engine field under a reserved name, read by the translator's biased lookups, not by pack uniforms.
		new Std140.Member(dev.mcms.glsl.msl.ProgramRequest.LOD_BIAS_FIELD, "float"));

	private static final Map<String, Integer> OFFSETS = offsets();

	private final ByteBuffer block;
	// Engine clock state.
	private long lastNanos = -1;
	private float clock, lastFrameTime;
	private int frameCounter = -1;
	// Smoothed state (persists between frames).
	private float wetness;
	private final float[] eyeSmooth = { 0f, 0f };
	private boolean smoothStarted;
	private float centerDepthSmooth;
	private float centerDepth = 1f;
	/** ADR 0008: the frame block's LOD bias (log2(1 / render scale)); the engine sets it with the targets' scale. */
	private float lodBias;
	private float constantMood, lastMood = -1f;
	// Const overrides from the pack (ticks).
	private float wetnessHalflife = 600f, drynessHalflife = 200f, eyeBrightnessHalflife = 10f,
			centerDepthHalflife = 1f;
	// The floating camera origin and the previous frame's camera values.
	private final double[] origin = new double[3];
	private boolean hasOrigin, hasPrevious;
	private double prevCamX, prevCamY, prevCamZ;
	private float prevWrappedX, prevWrappedY, prevWrappedZ;
	private float prevFractX, prevFractY, prevFractZ;
	private int prevIntX, prevIntY, prevIntZ;
	private float prevEndFlashIntensity;
	private float alphaTestRef = 0.1f;
	/** The block atlas's size for the custom uniforms' atlasSize (set per frame by {@link #setBlockAtlas}). */
	private int blockAtlasWidth, blockAtlasHeight;
	// Matrix scratch (preallocated; no allocation on the frame path).
	private final Matrix4f modelView = new Matrix4f();
	private final Matrix4f projection = new Matrix4f();
	private final Matrix4f prevModelView = new Matrix4f();
	private final Matrix4f prevProjection = new Matrix4f();
	private final Matrix4f inverse = new Matrix4f();
	/**
	 * The frame's Distant Horizons values (QsDh, engine-pack-format.md section 17): {@link #update} sets the values Iris
	 * 1.11.7 gives while DH draws nothing (measured with mcms-uniforms, 2026-11-16: both planes 0.01, dhProjection and its
	 * inverse the gbuffer ones, dhRenderDistance the game's render distance in chunks, 16 at 16 and 12 at 12;
	 * dhPreviousProjection the previous gbuffer projection [inference]); {@link #distant}
	 * replaces them with DH's own while DH draws through the pack. The QsDh block and the custom uniforms read them.
	 */
	private float dhNearPlane, dhFarPlane;
	private int dhRenderDistance;
	private final Matrix4f dhProjection = new Matrix4f();
	private final Matrix4f dhProjectionInverse = new Matrix4f();
	private final Matrix4f dhPreviousProjection = new Matrix4f();
	/** The far plane of the frame's projection (set by {@link #update}; the horizon draw reads it). */
	private float farPlane;
	private final Vector3f vec = new Vector3f();
	private final Vector3f look = new Vector3f();
	private final float[] mat = new float[16];
	private final Vector3f moonDir = new Vector3f();
	private final Vector3f upDir = new Vector3f();
	private final Vector3f sunDir = new Vector3f();
	private final Vector3f shadowLightWorld = new Vector3f();
	/** The shadow camera: {@code shadowModelView}, {@code shadowProjection} and their inverses (E5). */
	private final ShadowCamera shadow = new ShadowCamera();
	private static final float[] ZERO16 = new float[16];
	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("Quicksilver");
	/** One log line per run that the clock hold applied. */
	private static boolean holdLogged;
	private static final float[] ZERO9 = new float[9];

	/** One instance per engine run; the block buffer is preallocated here. */
	public FrameUniforms() {
		this.block = ByteBuffer.allocate(size()).order(ByteOrder.LITTLE_ENDIAN);
	}

	/**
	 * The frame block: the fields above at their std140 offsets, bound at {@code slot}.
	 *
	 * @param slot the buffer slot the engine binds the block at
	 * @return the block for a {@code ProgramRequest.UniformSchema}
	 */
	public static UniformBlock schema(int slot) {
		return Std140.block(BLOCK_NAME, slot, MEMBERS);
	}

	/** The block's padded size in bytes (what {@link #write} fills), computed once. */
	private static final int SIZE = Std140.paddedSize(schema(0));

	/** The block's padded size in bytes (what {@link #write} fills). */
	public static int size() {
		return SIZE;
	}

	/**
	 * Computes every value of the frame from the captured inputs, measuring the frame time from the wall clock.
	 * Allocates nothing.
	 *
	 * @param in the frame's captured values
	 */
	public void update(FrameInputs in) {
		long now = System.nanoTime();
		float dt = lastNanos < 0 ? 0f : (now - lastNanos) * 1e-9f;
		lastNanos = now;
		// The bench's clock hold (mcms.shaders.holdClock, seconds; read every frame so the harness can set it at run
		// time): holds only the clock (frameTimeCounter and the expression clock); the frame time, the smoothing and
		// the expression smooth() keep the real dt, as the harness holds Iris's and Vitrail's clocks.
		advance(in, dt, holdClock());
	}

	/**
	 * The frame's computation with an explicit frame time; {@link #update} measures the time and calls this.
	 *
	 * @param in   the frame's captured values
	 * @param dt   the seconds since the previous frame (always real; the hold does not stop the smoothing)
	 * @param hold the held clock value in seconds, or a negative number when the clock runs
	 */
	void advance(FrameInputs in, float dt, float hold) {
		if (hold >= 0f && !holdLogged) {
			holdLogged = true;
			LOG.info("[mcms-shaders] frame clock held at {} s (mcms.shaders.holdClock)", hold);
		}
		lastFrameTime = dt;
		if (hold >= 0f) {
			clock = hold;
		} else {
			clock += dt;
			if (clock >= CLOCK_WRAP) {
				clock -= CLOCK_WRAP;
			}
		}
		frameCounter = (frameCounter + 1) % FRAME_WRAP;
		boolean firstFrame = !hasPrevious;
		long dayTime = in.dayTime();
		int worldTime = (int) (dayTime % 24000L);
		int worldDay = (int) (dayTime / 24000L);
		// The celestial angle in turns, 0 at noon: the game's own sun angle (FrameInputs.sunAngleDegrees), not a formula.
		float celestial = frac(in.sunAngleDegrees() / 360f);
		float sunAngle = frac(celestial + 0.25f);
		float shadowAngle = sunAngle < 0.5f ? sunAngle : sunAngle - 0.5f;
		putFloat("frameTimeCounter", clock);
		putFloat("frameTime", lastFrameTime);
		putInt("frameCounter", frameCounter);
		putInt("worldTime", worldTime);
		putInt("worldDay", worldDay);
		putFloat("sunAngle", sunAngle);
		putFloat("shadowAngle", shadowAngle);
		putInt("moonPhase", in.moonPhaseOrdinal());
		// --- Weather and wetness (the half-life rule: after one half-life half the remaining distance remains).
		// drynessHalflife (default 200 ticks) governs dry-to-wet, wetnessHalflife (600 ticks) wet-to-dry (the Iris
		// constants pages: wetnessHalflife "wet to dry", drynessHalflife "dry to wet").
		float rain = in.rainLevel();
		wetness = approach(wetness, rain, (rain > wetness ? drynessHalflife : wetnessHalflife) * TICK_SECONDS, dt,
			!smoothStarted);
		putFloat("rainStrength", rain);
		putFloat("wetness", wetness);
		putFloat("thunderStrength", in.thunderLevel());
		// --- Screen and system.
		float aspect = in.height() == 0 ? 1f : (float) in.width() / in.height();
		putFloat("aspectRatio", aspect);
		putFloat("viewWidth", in.width());
		putFloat("viewHeight", in.height());
		putFloat("screenBrightness", in.screenBrightness());
		putFloat(dev.mcms.glsl.msl.ProgramRequest.LOD_BIAS_FIELD, lodBias);
		putFloat("near", NEAR);
		putFloat("far", in.renderDistanceChunks() * 16f);
		putFloat("alphaTestRef", alphaTestRef);
		centerDepthSmooth = approach(centerDepthSmooth, centerDepth, centerDepthHalflife * TICK_SECONDS, dt,
			!smoothStarted);
		putFloat("centerDepthSmooth", centerDepthSmooth);
		putInt3("currentDate", in.dateYear(), in.dateMonth(), in.dateDay());
		putInt3("currentTime", in.timeHour(), in.timeMinute(), in.timeSecond());
		putInt2("currentYearTime", in.yearSecondIn(), in.yearSecondLeft());
		// --- Fog and sky.
		putVec3("fogColor", in.fogR(), in.fogG(), in.fogB());
		putFloat("fogDensity", 0f);
		putInt("fogMode", FOG_LINEAR);
		// The game's fog.glsl takes max(linear(spherical, environmental), linear(cylindrical, renderDistance)); the
		// earlier-starting range is the fog a pack sees, with its shape (0 sphere, 1 cylinder).
		boolean environmental = in.fogEnvironmentalStart() < in.fogRenderDistanceStart();
		putFloat("fogStart", environmental ? in.fogEnvironmentalStart() : in.fogRenderDistanceStart());
		putFloat("fogEnd", environmental ? in.fogEnvironmentalEnd() : in.fogRenderDistanceEnd());
		putInt("fogShape", environmental ? 0 : 1);
		putVec3("skyColor", in.skyR(), in.skyG(), in.skyB());
		// --- Camera: cameraPosition is the in-game coordinate (Iris "Camera": "similar to the in game coordinates,
		// except for precision reasons the value is wrapped"; modern Iris resets every 30000 blocks when walking, or
		// after a teleport of more than 1000 blocks). Fract/Int always keep the exact raw position (unshifted) and
		// eyeAltitude is the raw camera Y (the F3 altitude). The wrap never shifts Y: eyeAltitude "is equivalent to
		// cameraPosition.y" (Iris "Camera"), so cameraPosition.y is always the in-game Y.
		double cx = in.cameraX(), cy = in.cameraY(), cz = in.cameraZ();
		// The teleport test compares raw positions against the previous frame's raw position.
		boolean jumped = hasOrigin
			&& (Math.abs(cx - prevCamX) > JUMP_DISTANCE || Math.abs(cy - prevCamY) > JUMP_DISTANCE
				|| Math.abs(cz - prevCamZ) > JUMP_DISTANCE);
		if (!hasOrigin) {
			// The origin starts at the world origin, so cameraPosition is the in-game coordinate until it wraps.
			hasOrigin = true;
		}
		float camX = (float) (cx - origin[0]), camY = (float) cy, camZ = (float) (cz - origin[2]);
		if (jumped || camX > WRAP_DISTANCE || camX < -WRAP_DISTANCE || camZ > WRAP_DISTANCE
			|| camZ < -WRAP_DISTANCE) {
			origin[0] = Math.floor(cx);
			origin[2] = Math.floor(cz);
			camX = (float) (cx - origin[0]);
			camZ = (float) (cz - origin[2]);
		}
		if (hasPrevious) {
			// The previous values are the previous frame's uniform values (Iris "Camera"), not this origin's
			// recomputation of the old raw position.
			putVec3("previousCameraPosition", prevWrappedX, prevWrappedY, prevWrappedZ);
			putVec3("previousCameraPositionFract", prevFractX, prevFractY, prevFractZ);
			putInt3("previousCameraPositionInt", prevIntX, prevIntY, prevIntZ);
		} else {
			putVec3("previousCameraPosition", camX, camY, camZ);
			putVec3("previousCameraPositionFract", fract(cx), fract(cy), fract(cz));
			putInt3("previousCameraPositionInt", (int) Math.floor(cx), (int) Math.floor(cy), (int) Math.floor(cz));
		}
		prevCamX = cx;
		prevCamY = cy;
		prevCamZ = cz;
		prevWrappedX = camX;
		prevWrappedY = camY;
		prevWrappedZ = camZ;
		prevFractX = fract(cx);
		prevFractY = fract(cy);
		prevFractZ = fract(cz);
		prevIntX = (int) Math.floor(cx);
		prevIntY = (int) Math.floor(cy);
		prevIntZ = (int) Math.floor(cz);
		hasPrevious = true;
		putVec3("cameraPosition", camX, camY, camZ);
		putVec3("cameraPositionFract", prevFractX, prevFractY, prevFractZ);
		putInt3("cameraPositionInt", prevIntX, prevIntY, prevIntZ);
		putFloat("eyeAltitude", (float) cy);
		// --- Matrices (OpenGL convention): gbufferModelView is the camera's view rotation captured for this frame
		// (player space to view space, no camera translation; Iris "Matrices"); gbufferProjection is built from the
		// camera's vertical FOV, the aspect, near 0.05 and the game's far plane. Both are set, not multiplied into
		// the previous frame's matrices.
		look.set(in.lookX(), in.lookY(), in.lookZ());
		if (!firstFrame) {
			prevModelView.set(modelView);
			prevProjection.set(projection);
		}
		modelView.set(in.viewRotation());
		projection.setPerspective((float) Math.toRadians(in.fovY()), aspect, NEAR, in.farPlane());
		this.farPlane = in.farPlane();
		if (firstFrame) {
			prevModelView.set(modelView);
			prevProjection.set(projection);
		}
		putMat4("gbufferModelView", modelView);
		putMat4("gbufferModelViewInverse", inverse.set(modelView).invert());
		putMat4("gbufferProjection", projection);
		putMat4("gbufferProjectionInverse", inverse.set(projection).invert());
		putMat4("gbufferPreviousModelView", prevModelView);
		putMat4("gbufferPreviousProjection", prevProjection);
		// Distant Horizons values without DH (Iris's, see the fields); the engine's distant(...) replaces them while DH draws.
		dhNearPlane = NO_DISTANT_PLANE;
		dhFarPlane = NO_DISTANT_PLANE;
		dhRenderDistance = in.renderDistanceChunks();
		dhProjection.set(projection);
		dhProjectionInverse.set(projection).invert();
		dhPreviousProjection.set(prevProjection);
		// --- Celestial positions in view space (length 100): the sun at sunAngle turns east (+X) -> up -> west,
		// the path tilted around the east axis by the pack's sunPathRotation (rotated perpendicular to the direction
		// of travel; 0 crosses the top of the sky dome). A positive value tilts the noon sun toward -Z: packs that
		// rebuild the sun from the constant compute it so (BSL v10.1.8, lib/atmospherics/sunmoon.glsl: the world
		// direction (-sin a, cos a cos p, -cos a sin p) with a = 2 pi (sunAngle - 1/4)), and E7 measured it: BSL,
		// Unbound, Mellow and Photon (tilts -40, -40, -30, -35) drew their shaded faces dark against Iris and Vitrail
		// under the opposite sign, while every tilt-0 configuration matched (results/2026-11-15-e7-s2probe).
		double theta = 2d * Math.PI * sunAngle;
		double phi = Math.toRadians(sunPathRotation);
		vec.set((float) Math.cos(theta), (float) (Math.sin(theta) * Math.cos(phi)),
			(float) (-Math.sin(theta) * Math.sin(phi)));
		sunDir.set(vec);
		moonDir.set(vec).negate();
		// The shadow camera looks along the light's world direction (before the view transform above).
		shadowLightWorld.set(sunAngle < 0.5f ? sunDir : moonDir);
		shadow.update(cx, cy, cz, shadowLightWorld.x, shadowLightWorld.y, shadowLightWorld.z);
		modelView.transformDirection(sunDir).mul(100f);
		modelView.transformDirection(moonDir).mul(100f);
		Vector3f shadowLight = sunAngle < 0.5f ? sunDir : moonDir;
		upDir.set(0f, 1f, 0f);
		modelView.transformDirection(upDir).mul(100f);
		putVec3("sunPosition", sunDir.x, sunDir.y, sunDir.z);
		putVec3("moonPosition", moonDir.x, moonDir.y, moonDir.z);
		putVec3("shadowLightPosition", shadowLight.x, shadowLight.y, shadowLight.z);
		putVec3("upPosition", upDir.x, upDir.y, upDir.z);
		putMat4("shadowModelView", shadow.modelView());
		putMat4("shadowModelViewInverse", shadow.modelViewInverse());
		putMat4("shadowProjection", shadow.projection());
		putMat4("shadowProjectionInverse", shadow.projectionInverse());
		// --- Eye, look and body vectors (Iris: playerBodyVector currently reads the same value as playerLookVector).
		float eyeX = (float) (in.eyeX() - origin[0]), eyeY = (float) (in.eyeY() - origin[1]),
				eyeZ = (float) (in.eyeZ() - origin[2]);
		putVec3("eyePosition", eyeX, eyeY, eyeZ);
		putVec3("relativeEyePosition", camX - eyeX, camY - eyeY, camZ - eyeZ);
		putVec3("playerLookVector", look.x, look.y, look.z);
		putVec3("playerBodyVector", look.x, look.y, look.z);
		// --- Player state.
		putInt("isEyeInWater", in.eyeFluid());
		putFloat("nightVision", in.nightVision());
		putFloat("blindness", in.blindness());
		putFloat("darknessFactor", in.darknessFactor());
		putFloat("darknessLightFactor", in.darknessLightFactor());
		float mood = in.playerMood();
		// constantMood is playerMood without its reset at 1.0 (Iris "Player Status": it stays "until the player leaves a cave
		// or otherwise causes the mood value to decrease"): it keeps the mood's running maximum, stands still through the
		// reset (a drop from 0.99 or more: the game adds at most 15/6000 a tick and resets at 1.0) and the climb after it, and
		// follows every other decrease, to zero too: Iris reads 0 where the mood rose to 1/6000 at the world's start and fell
		// back (mcms-uniforms, 2026-11-16), where a rule that held a drop to zero read 1/6000.
		boolean reset = mood < lastMood && lastMood >= 0.99f;
		constantMood = mood >= constantMood ? mood : mood < lastMood && !reset ? mood : constantMood;
		lastMood = mood;
		putFloat("playerMood", mood);
		putFloat("constantMood", constantMood);
		int blockLight = in.eyeBlockLight() * 16, skyLight = in.eyeSkyLight() * 16;
		putInt2("eyeBrightness", blockLight, skyLight);
		eyeSmooth[0] = approach(eyeSmooth[0], blockLight, eyeBrightnessHalflife * TICK_SECONDS, dt, !smoothStarted);
		eyeSmooth[1] = approach(eyeSmooth[1], skyLight, eyeBrightnessHalflife * TICK_SECONDS, dt, !smoothStarted);
		putInt2("eyeBrightnessSmooth", Math.round(eyeSmooth[0]), Math.round(eyeSmooth[1]));
		smoothStarted = true;
		putInt("hideGUI", in.hideGui() ? 1 : 0);
		putInt("heldItemId", in.heldItemId());
		putInt("heldItemId2", in.heldItemId2());
		putInt("heldBlockLightValue", in.heldBlockLightValue());
		putInt("heldBlockLightValue2", in.heldBlockLightValue2());
		boolean noStats = in.creative() || in.spectator();
		putFloat("currentPlayerHealth", noStats ? -1f : in.health() / in.maxHealth());
		putFloat("maxPlayerHealth", noStats ? -1f : in.maxHealth());
		putFloat("currentPlayerArmor", noStats ? -1f : in.armor() / in.maxArmor());
		putFloat("maxPlayerArmor", in.maxArmor());
		putFloat("currentPlayerAir", noStats ? -1f : in.air() / in.maxAir());
		putFloat("maxPlayerAir", noStats ? -1f : in.maxAir());
		putFloat("currentPlayerHunger", noStats ? -1f : in.food() / in.maxFood());
		// maxPlayerHunger has no mode -1: the modes without hunger still report the maximum (Iris "Player Status").
		putFloat("maxPlayerHunger", in.maxFood());
		putBool("isSpectator", in.spectator());
		putBool("isRightHanded", in.rightHanded());
		putBool("firstPersonCamera", in.firstPerson());
		putBool("isRiding", in.riding());
		putBool("inSwimmingAnimation", in.swimming());
		putBool("feetInWater", in.inWaterFeet());
		putBool("isElytraFlying", in.elytraFlying());
		putBool("vehicleInWater", in.vehicleInWater());
		putBool("is_alive", in.alive());
		putBool("is_burning", in.burning());
		putBool("is_child", in.child());
		putBool("is_glowing", in.glowing());
		putBool("is_hurt", in.hurt());
		putBool("is_in_lava", in.inLava());
		putBool("is_in_water", in.inWaterFeet());
		putBool("is_invisible", in.invisible());
		putBool("is_on_ground", in.onGround());
		putBool("is_ridden", in.ridden());
		putBool("is_riding", in.riding());
		putBool("is_sneaking", in.sneaking());
		putBool("is_sprinting", in.sprinting());
		putBool("is_wet", in.wet());
		// --- Lightning (player space: the bolt's offset from the camera; w 1 when a bolt exists) and the End flash.
		if (in.lightning()) {
			vec.set((float) (in.lightningX() - cx), (float) (in.lightningY() - cy), (float) (in.lightningZ() - cz));
			block.putFloat(off("lightningBoltPosition") + 12, 1f);
		} else {
			vec.zero();
			block.putFloat(off("lightningBoltPosition") + 12, 0f);
		}
		putVec3("lightningBoltPosition", vec.x, vec.y, vec.z);
		// The End flash angles are a yaw/pitch pair (xAngle yaw, yAngle pitch), as the camera's are.
		double flashYaw = Math.toRadians(in.endFlashXAngle()), flashPitch = Math.toRadians(in.endFlashYAngle());
		vec.set((float) (-Math.sin(flashYaw) * Math.cos(flashPitch)), (float) -Math.sin(flashPitch),
			(float) (Math.cos(flashYaw) * Math.cos(flashPitch)));
		modelView.transformDirection(vec).mul(100f);
		putVec3("endFlashPosition", vec.x, vec.y, vec.z);
		putFloat("endFlashIntensity", in.endFlashIntensity());
		putFloat("previousEndFlashIntensity", prevEndFlashIntensity);
		prevEndFlashIntensity = in.endFlashIntensity();
		// --- Biome and dimension.
		putInt("biome", in.biomeId());
		putInt("biome_category", in.biomeCategory());
		putInt("biome_precipitation", in.biomePrecipitation());
		putFloat("temperature", in.temperature());
		putFloat("rainfall", in.rainfall());
		putFloat("ambientLight", in.ambientLight());
		putInt("bedrockLevel", in.bedrockLevel());
		putFloat("cloudHeight", in.cloudHeight());
		putBool("hasCeiling", in.hasCeiling());
		putBool("hasSkylight", in.hasSkylight());
		putInt("heightLimit", in.heightLimit());
		putInt("logicalHeightLimit", in.logicalHeightLimit());
	}

	/**
	 * Writes the block's std140 bytes, {@link #size()} of them, little-endian.
	 *
	 * @param dst the destination buffer, positioned at the block's start
	 */
	public void write(ByteBuffer dst) {
		dst.put(block.array(), 0, size());
	}

	/**
	 * Copies the frame's GL-convention projection (the matrix {@code gbufferProjection} holds, built at the last
	 * {@link #update}) into {@code out[0..15]}, column-major, without allocating. The gbuffers delegate serves it as
	 * the game passes' projection.
	 *
	 * @param out the 16 floats the matrix is written to
	 */
	public void gbufferProjection(float[] out) {
		this.projection.get(out);
	}

	/**
	 * Copies the frame's view rotation (the matrix {@code gbufferModelView} holds, captured at the last {@link #update})
	 * into {@code out[0..15]}, column-major, without allocating. The horizon draw (plan.md E4) reads it as its model-view.
	 *
	 * @param out the 16 floats the matrix is written to
	 */
	public void gbufferModelView(float[] out) {
		this.modelView.get(out);
	}

	/** The far plane in blocks the frame's projection was built with (the game's {@code depthFar}). */
	public float farPlane() {
		return this.farPlane;
	}

	/**
	 * Replaces this frame's Distant Horizons values (set by {@link #update} to Iris's values without DH) with DH's own (the
	 * last DH render's), for the custom uniforms. The engine calls it after {@link #update} while DH draws through the pack.
	 */
	public void distant(DistantFrame frame) {
		this.dhNearPlane = frame.nearPlane();
		this.dhFarPlane = frame.farPlane();
		this.dhRenderDistance = frame.renderDistanceBlocks();
		this.dhProjection.set(frame.projection());
		this.dhProjectionInverse.set(frame.projectionInverse());
		this.dhPreviousProjection.set(frame.previousProjection());
	}

	/**
	 * Writes the QsDh block ({@code block}, {@link DistantUniforms#write}) with this frame's Distant Horizons values: the
	 * ones {@link #update} set (Iris's values without DH), or DH's after {@link #distant}. The engine binds it to every
	 * program while DH draws nothing through the pack.
	 */
	public void writeDistant(UniformBlock block, ByteBuffer out) {
		DistantUniforms.write(block, this.dhNearPlane, this.dhFarPlane, this.dhRenderDistance, this.dhProjection,
			this.dhProjectionInverse, this.dhPreviousProjection, out);
	}

	/**
	 * Copies the frame's fog colour (the {@code fogColor} the block holds, the game's {@code FogData.color}) into
	 * {@code out[0..2]} without allocating: the horizon draw's colour (the game clears the main target to it).
	 */
	public void fogColor(float[] out) {
		out[0] = getFloatAt("fogColor", 0);
		out[1] = getFloatAt("fogColor", 4);
		out[2] = getFloatAt("fogColor", 8);
	}

	/**
	 * Writes the expression parameters ({@link FrameLayout#parameters()}) into the frame the pack's custom uniform
	 * expressions read, in slot order, from this frame block's values; a parameter this block does not serve is zeroed.
	 * Allocates nothing. Called before {@code CustomUniforms.update}.
	 *
	 * @param frame  the expression frame to fill
	 * @param layout the pack's frame layout
	 */
	public void fill(ExprFrame frame, FrameLayout layout) {
		for (FrameLayout.Parameter p : layout.parameters()) {
			fillParameter(frame, p);
		}
	}

	private void fillParameter(ExprFrame frame, FrameLayout.Parameter p) {
		int slot = p.slot();
		switch (p.name()) {
			// Floats the block holds.
			case "frameTime" -> frame.setFloat(slot, getFloat("frameTime"));
			case "frameTimeCounter" -> frame.setFloat(slot, getFloat("frameTimeCounter"));
			case "sunAngle" -> frame.setFloat(slot, getFloat("sunAngle"));
			case "shadowAngle" -> frame.setFloat(slot, getFloat("shadowAngle"));
			case "rainStrength" -> frame.setFloat(slot, getFloat("rainStrength"));
			case "wetness" -> frame.setFloat(slot, getFloat("wetness"));
			case "thunderStrength" -> frame.setFloat(slot, getFloat("thunderStrength"));
			case "screenBrightness" -> frame.setFloat(slot, getFloat("screenBrightness"));
			case "aspectRatio" -> frame.setFloat(slot, getFloat("aspectRatio"));
			case "viewWidth" -> frame.setFloat(slot, getFloat("viewWidth"));
			case "viewHeight" -> frame.setFloat(slot, getFloat("viewHeight"));
			case "near" -> frame.setFloat(slot, getFloat("near"));
			case "far" -> frame.setFloat(slot, getFloat("far"));
			case "alphaTestRef" -> frame.setFloat(slot, getFloat("alphaTestRef"));
			case "centerDepthSmooth" -> frame.setFloat(slot, getFloat("centerDepthSmooth"));
			case "eyeAltitude" -> frame.setFloat(slot, getFloat("eyeAltitude"));
			case "playerMood" -> frame.setFloat(slot, getFloat("playerMood"));
			case "darknessFactor" -> frame.setFloat(slot, getFloat("darknessFactor"));
			case "darknessLightFactor" -> frame.setFloat(slot, getFloat("darknessLightFactor"));
			case "nightVision" -> frame.setFloat(slot, getFloat("nightVision"));
			case "blindness" -> frame.setFloat(slot, getFloat("blindness"));
			case "fogStart" -> frame.setFloat(slot, getFloat("fogStart"));
			case "fogEnd" -> frame.setFloat(slot, getFloat("fogEnd"));
			case "fogDensity" -> frame.setFloat(slot, getFloat("fogDensity"));
			case "endFlashIntensity" -> frame.setFloat(slot, getFloat("endFlashIntensity"));
			case "currentPlayerHealth" -> frame.setFloat(slot, getFloat("currentPlayerHealth"));
			case "maxPlayerHealth" -> frame.setFloat(slot, getFloat("maxPlayerHealth"));
			case "currentPlayerArmor" -> frame.setFloat(slot, getFloat("currentPlayerArmor"));
			case "maxPlayerArmor" -> frame.setFloat(slot, getFloat("maxPlayerArmor"));
			case "currentPlayerAir" -> frame.setFloat(slot, getFloat("currentPlayerAir"));
			case "maxPlayerAir" -> frame.setFloat(slot, getFloat("maxPlayerAir"));
			case "currentPlayerHunger" -> frame.setFloat(slot, getFloat("currentPlayerHunger"));
			case "maxPlayerHunger" -> frame.setFloat(slot, getFloat("maxPlayerHunger"));
			case "ambientLight" -> frame.setFloat(slot, getFloat("ambientLight"));
			case "cloudHeight" -> frame.setFloat(slot, getFloat("cloudHeight"));
			// Floats derived from the block's values.
			case "texelSizeX", "pixelSizeX" -> frame.setFloat(slot, 1f / getFloat("viewWidth"));
			case "texelSizeY", "pixelSizeY" -> frame.setFloat(slot, 1f / getFloat("viewHeight"));
			case "temperature" -> frame.setFloat(slot, getFloat("temperature"));
			case "rainfall" -> frame.setFloat(slot, getFloat("rainfall"));
			case "biome" -> frame.setFloat(slot, getInt("biome"));
			case "biome_category" -> frame.setFloat(slot, getInt("biome_category"));
			case "biome_precipitation" -> frame.setFloat(slot, getInt("biome_precipitation"));
			// Ints.
			case "worldTime" -> frame.setInt(slot, getInt("worldTime"));
			case "worldDay" -> frame.setInt(slot, getInt("worldDay"));
			case "moonPhase" -> frame.setInt(slot, getInt("moonPhase"));
			case "frameCounter" -> frame.setInt(slot, getInt("frameCounter"));
			case "heldItemId" -> frame.setInt(slot, getInt("heldItemId"));
			case "heldItemId2" -> frame.setInt(slot, getInt("heldItemId2"));
			case "heldBlockLightValue" -> frame.setInt(slot, getInt("heldBlockLightValue"));
			case "heldBlockLightValue2" -> frame.setInt(slot, getInt("heldBlockLightValue2"));
			case "isEyeInWater" -> frame.setInt(slot, getInt("isEyeInWater"));
			case "hideGUI" -> frame.setInt(slot, getInt("hideGUI"));
			// bossBattle (OptiFine doc: 1 custom, 2 ender dragon, 3 wither, 4 raid): 0 until the frame state
			// tracks boss bars (a later milestone); never wrongly claim a custom boss bar.
			case "bossBattle" -> frame.setInt(slot, 0);
			case "dhRenderDistance" -> frame.setInt(slot, dhRenderDistance);
			case "renderStage", "instanceId", "currentRenderedItemId", "currentSelectedItemId" -> frame.setInt(slot, 0);
			// Bools.
			case "is_alive" -> frame.setBool(slot, getBool("is_alive"));
			case "is_burning" -> frame.setBool(slot, getBool("is_burning"));
			case "is_child" -> frame.setBool(slot, getBool("is_child"));
			case "is_glowing" -> frame.setBool(slot, getBool("is_glowing"));
			case "is_hurt" -> frame.setBool(slot, getBool("is_hurt"));
			case "is_in_lava" -> frame.setBool(slot, getBool("is_in_lava"));
			case "is_in_water" -> frame.setBool(slot, getBool("is_in_water"));
			case "is_invisible" -> frame.setBool(slot, getBool("is_invisible"));
			case "is_on_ground" -> frame.setBool(slot, getBool("is_on_ground"));
			case "is_ridden" -> frame.setBool(slot, getBool("is_ridden"));
			case "is_riding" -> frame.setBool(slot, getBool("is_riding"));
			case "is_sneaking" -> frame.setBool(slot, getBool("is_sneaking"));
			case "is_sprinting" -> frame.setBool(slot, getBool("is_sprinting"));
			case "is_wet" -> frame.setBool(slot, getBool("is_wet"));
			// Vectors.
			case "eyeBrightness" -> frame.setVec2(slot, getInt("eyeBrightness"), block.getInt(off("eyeBrightness") + 4));
			case "eyeBrightnessSmooth" -> frame.setVec2(slot, getInt("eyeBrightnessSmooth"),
				block.getInt(off("eyeBrightnessSmooth") + 4));
			// The per-draw atlasSize is QsDraw's (ProgramRequests); this scope has no draw, so it reads the block atlas.
			case "atlasSize" -> frame.setVec2(slot, blockAtlasWidth, blockAtlasHeight);
			case "cameraPosition" -> frame.setVec3(slot, getFloatAt("cameraPosition", 0),
				getFloatAt("cameraPosition", 4), getFloatAt("cameraPosition", 8));
			case "previousCameraPosition" -> frame.setVec3(slot, getFloatAt("previousCameraPosition", 0),
				getFloatAt("previousCameraPosition", 4), getFloatAt("previousCameraPosition", 8));
			case "sunPosition" -> frame.setVec3(slot, getFloatAt("sunPosition", 0), getFloatAt("sunPosition", 4),
				getFloatAt("sunPosition", 8));
			case "moonPosition" -> frame.setVec3(slot, getFloatAt("moonPosition", 0), getFloatAt("moonPosition", 4),
				getFloatAt("moonPosition", 8));
			case "shadowLightPosition" -> frame.setVec3(slot, getFloatAt("shadowLightPosition", 0),
				getFloatAt("shadowLightPosition", 4), getFloatAt("shadowLightPosition", 8));
			case "upPosition" -> frame.setVec3(slot, getFloatAt("upPosition", 0), getFloatAt("upPosition", 4),
				getFloatAt("upPosition", 8));
			case "skyColor" -> frame.setVec3(slot, getFloatAt("skyColor", 0), getFloatAt("skyColor", 4),
				getFloatAt("skyColor", 8));
			case "relativeEyePosition" -> frame.setVec3(slot, getFloatAt("relativeEyePosition", 0),
				getFloatAt("relativeEyePosition", 4), getFloatAt("relativeEyePosition", 8));
			case "eyePosition" -> frame.setVec3(slot, getFloatAt("eyePosition", 0), getFloatAt("eyePosition", 4),
				getFloatAt("eyePosition", 8));
			case "playerBodyVector" -> frame.setVec3(slot, getFloatAt("playerBodyVector", 0),
				getFloatAt("playerBodyVector", 4), getFloatAt("playerBodyVector", 8));
			case "playerLookVector" -> frame.setVec3(slot, getFloatAt("playerLookVector", 0),
				getFloatAt("playerLookVector", 4), getFloatAt("playerLookVector", 8));
			case "modelOffset" -> frame.setVec3(slot, 0f, 0f, 0f);
			case "vehicleLookVector" -> frame.setVec3(slot, 0f, 0f, 0f);
			case "spriteBounds" -> frame.setVec4(slot, 0f, 0f, 0f, 0f);
			case "colorModulator" -> frame.setVec4(slot, 0f, 0f, 0f, 0f);
			case "lightningBoltPosition" -> frame.setVec4(slot, getFloatAt("lightningBoltPosition", 0),
				getFloatAt("lightningBoltPosition", 4), getFloatAt("lightningBoltPosition", 8),
				getFloatAt("lightningBoltPosition", 12));
			case "blendFunc" -> frame.setVec4(slot, 1f, 0f, 1f, 0f);
			// Matrices (M is column-major in the expression frame). The shadow matrices are the frame's shadow camera's
			// (Bliss 2.1.2 builds shadowViewDir, shadowCamera and shadowLightVec from shadowModelView); the per-draw
			// matrices have no frame value and read zero.
			case "gbufferModelView" -> frame.setMat4(slot, matrix("gbufferModelView", modelView));
			case "gbufferModelViewInverse" -> frame.setMat4(slot, matrix("gbufferModelViewInverse",
				inverse.set(modelView).invert()));
			case "gbufferProjection" -> frame.setMat4(slot, matrix("gbufferProjection", projection));
			case "gbufferProjectionInverse" -> frame.setMat4(slot, matrix("gbufferProjectionInverse",
				inverse.set(projection).invert()));
			case "gbufferPreviousModelView" -> frame.setMat4(slot, matrix("gbufferPreviousModelView", prevModelView));
			case "gbufferPreviousProjection" -> frame.setMat4(slot, matrix("gbufferPreviousProjection", prevProjection));
			case "shadowModelView" -> frame.setMat4(slot, matrix("shadowModelView", shadow.modelView()));
			case "shadowModelViewInverse" -> frame.setMat4(slot, matrix("shadowModelViewInverse", shadow.modelViewInverse()));
			case "shadowProjection" -> frame.setMat4(slot, matrix("shadowProjection", shadow.projection()));
			case "shadowProjectionInverse" -> frame.setMat4(slot, matrix("shadowProjectionInverse", shadow.projectionInverse()));
			case "dhProjection" -> frame.setMat4(slot, matrix("dhProjection", dhProjection));
			case "dhProjectionInverse" -> frame.setMat4(slot, matrix("dhProjectionInverse", dhProjectionInverse));
			case "dhPreviousProjection" -> frame.setMat4(slot, matrix("dhPreviousProjection", dhPreviousProjection));
			case "modelViewMatrix", "modelViewMatrixInverse", "projectionMatrix", "projectionMatrixInverse",
					"textureMatrix" -> {
				java.util.Arrays.fill(mat, 0f);
				frame.setMat4(slot, mat);
			}
			case "normalMatrix" -> {
				java.util.Arrays.fill(mat, 0f);
				frame.setMat3(slot, mat);
			}
			default -> zero(frame, slot, p.type());
		}
	}

	private static void zero(ExprFrame frame, int slot, dev.mcms.shaders.expr.ExprType type) {
		switch (type) {
			case FLOAT -> frame.setFloat(slot, 0f);
			case INT -> frame.setInt(slot, 0);
			case BOOL -> frame.setBool(slot, false);
			case VEC2 -> frame.setVec2(slot, 0f, 0f);
			case VEC3 -> frame.setVec3(slot, 0f, 0f, 0f);
			case MAT4 -> frame.setMat4(slot, ZERO16);
			case MAT3 -> frame.setMat3(slot, ZERO9);
		}
	}

	private float[] matrix(String name, Matrix4f m) {
		m.get(mat);
		return mat;
	}

	/**
	 * Sets the smoothing half-lives from the pack's const values (in ticks; {@code OptionValues} holds the loaded
	 * value, the declared value is the default). The defaults are the documented ones: wetness 600, dryness 200,
	 * eyeBrightness 10, centerDepth 1 (OptiFine doc, "Fragment Shader Configuration").
	 *
	 * @param wetnessTicks        the pack's {@code wetnessHalflife}
	 * @param drynessTicks        the pack's {@code drynessHalflife}
	 * @param eyeBrightnessTicks  the pack's {@code eyeBrightnessHalflife}
	 * @param centerDepthTicks    the pack's {@code centerDepthHalflife}
	 */
	public void setHalflives(float wetnessTicks, float drynessTicks, float eyeBrightnessTicks, float centerDepthTicks) {
		this.wetnessHalflife = wetnessTicks;
		this.drynessHalflife = drynessTicks;
		this.eyeBrightnessHalflife = eyeBrightnessTicks;
		this.centerDepthHalflife = centerDepthTicks;
	}

	/**
	 * Sets the centre depth (the depth buffer's value at the screen centre, OpenGL's far = 1 convention) that
	 * {@code centerDepthSmooth} approaches. Until E4 reads the depth target it stays 1.0.
	 *
	 * @param depth the centre depth, 0 to 1
	 */
	public void setCenterDepth(float depth) {
		this.centerDepth = depth;
	}

	/** ADR 0008: the LOD bias of the render scale, log2(1 / scale) (0 at the window size); the next update writes it. */
	public void setLodBias(float bias) {
		this.lodBias = bias;
	}

	/**
	 * Sets the alpha-test reference for the program about to run (the {@code alphaTest.<program>} directive's value, or
	 * the default 0.1). E3-A calls this per pass; the field stays in this block so the translator finds one name.
	 *
	 * @param ref the reference value
	 */
	public void setAlphaTestRef(float ref) {
		this.alphaTestRef = ref;
	}

	/**
	 * Sets the block atlas's size in pixels, read by the custom uniforms' {@code atlasSize} (the frame has no draw, so it reads
	 * the terrain's atlas, the atlas the world's geometry binds). The engine sets it each frame after {@link #update}, from the
	 * table its atlases refresh filled (Engine.refreshAtlases).
	 *
	 * @param width  the block atlas's width
	 * @param height the block atlas's height
	 */
	public void setBlockAtlas(int width, int height) {
		this.blockAtlasWidth = width;
		this.blockAtlasHeight = height;
	}

	/**
	 * Sets the pack's shadow constants for the set's shadow camera (E5; set install, not per frame). The camera's
	 * settings come from {@link dev.mcms.shaders.runtime.shadow.ShadowCamera.Settings#of}.
	 *
	 * @param settings the shadow camera's settings
	 */
	public void setShadowSettings(ShadowCamera.Settings settings) {
		this.shadow.configure(settings);
	}

	/** The shadow camera whose matrices the block holds this frame (the shadow pass binds them per pass). */
	public ShadowCamera shadow() {
		return this.shadow;
	}

	/** The pack const {@code sunPathRotation} in degrees (Iris "sunPathRotation"); 0 keeps the vanilla path. */
	private float sunPathRotation;

	/**
	 * Sets the pack const {@code sunPathRotation} (degrees), applied to the sun, moon and shadow-light positions
	 * (the const rotates the celestial path perpendicular to the direction of travel; the default 0 crosses the top
	 * of the sky dome). A positive value tilts the noon sun toward -Z (measured in E7, see {@code update}).
	 * E3-A passes the loaded const's value.
	 *
	 * @param degrees the rotation in degrees
	 */
	public void setSunPathRotation(float degrees) {
		this.sunPathRotation = degrees;
	}

	/** The pack const {@code sunPathRotation} in degrees that the celestial positions use (0 without a set). */
	public float sunPathRotation() {
		return this.sunPathRotation;
	}

	/** The real frame time in seconds for {@code CustomUniforms}' expression clock ({@code smooth}'s delta). */
	public float frameTime() {
		return lastFrameTime;
	}

	/** The engine's clock in seconds ({@code frameTimeCounter}'s value), for {@code CustomUniforms}' expression clock. */
	public float clockSeconds() {
		return clock;
	}

	/**
	 * Starts the clock and the frame counter again: at a dimension change (plan.md E7), and when a set is installed
	 * ({@code Engine.install}, which a set that lands after a background load goes through), so the next frame's {@code frameTimeCounter}
	 * is that frame's time and its {@code frameCounter} 0. Iris starts both again in each new dimension and when its pipeline starts a set
	 * (measured on 2026-11-16 with an instrumented copy of Bliss in the Overworld, the Nether and the End: Iris 41.9, 37.4 and 35.6 s, the
	 * engine before this 45.5, 86.4 and 127.4 s). A held clock ({@code mcms.shaders.holdClock}) still wins.
	 */
	public void restartClock() {
		clock = 0f;
		frameCounter = -1;
	}

	/**
	 * The value of {@code mcms.shaders.holdClock} in seconds, or -1 when absent or not a number. Read every frame,
	 * but parsed only when the property's text changed. When a hold applies, the engine marks itself as the clock's
	 * reader through {@code mcms.shaders.holdClockRead} so the bench harness can record it in meta.json.
	 */
	private static String holdClockText;
	private static float holdClockValue = -1f;

	private static float holdClock() {
		String v = System.getProperty("mcms.shaders.holdClock");
		if (v == null) {
			holdClockText = null;
			return -1f;
		}
		if (!v.equals(holdClockText)) {
			try {
				holdClockValue = Float.parseFloat(v.trim());
			} catch (NumberFormatException ex) {
				holdClockValue = -1f;
			}
			holdClockText = v;
		}
		if (holdClockValue >= 0f) {
			System.setProperty("mcms.shaders.holdClockRead", "true");
		}
		return holdClockValue;
	}

	private static float frac(float v) {
		return v - (float) Math.floor(v);
	}

	private static float fract(double v) {
		return (float) (v - Math.floor(v));
	}

	/**
	 * Exponential approach: after {@code halflife} seconds half the remaining distance remains; the first frame snaps
	 * to the target. The same rule {@code smooth()} in the expression language uses.
	 */
	private static float approach(float current, float target, float halflifeSeconds, float dt, boolean first) {
		if (first || halflifeSeconds <= 0f || dt <= 0f || target == current) {
			return target;
		}
		return target + (current - target) * (float) Math.exp(-Math.log(2) * dt / halflifeSeconds);
	}

	private int off(String field) {
		return OFFSETS.get(field);
	}

	private void putFloat(String field, float v) {
		block.putFloat(off(field), v);
	}

	private void putInt(String field, int v) {
		block.putInt(off(field), v);
	}

	private void putBool(String field, boolean v) {
		block.putInt(off(field), v ? 1 : 0);
	}

	private void putVec3(String field, float x, float y, float z) {
		int o = off(field);
		block.putFloat(o, x).putFloat(o + 4, y).putFloat(o + 8, z);
	}

	private void putInt2(String field, int x, int y) {
		int o = off(field);
		block.putInt(o, x).putInt(o + 4, y);
	}

	private void putInt3(String field, int x, int y, int z) {
		int o = off(field);
		block.putInt(o, x).putInt(o + 4, y).putInt(o + 8, z);
	}

	private void putMat4(String field, Matrix4f m) {
		m.get(mat, 0);
		int o = off(field);
		for (int i = 0; i < 16; i++) {
			block.putFloat(o + 4 * i, mat[i]);
		}
	}

	private float getFloat(String field) {
		return block.getFloat(off(field));
	}

	private float getFloatAt(String field, int byteOffset) {
		return block.getFloat(off(field) + byteOffset);
	}

	private int getInt(String field) {
		return block.getInt(off(field));
	}

	private boolean getBool(String field) {
		return block.getInt(off(field)) != 0;
	}

	private static Map<String, Integer> offsets() {
		Map<String, Integer> m = new HashMap<>();
		for (UniformField f : schema(0).fields()) {
			m.put(f.name(), f.offset());
		}
		return java.util.Collections.unmodifiableMap(m);
	}
}
