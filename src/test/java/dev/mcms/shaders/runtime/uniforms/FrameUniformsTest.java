// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.uniforms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
/**
 * The frame block's computations from synthetic {@link FrameInputs}: the GL-convention matrices against hand-computed
 * values, the previous-frame values, the sun and moon positions, the smoothed values' half-lives with the documented
 * defaults and the pack overrides, the clock (wrap and the {@code mcms.shaders.holdClock} hold), the camera wrap, the
 * fog merge, and the std140 bytes {@code write} produces. Values are read back through {@link FrameUniforms#schema}
 * offsets, so a wrong offset fails the same way a wrong value does.
 */
class FrameUniformsTest {
	private static final double TOL = 1e-4;

	/** A {@link FrameInputs} with known values; every test changes what it needs and builds. */
	static final class TestInputs {
		int width = 1920, height = 1080;
		float partialTick;
		double cameraX = 100.5, cameraY = 64.25, cameraZ = 200.75;
		double eyeX = 100.5, eyeY = 65.25, eyeZ = 200.75;
		float yaw, pitch;
		float lookX, lookY = 0f, lookZ = 1f;
		org.joml.Matrix4f viewRotation = new org.joml.Matrix4f();
		float fovY = 90f;
		float farPlane = 1000f;
		int renderDistanceChunks = 12;
		long dayTime = 6000;
		float sunAngleDegrees; // the game's sun angle in degrees: 0 at noon, the dayTime 6000 default
		int moonPhaseOrdinal = 3;
		float rainLevel, thunderLevel;
		float skyR = 0.4f, skyG = 0.6f, skyB = 1f;
		float fogR = 0.7f, fogG = 0.8f, fogB = 0.9f;
		float fogEnvironmentalStart = 10f, fogEnvironmentalEnd = 100f;
		float fogRenderDistanceStart = 90f, fogRenderDistanceEnd = 192f;
		int eyeFluid;
		int eyeBlockLight, eyeSkyLight = 12;
		float screenBrightness = 0.5f;
		boolean hideGui, firstPerson = true, spectator, rightHanded = true, creative;
		float nightVision, blindness, darknessFactor, darknessLightFactor;
		float playerMood;
		int heldBlockLightValue, heldBlockLightValue2, heldItemId, heldItemId2;
		boolean sneaking, sprinting, onGround = true, swimming, inWaterFeet, inLava;
		boolean wet, burning, hurt, invisible, glowing, alive = true, child;
		boolean ridden, riding, elytraFlying, vehicleInWater;
		float health = 20f, maxHealth = 20f, armor = 10f, maxArmor = 50f;
		float air = 300f, maxAir = 300f, food = 20f, maxFood = 20f;
		int biomeId = 2, biomeCategory = 5, biomePrecipitation;
		float temperature = 0.7f, rainfall;
		float ambientLight, cloudHeight = 192f;
		int bedrockLevel = -64, heightLimit = 384, logicalHeightLimit = 384;
		boolean hasCeiling, hasSkylight = true;
		float endFlashIntensity, endFlashXAngle, endFlashYAngle;
		boolean lightning;
		double lightningX, lightningY = 64, lightningZ = 210;
		int dateYear = 2026, dateMonth = 11, dateDay = 13, timeHour = 12, timeMinute = 30, timeSecond = 15;
		int yearSecondIn = 1000, yearSecondLeft = 2000;

		FrameInputs build() {
			return new FrameInputs(width, height, partialTick, cameraX, cameraY, cameraZ, eyeX, eyeY, eyeZ, yaw, pitch,
				lookX, lookY, lookZ, viewRotation, fovY, farPlane, renderDistanceChunks, dayTime, sunAngleDegrees, moonPhaseOrdinal, rainLevel,
				thunderLevel, skyR, skyG, skyB, fogR, fogG, fogB, fogEnvironmentalStart, fogEnvironmentalEnd,
				fogRenderDistanceStart, fogRenderDistanceEnd, eyeFluid, eyeBlockLight, eyeSkyLight, screenBrightness,
				hideGui, firstPerson, spectator, rightHanded, creative, nightVision, blindness, darknessFactor,
				darknessLightFactor, playerMood, heldBlockLightValue, heldBlockLightValue2, heldItemId, heldItemId2,
				sneaking, sprinting, onGround, swimming, inWaterFeet, inLava, wet, burning, hurt, invisible, glowing,
				alive, child, ridden, riding, elytraFlying, vehicleInWater, health, maxHealth, armor, maxArmor, air,
				maxAir, food, maxFood, biomeId, biomeCategory, biomePrecipitation, temperature, rainfall, ambientLight,
				cloudHeight, bedrockLevel, heightLimit, logicalHeightLimit, hasCeiling, hasSkylight, endFlashIntensity,
				endFlashXAngle, endFlashYAngle, lightning, lightningX, lightningY, lightningZ, dateYear, dateMonth,
				dateDay, timeHour, timeMinute, timeSecond, yearSecondIn, yearSecondLeft);
		}
	}

	private static final Map<String, Integer> OFFSETS = offsets();

	private static Map<String, Integer> offsets() {
		Map<String, Integer> m = new HashMap<>();
		for (var f : FrameUniforms.schema(0).fields()) {
			m.put(f.name(), f.offset());
		}
		return m;
	}

