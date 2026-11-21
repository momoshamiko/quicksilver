// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * The {@code std140} offsets of Sodium's {@code u_Globals} from both jars ADR 0007 allows, against the hand-computed
 * values of the ADR's Context (plan.md E4's test list): beta.1 ({@code g6ju16VJ}) has no {@code u_CameraOffset} and
 * is 184 bytes; beta.5 ({@code v7Lw0h1m}) declares it between {@code u_FogColor} and {@code u_EnvironmentFog}, at
 * 144, moving every later field (the block grows to 200). The jars reach the test as zip files at the system
 * properties the build sets ({@code mcms.test.sodium.beta1} from the compile classpath, {@code
 * mcms.test.sodium.beta5} from the test classpath); a jar that is absent skips its half.
 */
class SodiumGlobalsLayoutTest {

	@Test
	void beta1HasNoCameraOffsetAndIs184Bytes() throws Exception {
		String jar = System.getProperty("mcms.test.sodium.beta1");
		assumeTrue(jar != null, "the beta.1 jar path was not set");
		SodiumGlobals globals = SodiumGlobals.parse(readGlobals(jar));
		assertTrue(globals.problems().isEmpty(), () -> String.join("; ", globals.problems()));
		assertEquals(-1L, globals.cameraOffsetAt());
		assertEquals(184, globals.size());
		assertEquals(0L, globals.offsetOf("u_ProjectionMatrix"));
		assertEquals(64L, globals.offsetOf("u_ModelViewMatrix"));
		assertEquals(128L, globals.offsetOf("u_FogColor"));
		assertEquals(144L, globals.offsetOf("u_EnvironmentFog"));
		assertEquals(152L, globals.offsetOf("u_RenderFog"));
		assertEquals(160L, globals.offsetOf("u_TexelSize"));
		assertEquals(168L, globals.offsetOf("u_TexCoordShrink"));
		assertEquals(176L, globals.offsetOf("u_FadePeriodInv"));
		assertEquals(180L, globals.offsetOf("u_UseRGSS"));
	}

	@Test
	void beta5DeclaresTheCameraOffsetAt144AndMovesEveryLaterField() throws Exception {
		String jar = System.getProperty("mcms.test.sodium.beta5");
		assumeTrue(jar != null, "the beta.5 jar path was not set");
		SodiumGlobals globals = SodiumGlobals.parse(readGlobals(jar));
		assertTrue(globals.problems().isEmpty(), () -> String.join("; ", globals.problems()));
		assertEquals(144L, globals.cameraOffsetAt());
		assertEquals(200, globals.size());
		assertEquals(160L, globals.offsetOf("u_EnvironmentFog"));
		assertEquals(168L, globals.offsetOf("u_RenderFog"));
		assertEquals(176L, globals.offsetOf("u_TexelSize"));
		assertEquals(184L, globals.offsetOf("u_TexCoordShrink"));
		assertEquals(192L, globals.offsetOf("u_FadePeriodInv"));
		assertEquals(196L, globals.offsetOf("u_UseRGSS"));
	}

	@Test
	void aBlockThatLacksARequiredFieldReportsTheProblem() {
		SodiumGlobals globals = SodiumGlobals.parse("""
			layout(std140) uniform u_Globals {
			    vec4 u_FogColor;
			};
			""");
		assertEquals(3, globals.problems().size());
		String problems = String.join("; ", globals.problems());
		assertTrue(problems.contains("u_ModelViewMatrix"));
		assertTrue(problems.contains("u_ProjectionMatrix"));
		assertTrue(problems.contains("u_TexCoordShrink"), "the decode reads the shrink, so a block without it fails");
	}

	@Test
	void aBlockWithProblemsFailsTheTerrainLoadWithItsReason() {
		SodiumGlobals broken = SodiumGlobals.parse("layout(std140) uniform u_Globals { vec4 u_FogColor; };");
		String failure = TerrainEngine.globalsFailure(broken);
		assertNotNull(failure, "a globals block with problems must not let the terrain extension start");
		assertTrue(failure.contains("u_TexCoordShrink"));
	}

	@Test
	void aCompleteBlockAllowsTheTerrainLoad() {
		String text = """
			layout(std140) uniform u_Globals {
			    mat4 u_ProjectionMatrix;
			    mat4 u_ModelViewMatrix;
			    vec4 u_FogColor;
			    vec2 u_TexCoordShrink;
			};
			""";
		assertNull(TerrainEngine.globalsFailure(SodiumGlobals.parse(text)));
	}

	private static String readGlobals(String jar) throws Exception {
		try (ZipFile zip = new ZipFile(jar)) {
			ZipEntry entry = zip.getEntry("assets/sodium/shaders/include/globals.glsl");
			assumeTrue(entry != null, "no globals.glsl in " + jar);
			try (InputStream in = zip.getInputStream(entry)) {
				return new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
		}
	}
}