	private static float floatAt(ByteBuffer b, String field) {
		return b.getFloat(OFFSETS.get(field));
	}

	private static int intAt(ByteBuffer b, String field) {
		return b.getInt(OFFSETS.get(field));
	}

	private static boolean boolAt(ByteBuffer b, String field) {
		return b.getInt(OFFSETS.get(field)) != 0;
	}

	private static float[] vecAt(ByteBuffer b, String field, int n) {
		int o = OFFSETS.get(field);
		float[] out = new float[n];
		for (int i = 0; i < n; i++) {
			out[i] = b.getFloat(o + 4 * i);
		}
		return out;
	}

	private static Matrix4f matAt(ByteBuffer b, String field) {
		int o = OFFSETS.get(field);
		float[] m = new float[16];
		b.position(o);
		for (int i = 0; i < 16; i++) {
			m[i] = b.getFloat();
		}
		return new Matrix4f(m);
	}

	private static ByteBuffer block(FrameUniforms u) {
		ByteBuffer b = ByteBuffer.allocate(FrameUniforms.size()).order(ByteOrder.LITTLE_ENDIAN);
		u.write(b);
		b.flip();
		return b;
	}

	/** One frame without the clock hold; the package-private {@code advance} gives the test its frame time. */
	private static void frame(FrameUniforms u, TestInputs in, float dt) {
		u.advance(in.build(), dt, -1f);
	}

	/** The GL projection for a 90-degree vertical FOV, aspect 2, near 0.05, far 1000, element by element. */
	@Test
	void projectionIsHandComputedatedOpenGlConvention() {
		FrameUniforms u = new FrameUniforms();
		frame(u, new TestInputs(), 0.016f);
		Matrix4f p = matAt(block(u), "gbufferProjection");
		// f = 1 / tan(45 degrees) = 1; column-major: m00 = f / aspect, m11 = f, m22 = (far + near) / (near - far),
		// m23 = 2 * far * near / (near - far), m32 = -1.
		assertEquals(0.5625f, p.m00(), TOL);
		assertEquals(1f, p.m11(), TOL);
		assertEquals(1000.05f / -999.95f, p.m22(), TOL);
		assertEquals(-1f, p.m23(), TOL);
		assertEquals(100f / -999.95f, p.m32(), TOL);
		assertEquals(0f, p.m30(), TOL);
		// Near and far land at OpenGL's -1 and 1 (clip z over clip w, row 2 and row 3 of the matrix).
		assertEquals(-1f, (p.m22() * -0.05f + p.m32()) / (p.m23() * -0.05f + p.m33()), TOL);
		assertEquals(1f, (p.m22() * -1000f + p.m32()) / (p.m23() * -1000f + p.m33()), TOL);
	}

	/** Three frames of the same state give the same matrices: nothing accumulates into the JOML matrices per frame. */
	@Test
	void projectionAndModelViewDoNotAccumulateAcrossFrames() {
		FrameUniforms u = new FrameUniforms();
		TestInputs in = new TestInputs();
		frame(u, in, 0.016f);
		float m00 = matAt(block(u), "gbufferProjection").m00();
		Matrix4f mv = matAt(block(u), "gbufferModelView");
		assertEquals(0.5625f, m00, TOL);
		frame(u, in, 0.016f);
		assertEquals(m00, matAt(block(u), "gbufferProjection").m00(), TOL);
		assertTrue(mv.equals(matAt(block(u), "gbufferModelView"), 1e-5f));
		frame(u, in, 0.016f);
		assertEquals(m00, matAt(block(u), "gbufferProjection").m00(), TOL);
		assertTrue(mv.equals(matAt(block(u), "gbufferModelView"), 1e-5f));
	}

	/**
	 * gbufferModelView is the camera's view rotation captured for the frame (Iris "Matrices": player space to view
	 * space), so it carries no camera translation and a straight-down look stays finite.
	 */
	@Test
	void modelViewIsTheCapturedViewRotationWithoutTranslation() {
		FrameUniforms u = new FrameUniforms();
		TestInputs in = new TestInputs();
		in.viewRotation = new Matrix4f().rotateY((float) Math.toRadians(30f));
		frame(u, in, 0.016f);
		Matrix4f m = matAt(block(u), "gbufferModelView");
		assertTrue(in.viewRotation.equals(m, 1e-5f));
		// No camera translation: the player-space origin stays the view-space origin, a player point only rotates.
		assertEquals(0f, m.m30(), TOL);
		assertEquals(0f, m.m31(), TOL);
		assertEquals(0f, m.m32(), TOL);
		Vector3f player = m.transformPosition(new Vector3f(3f, -2f, 5f));
		assertEquals(in.viewRotation.transformPosition(new Vector3f(3f, -2f, 5f)), player);
		// A vertical look (a rotation matrix, not a lookAt with the up axis) keeps every celestial value finite.
		in.viewRotation = new Matrix4f().rotateX((float) Math.toRadians(90f));
		in.lookX = 0f;
		in.lookY = -1f;
		in.lookZ = 0f;
		for (int i = 0; i < 3; i++) {
			frame(u, in, 0.016f);
		}
		ByteBuffer b = block(u);
		for (String f : new String[] { "sunPosition", "moonPosition", "shadowLightPosition", "upPosition" }) {
			for (float v : vecAt(b, f, 3)) {
				assertTrue(Float.isFinite(v), f);
			}
		}
	}

	/** The inverses are the matrices' inverses. */
	@Test
	void matricesHaveInverses() {
		FrameUniforms u = new FrameUniforms();
		frame(u, new TestInputs(), 0.016f);
		ByteBuffer b = block(u);
		Matrix4f p = matAt(b, "gbufferProjection"), pInv = matAt(b, "gbufferProjectionInverse");
		Matrix4f m = matAt(b, "gbufferModelView"), mInv = matAt(b, "gbufferModelViewInverse");
		assertTrue(new Matrix4f(p).mul(pInv).equals(new Matrix4f(), 1e-3f));
		assertTrue(new Matrix4f(m).mul(mInv).equals(new Matrix4f(), 1e-3f));
	}

	/** The second frame's previous values are the first frame's, and the first frame's previous are its own. */
	@Test
	void previousValuesAreThePreviousFrames() {
		FrameUniforms u = new FrameUniforms();
		TestInputs first = new TestInputs();
		frame(u, first, 0.016f);
		ByteBuffer b1 = block(u);
		Matrix4f mv1 = matAt(b1, "gbufferModelView");
		Matrix4f p1 = matAt(b1, "gbufferProjection");
		assertEquals(mv1, matAt(b1, "gbufferPreviousModelView"));
		assertEquals(p1, matAt(b1, "gbufferPreviousProjection"));
		assertArrayEquals(vecAt(b1, "cameraPosition", 3), vecAt(b1, "previousCameraPosition", 3));
		TestInputs second = new TestInputs();
		second.cameraX = 110.5;
		second.lookX = 1f;
		second.lookZ = 0f;
		second.fovY = 70f;
		frame(u, second, 0.016f);
		ByteBuffer b2 = block(u);
		assertEquals(mv1, matAt(b2, "gbufferPreviousModelView"));
		assertEquals(p1, matAt(b2, "gbufferPreviousProjection"));
		// cameraPosition is the in-game coordinate (wrapped only past 30000 blocks or a 1000-block teleport): a
		// 10-block walk keeps the raw coordinates, and the previous value is the first frame's uniform value.
		assertArrayEquals(new float[] { 100.5f, 64.25f, 200.75f }, vecAt(b2, "previousCameraPosition", 3));
		assertArrayEquals(new float[] { 110.5f, 64.25f, 200.75f }, vecAt(b2, "cameraPosition", 3));
	}

	/** Noon and midnight from the level time; the sun rises east and stands overhead at noon, in view space. */
	@Test
	void sunAngleAndCelestialPositionsFollowTheClock() {
		FrameUniforms u = new FrameUniforms();
		TestInputs in = new TestInputs();
		frame(u, in, 0.016f);
		ByteBuffer b = block(u);
		// dayTime 6000 is noon: sunAngle 0.25, worldTime 6000, worldDay 0, the sun overhead.
		assertEquals(0.25f, floatAt(b, "sunAngle"), TOL);
		assertEquals(6000, intAt(b, "worldTime"));
		assertEquals(0, intAt(b, "worldDay"));
		assertEquals(3, intAt(b, "moonPhase"));
		assertArrayEquals(new float[] { 0f, 100f, 0f }, vecAt(b, "sunPosition", 3));
		assertArrayEquals(new float[] { 0f, -100f, 0f }, vecAt(b, "moonPosition", 3));
		assertArrayEquals(new float[] { 0f, 100f, 0f }, vecAt(b, "shadowLightPosition", 3));
		assertArrayEquals(new float[] { 0f, 100f, 0f }, vecAt(b, "upPosition", 3));
		assertEquals(0.25f, floatAt(b, "shadowAngle"), TOL);
		in.dayTime = 18000;
		in.sunAngleDegrees = 180f;
		frame(u, in, 0.016f);
		b = block(u);
		assertEquals(0.75f, floatAt(b, "sunAngle"), TOL);
		assertEquals(0.25f, floatAt(b, "shadowAngle"), TOL);
		// Midnight: the sun is down, the moon overhead, and the shadow light is the moon.
		assertArrayEquals(new float[] { 0f, -100f, 0f }, vecAt(b, "sunPosition", 3));
		assertArrayEquals(new float[] { 0f, 100f, 0f }, vecAt(b, "moonPosition", 3));
		assertArrayEquals(new float[] { 0f, 100f, 0f }, vecAt(b, "shadowLightPosition", 3));
		assertEquals(18000, intAt(b, "worldTime"));
		// Two days in: worldDay counts the whole days of the clock.
		in.dayTime = 48000;
		in.sunAngleDegrees = 282.37f;
		frame(u, in, 0.016f);
		assertEquals(2, intAt(block(u), "worldDay"));
		assertEquals(0, intAt(block(u), "worldTime"));
	}

	/**
	 * The sun comes from the game's sun angle in degrees, not from the clock: the clock reads noon in every case here and
	 * only sunAngleDegrees changes. At d degrees the sun sits at (-100 sin d, 100 cos d, 0) in view space.
	 */
	@Test
	void theGameSunAngleSetsTheSunMoonAndShadowLight() {
		// 0 degrees: the overhead noon sun; the sun is the shadow light and shadowAngle is its sunAngle, 0.25.
		assertCelestial(0f, true, 0.25f);
		// 90 degrees: sunAngle is exactly 0.5, which is not below 0.5, so the moon is the light and shadowAngle folds to 0.
		assertCelestial(90f, false, 0f);
		// 270 degrees: sunAngle wraps to 0, the sun on the +X horizon, so the sun is the light again.
		assertCelestial(270f, true, 0f);
		// 352.5 degrees: sunAngle is (352.5 + 90) / 360 - 1 with the sun still up, so shadowAngle is that value.
		assertCelestial(352.5f, true, (352.5f + 90f) / 360f - 1f);
	}

	/** The frame with the clock at noon and the game's sun angle set to degrees; checks the sun, moon and light. */
	private static void assertCelestial(float degrees, boolean sunIsLight, float shadowAngle) {
		FrameUniforms u = new FrameUniforms();
		TestInputs in = new TestInputs();
		in.sunAngleDegrees = degrees;
		frame(u, in, 0.016f);
		ByteBuffer b = block(u);
		double rad = Math.toRadians(degrees);
		float[] sun = {(float) (-100 * Math.sin(rad)), (float) (100 * Math.cos(rad)), 0f};
		float[] moon = {-sun[0], -sun[1], -sun[2]};
		assertArrayEquals(sun, vecAt(b, "sunPosition", 3));
		assertArrayEquals(moon, vecAt(b, "moonPosition", 3));
		assertArrayEquals(sunIsLight ? sun : moon, vecAt(b, "shadowLightPosition", 3));
		assertEquals(shadowAngle, floatAt(b, "shadowAngle"), TOL);
	}

	/**
	 * The game's sky draws a body at its local +Y through the pose Ry(-90) Rz(p) Rx(a) (SkyRenderer.renderSunMoonAndStars,
	 * with EngineCelestialTiltMixin's Rz(p) for the pack's sunPathRotation p): with an identity view, that direction must be
	 * the sunPosition the engine hands the pack, for any tilt and angle, or the vanilla sun and the pack's sun part ways.
	 */
	@Test
	void theTiltedSkyPoseCarriesTheSunToSunPosition() {
		for (float tilt : new float[] { -40f, 30f }) {
			for (float degrees : new float[] { 20f, 75f, 300f }) {
				FrameUniforms u = new FrameUniforms();
				TestInputs in = new TestInputs();
				u.setSunPathRotation(tilt);
				in.sunAngleDegrees = degrees;
				frame(u, in, 0.016f);
				org.joml.Vector3f sky = new org.joml.Matrix4f().rotateY((float) Math.toRadians(-90))
					.rotateZ((float) Math.toRadians(tilt)).rotateX((float) Math.toRadians(degrees))
					.transformDirection(new org.joml.Vector3f(0f, 1f, 0f)).mul(100f);
				assertArrayEquals(new float[] { sky.x, sky.y, sky.z }, vecAt(block(u), "sunPosition", 3));
			}
		}
	}

	/** The smoothed values follow their half-lives: half the remaining distance per half-life, per direction. */
	@Test
	void smoothingFollowsTheDocumentedHalfLives() {
		FrameUniforms u = new FrameUniforms();
		TestInputs in = new TestInputs();
		in.rainLevel = 0f;
		frame(u, in, 0f);
		assertEquals(0f, floatAt(block(u), "wetness"));
		// Defaults (Iris constants pages): drynessHalflife 200 ticks (10 s) governs dry-to-wet, wetnessHalflife
		// 600 ticks (30 s) governs wet-to-dry.
		in.rainLevel = 1f;
		frame(u, in, 10f);
		assertEquals(0.5f, floatAt(block(u), "wetness"), TOL);
		frame(u, in, 10f);
		assertEquals(0.75f, floatAt(block(u), "wetness"), TOL);
		in.rainLevel = 0f;
		frame(u, in, 30f);
		assertEquals(0.375f, floatAt(block(u), "wetness"), TOL);
		// The pack overrides (in ticks) reach the same maths.
		u.setHalflives(20f, 20f, 10f, 1f);
		frame(u, in, 1f);
		assertEquals(0.1875f, floatAt(block(u), "wetness"), TOL);
	}

	/** eyeBrightness is the lightmap value (0-15 scaled to 0-240); its smooth halves per 10 ticks by default. */
	@Test
	void eyeBrightnessPacksAndSmooths() {
		FrameUniforms u = new FrameUniforms();
		TestInputs in = new TestInputs();
		in.eyeBlockLight = 0;
		in.eyeSkyLight = 0;
		frame(u, in, 0f);
		ByteBuffer b = block(u);
		assertEquals(0, intAt(b, "eyeBrightness"));
		assertEquals(0, intAt(b, "eyeBrightnessSmooth"));
		in.eyeBlockLight = 15;
		in.eyeSkyLight = 0;
		frame(u, in, 0.5f);
		b = block(u);
		// Block light 15 reads 240; the smooth covers half the distance in one 10-tick half-life (0.5 s).
		assertEquals(240, b.getInt(OFFSETS.get("eyeBrightness")));
		assertEquals(0, b.getInt(OFFSETS.get("eyeBrightness") + 4));
		assertEquals(120, intAt(b, "eyeBrightnessSmooth"));
	}
	/** The clock wraps at 3600 s, the frame counter at 720720, and the hold holds only the clock. */
	@Test
	void clockWrapsAndTheHoldFreezesIt() {
		FrameUniforms u = new FrameUniforms();
		TestInputs in = new TestInputs();
		frame(u, in, 3599.9f);
		assertEquals(3599.9f, u.clockSeconds(), TOL);
		frame(u, in, 0.2f);
		assertEquals(0.1f, u.clockSeconds(), 2e-3);
		assertEquals(0.2f, u.frameTime(), TOL);
		for (int i = 2; i < 720720; i++) {
			frame(u, in, 0f);
		}
		assertEquals(720719 - 1 + 1, intAt(block(u), "frameCounter"));
		frame(u, in, 0f);
		assertEquals(0, intAt(block(u), "frameCounter"));
		try {
			System.setProperty("mcms.shaders.holdClock", "123.5");
			FrameUniforms held = new FrameUniforms();
			TestInputs rain = new TestInputs();
			rain.rainLevel = 1f;
			held.advance(rain.build(), 0f, -1f);
			assertEquals(1f, floatAt(block(held), "wetness"));
			held.update(rain.build());
			assertEquals(123.5f, held.clockSeconds(), TOL);
			assertEquals(123.5f, floatAt(block(held), "frameTimeCounter"), TOL);
			// The hold holds only the clock: the frame time (and with it the smoothing and the expression smooth())
			// keeps the real dt, as the harness holds Iris's and Vitrail's clocks.
			held.advance(rain.build(), 5f, 123.5f);
			assertEquals(123.5f, held.clockSeconds(), TOL);
			assertEquals(5f, held.frameTime(), TOL);
		} finally {
			System.clearProperty("mcms.shaders.holdClock");
			System.clearProperty("mcms.shaders.holdClockRead");
		}
		// The hold marks the engine as the clock's reader for the harness; the mark must not leak to other tests.
		assertNull(System.getProperty("mcms.shaders.holdClockRead"));
	}

	/** A dimension change starts the clock and the frame counter again (as Iris does); a held clock still wins. */
	@Test
	void restartStartsTheClockAndTheCounterAgain() {
		FrameUniforms u = new FrameUniforms();
		TestInputs in = new TestInputs();
		for (int i = 0; i < 50; i++) {
			frame(u, in, 0.5f);
		}
		assertEquals(25f, u.clockSeconds(), TOL);
		assertEquals(49, intAt(block(u), "frameCounter"));
		u.restartClock();
		frame(u, in, 0.25f);
		assertEquals(0.25f, floatAt(block(u), "frameTimeCounter"), TOL);
		assertEquals(0, intAt(block(u), "frameCounter"));
		u.restartClock();
		u.advance(in.build(), 0.25f, 100f);
		assertEquals(100f, floatAt(block(u), "frameTimeCounter"), TOL);
	}

	/** The camera origin re-anchors past 30000 blocks and on a 1000-block jump; Fract/Int keep the exact position. */
	@Test
	void cameraWrapsAndKeepsFractAndInt() {
		FrameUniforms u = new FrameUniforms();
		TestInputs in = new TestInputs();
		frame(u, in, 0.016f);
		ByteBuffer b = block(u);
		// cameraPosition is the in-game coordinate; Fract/Int keep the exact raw position; eyeAltitude is the F3 Y.
		assertArrayEquals(new float[] { 100.5f, 64.25f, 200.75f }, vecAt(b, "cameraPosition", 3));
		assertArrayEquals(new float[] { 0.5f, 0.25f, 0.75f }, vecAt(b, "cameraPositionFract", 3));
		assertEquals(100, b.getInt(OFFSETS.get("cameraPositionInt")));
		assertEquals(64, b.getInt(OFFSETS.get("cameraPositionInt") + 4));
		assertEquals(200, b.getInt(OFFSETS.get("cameraPositionInt") + 8));
		assertEquals(64.25f, floatAt(b, "eyeAltitude"), TOL);
		// A teleport over 1000 blocks re-anchors X and Z only: eyeAltitude is the raw Y (Iris "Camera": eyeAltitude
		// "is equivalent to cameraPosition.y"), so cameraPosition.y always stays the in-game Y. Int stays the raw
		// coordinate, the previous value the old one.
		in.cameraX = 5000.5;
		frame(u, in, 0.016f);
		b = block(u);
		assertArrayEquals(new float[] { 0.5f, 64.25f, 0.75f }, vecAt(b, "cameraPosition", 3));
		assertEquals(5000, b.getInt(OFFSETS.get("cameraPositionInt")));
		assertArrayEquals(new float[] { 100.5f, 64.25f, 200.75f }, vecAt(b, "previousCameraPosition", 3));
		// A 10-block walk keeps the wrap anchor: only the 30000-block wrap or a teleport shifts X and Z.
		in.cameraX = 5010.5;
		frame(u, in, 0.016f);
		assertArrayEquals(new float[] { 10.5f, 64.25f, 0.75f }, vecAt(block(u), "cameraPosition", 3));
		// Walking past 30000 blocks from the anchor re-anchors again, still without shifting Y.
		in.cameraX = 35010.25;
		frame(u, in, 0.016f);
		assertArrayEquals(new float[] { 0.25f, 64.25f, 0.75f }, vecAt(block(u), "cameraPosition", 3));
	}

	/** The fog range that starts closer is the fog a pack sees, with its shape; the game's fog is linear. */
	@Test
	void fogReportsTheCloserRangeWithItsShape() {
		FrameUniforms u = new FrameUniforms();
		TestInputs in = new TestInputs();
		frame(u, in, 0.016f);
		ByteBuffer b = block(u);
		assertEquals(9729, intAt(b, "fogMode"));
		assertEquals(0f, floatAt(b, "fogDensity"));
		assertEquals(10f, floatAt(b, "fogStart"));
		assertEquals(100f, floatAt(b, "fogEnd"));
		assertEquals(0, intAt(b, "fogShape"));
		assertArrayEquals(new float[] { 0.7f, 0.8f, 0.9f }, vecAt(b, "fogColor", 3));
		assertArrayEquals(new float[] { 0.4f, 0.6f, 1f }, vecAt(b, "skyColor", 3));
		in.fogEnvironmentalStart = 200f;
		in.fogEnvironmentalEnd = 300f;
		frame(u, in, 0.016f);
		b = block(u);
		assertEquals(90f, floatAt(b, "fogStart"));
		assertEquals(192f, floatAt(b, "fogEnd"));
		assertEquals(1, intAt(b, "fogShape"));
	}

	/** The screen, player, biome and dimension values cross unchanged, with the documented conversions. */
	@Test
	void screenPlayerBiomeAndDimensionCrossWithTheirUnits() {
		FrameUniforms u = new FrameUniforms();
		TestInputs in = new TestInputs();
		in.eyeFluid = 3;
		in.hideGui = true;
		in.lightning = true;
		in.lightningX = 10;
		frame(u, in, 0.016f);
		ByteBuffer b = block(u);
		assertEquals(1920f / 1080f, floatAt(b, "aspectRatio"), TOL);
		assertEquals(1920f, floatAt(b, "viewWidth"));
		assertEquals(1080f, floatAt(b, "viewHeight"));
		assertEquals(0.5f, floatAt(b, "screenBrightness"));
		assertEquals(0.05f, floatAt(b, "near"));
		assertEquals(192f, floatAt(b, "far"));
		assertEquals(0.1f, floatAt(b, "alphaTestRef"));
		u.setAlphaTestRef(0.35f);
		frame(u, in, 0.016f);
		assertEquals(0.35f, floatAt(block(u), "alphaTestRef"));
		assertEquals(3, intAt(b, "isEyeInWater"));
		assertEquals(1, intAt(b, "hideGUI"));
		assertEquals(20f / 20f, floatAt(b, "currentPlayerHealth"), TOL);
		assertEquals(20f, floatAt(b, "maxPlayerHealth"));
		assertEquals(10f / 50f, floatAt(b, "currentPlayerArmor"), TOL);
		assertEquals(50f, floatAt(b, "maxPlayerArmor"));
		assertEquals(300f / 300f, floatAt(b, "currentPlayerAir"), TOL);
		assertEquals(20f / 20f, floatAt(b, "currentPlayerHunger"), TOL);
		assertTrue(boolAt(b, "is_alive"));
		assertTrue(boolAt(b, "firstPersonCamera"));
		assertTrue(boolAt(b, "is_on_ground"));
		assertEquals(0, intAt(b, "isSpectator"));
		assertEquals(2, intAt(b, "biome"));
		assertEquals(5, intAt(b, "biome_category"));
		assertEquals(-64, intAt(b, "bedrockLevel"));
		assertEquals(384, intAt(b, "heightLimit"));
		assertTrue(boolAt(b, "hasSkylight"));
		assertEquals(192f, floatAt(b, "cloudHeight"));
		// The bolt's offset from the camera in player space (Iris "World and Weather"), w 1 when one exists.
		assertArrayEquals(new float[] { -90.5f, -0.25f, 9.25f, 1f }, vecAt(b, "lightningBoltPosition", 4));
		// Creative reads -1 for the relative stats, the armor maximum stays.
		in.creative = true;
		frame(u, in, 0.016f);
		b = block(u);
		assertEquals(-1f, floatAt(b, "currentPlayerHealth"));
		assertEquals(-1f, floatAt(b, "maxPlayerHealth"));
		assertEquals(50f, floatAt(b, "maxPlayerArmor"));
		// Hunger has no mode -1: the maximum stays 20 even where the modes have no hunger (Iris "Player Status").
		assertEquals(-1f, floatAt(b, "currentPlayerHunger"));
		assertEquals(20f, floatAt(b, "maxPlayerHunger"));
		// Without a bolt the uniform is zero.
		in.lightning = false;
		frame(u, in, 0.016f);
		assertArrayEquals(new float[] { 0f, 0f, 0f, 0f }, vecAt(block(u), "lightningBoltPosition", 4));
		// The End flash keeps its previous intensity one frame behind.
		in.endFlashIntensity = 0.7f;
		frame(u, in, 0.016f);
		assertEquals(0f, floatAt(block(u), "previousEndFlashIntensity"));
		frame(u, in, 0.016f);
		assertEquals(0.7f, floatAt(block(u), "previousEndFlashIntensity"));
	}

	/** The expression parameters read this frame's values; texel sizes derive from the screen; unknown names zero. */
	@Test
	void fillServesTheExpressionParameters() {
		FrameUniforms u = new FrameUniforms();
		frame(u, new TestInputs(), 0.016f);
		var frame = new dev.mcms.shaders.expr.ExprFrame();
		var layout = new dev.mcms.shaders.pack.FrameLayout(java.util.List.of(
			new dev.mcms.shaders.pack.FrameLayout.Parameter("sunAngle", dev.mcms.shaders.expr.ExprType.FLOAT, 0),
			new dev.mcms.shaders.pack.FrameLayout.Parameter("worldTime", dev.mcms.shaders.expr.ExprType.INT, 1),
			new dev.mcms.shaders.pack.FrameLayout.Parameter("is_on_ground", dev.mcms.shaders.expr.ExprType.BOOL, 2),
			new dev.mcms.shaders.pack.FrameLayout.Parameter("texelSizeX", dev.mcms.shaders.expr.ExprType.FLOAT, 3),
			new dev.mcms.shaders.pack.FrameLayout.Parameter("texelSizeY", dev.mcms.shaders.expr.ExprType.FLOAT, 4),
			new dev.mcms.shaders.pack.FrameLayout.Parameter("cameraPosition", dev.mcms.shaders.expr.ExprType.VEC3, 5),
			new dev.mcms.shaders.pack.FrameLayout.Parameter("gbufferProjection", dev.mcms.shaders.expr.ExprType.MAT4, 6),
			new dev.mcms.shaders.pack.FrameLayout.Parameter("shadowModelView", dev.mcms.shaders.expr.ExprType.MAT4, 7),
			new dev.mcms.shaders.pack.FrameLayout.Parameter("bossBattle", dev.mcms.shaders.expr.ExprType.INT, 8),
			new dev.mcms.shaders.pack.FrameLayout.Parameter("unheard_of", dev.mcms.shaders.expr.ExprType.FLOAT, 9)), 10);
		frame.ensureSlots(10);
		// Stale garbage in every slot: fill must overwrite all of them.
		for (int i = 0; i < 10; i++) {
			frame.setFloat(i, 42f);
		}
		u.fill(frame, layout);
		assertEquals(0.25f, frame.getFloat(0));
		assertEquals(6000, frame.getInt(1));
		assertTrue(frame.getBool(2));
		assertEquals(1f / 1920f, frame.getFloat(3), TOL);
		assertEquals(1f / 1080f, frame.getFloat(4), TOL);
		float[] v = new float[4];
		frame.getVec(5, v);
		assertArrayEquals(new float[] { 100.5f, 64.25f, 200.75f, 0f }, v);
		// The matrix parameters are the block's (their element access is exercised through custom uniforms in
		// CustomUniformsTest); the unknown parameter is zeroed, not left at its stale value.
		// bossBattle is 0 until the frame state tracks boss bars (1 is a custom boss bar, OptiFine doc).
		assertEquals(0, frame.getInt(8));
		assertEquals(0f, frame.getFloat(9));
	}

	/** The block's bytes are little-endian at the schema's offsets. */
	@Test
	void writeProducesLittleEndianBytesAtTheSchemaOffsets() {
		FrameUniforms u = new FrameUniforms();
		frame(u, new TestInputs(), 0.016f);
		ByteBuffer b = block(u);
		int o = OFFSETS.get("viewWidth");
		assertEquals(0x00, b.get(o) & 0xff);
		assertEquals(0x00, b.get(o + 1) & 0xff);
		assertEquals(0xf0, b.get(o + 2) & 0xff);
		assertEquals(0x44, b.get(o + 3) & 0xff);
		assertEquals(6000, b.getInt(OFFSETS.get("worldTime")));
	}

	/**
	 * Without Distant Horizons the QsDh block carries Iris 1.11.7's values (mcms-uniforms, 2026-11-16): both planes 0.01,
	 * the render distance in chunks and the gbuffer projection. Zero planes divided zero by zero in Bliss's depth helpers.
	 */
	@Test
	void theDistantBlockWithoutDhCarriesIrissValues() {
		FrameUniforms u = new FrameUniforms();
		TestInputs in = new TestInputs();
		frame(u, in, 0.016f);
		var block = dev.mcms.shaders.runtime.distant.DistantUniforms.block(0);
		ByteBuffer b = ByteBuffer.allocate(dev.mcms.shaders.runtime.distant.DistantUniforms.size(block))
			.order(ByteOrder.LITTLE_ENDIAN);
		u.writeDistant(block, b);
		Map<String, Integer> at = new HashMap<>();
		for (var f : block.fields()) {
			at.put(f.name(), f.offset());
		}
		assertEquals(0.01f, b.getFloat(at.get("dhNearPlane")));
		assertEquals(0.01f, b.getFloat(at.get("dhFarPlane")));
		assertEquals(in.renderDistanceChunks, b.getInt(at.get("dhRenderDistance")));
		Matrix4f projection = matAt(block(u), "gbufferProjection");
		for (int c = 0; c < 4; c++) {
			for (int r = 0; r < 4; r++) {
				assertEquals(projection.get(c, r), b.getFloat(at.get("dhProjection") + (c * 4 + r) * Float.BYTES), TOL);
			}
		}
	}

	/** The biome's downfall (rainfall) is read through the game's own fields; a renamed field would read 0 silently. */
	@Test
	void theBiomesDownfallIsReadable() {
		assertTrue(BiomeClimate.readable());
	}

	/** centerDepthSmooth approaches the set centre depth with the default one-tick half-life. */
	@Test
	void centerDepthSmoothApproachesTheSetDepth() {
		FrameUniforms u = new FrameUniforms();
		TestInputs in = new TestInputs();
		frame(u, in, 0f);
		assertEquals(1f, floatAt(block(u), "centerDepthSmooth"));
		u.setCenterDepth(0.5f);
		frame(u, in, 0.05f);
		assertEquals(0.75f, floatAt(block(u), "centerDepthSmooth"), TOL);
	}

	/**
	 * constantMood is playerMood without the reset at 1.0 (Iris "Player Status"): it keeps the mood's maximum while
	 * the mood value cycles across several frames.
	 */
	@Test
	void constantMoodKeepsItsMaximumAcrossMoodCycles() {
		FrameUniforms u = new FrameUniforms();
		TestInputs in = new TestInputs();
		for (float mood : new float[] { 0.5f, 0.8f, 1.0f }) {
			in.playerMood = mood;
			frame(u, in, 0.016f);
			assertEquals(mood, floatAt(block(u), "constantMood"), TOL);
		}
		// playerMood resets to 0 after reaching 1.0; constantMood stays at 1.0.
		in.playerMood = 0f;
		frame(u, in, 0.016f);
		assertEquals(0f, floatAt(block(u), "playerMood"), TOL);
		assertEquals(1.0f, floatAt(block(u), "constantMood"), TOL);
		// The climb after the reset keeps the held maximum, and a natural decrease (the player left the cave)
		// follows the mood down again.
		in.playerMood = 0.5f;
		frame(u, in, 0.016f);
		assertEquals(1.0f, floatAt(block(u), "constantMood"), TOL);
		in.playerMood = 0.4f;
		frame(u, in, 0.016f);
		assertEquals(0.4f, floatAt(block(u), "constantMood"), TOL);
		// A decrease to zero that is no reset follows too: Iris reads 0 after the mood rose a step and fell back
		// (mcms-uniforms, 2026-11-16).
		in.playerMood = 0f;
		frame(u, in, 0.016f);
		assertEquals(0f, floatAt(block(u), "constantMood"), TOL);
	}

	/**
	 * sunPathRotation (the pack const, degrees) tilts the sun and moon path around the east axis (Iris
	 * "sunPathRotation": rotated perpendicular to their direction of travel, 0 crosses the top of the sky dome), so
	 * the noon sun moves off the zenith toward -Z for a positive tilt (the sign packs compute their own sun with, BSL's
	 * sunmoon.glsl; measured in E7), and the shadow light follows the higher body.
	 */
	@Test
	void sunPathRotationTiltsTheCelestialPath() {
		FrameUniforms u = new FrameUniforms();
		TestInputs in = new TestInputs();
		u.setSunPathRotation(45f);
		frame(u, in, 0.016f);
		ByteBuffer b = block(u);
		float s = (float) (100d * Math.sin(Math.PI / 4d));
		assertArrayEquals(new float[] { 0f, s, -s }, vecAt(b, "sunPosition", 3));
		assertArrayEquals(new float[] { 0f, -s, s }, vecAt(b, "moonPosition", 3));
		assertArrayEquals(new float[] { 0f, s, -s }, vecAt(b, "shadowLightPosition", 3));
		// Midnight: the moon is the shadow light, tilted the same way.
		in.dayTime = 18000;
		in.sunAngleDegrees = 180f;
		frame(u, in, 0.016f);
		b = block(u);
		assertArrayEquals(new float[] { 0f, s, -s }, vecAt(b, "moonPosition", 3));
		assertArrayEquals(new float[] { 0f, s, -s }, vecAt(b, "shadowLightPosition", 3));
		// Rotation 0 keeps the vanilla overhead path, and the values hold over several frames.
		u.setSunPathRotation(0f);
		in.dayTime = 6000;
		in.sunAngleDegrees = 0f;
		for (int i = 0; i < 3; i++) {
			frame(u, in, 0.016f);
		}
		assertArrayEquals(new float[] { 0f, 100f, 0f }, vecAt(block(u), "sunPosition", 3));
	}

	/** worldTime and worldDay stay exact after millions of days. */
	@Test
	void clockIsExactAfterMillionsOfDays() {
		FrameUniforms u = new FrameUniforms();
		TestInputs in = new TestInputs();
		in.dayTime = 24000L * 3_000_000L + 6000L;
		frame(u, in, 0.016f);
		frame(u, in, 0.016f);
		ByteBuffer b = block(u);
		assertEquals(0.25f, floatAt(b, "sunAngle"), TOL);
		assertEquals(6000, intAt(b, "worldTime"));
		assertEquals(3_000_000, intAt(b, "worldDay"));
	}

	/** The capture fallback for dimensions without a sky: the moon phase from the level's day time (vanilla rule). */
	@Test
	void moonPhaseFallsBackToTheDayTimeRule() {
		// Full moon on day 0, new moon on day 4, full again on day 8 (MoonPhase: 0 full ... 4 new).
		assertEquals(0, FrameInputs.moonPhaseFromDayTime(0L));
		assertEquals(4, FrameInputs.moonPhaseFromDayTime(4L * 24000L));
		assertEquals(0, FrameInputs.moonPhaseFromDayTime(8L * 24000L));
		assertEquals(3, FrameInputs.moonPhaseFromDayTime(24000L * 3_000_000L + 3L * 24000L));
	}

	private static void assertArrayEquals(float[] expected, float[] actual) {
		org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual, (float) TOL);
	}
}
