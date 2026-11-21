// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mcms.glsl.msl.ProgramRequest.TextureKind;
import org.junit.jupiter.api.Test;

/**
 * A gbuffers sampler's kind comes from its GLSL type (review item 14). A depthtex name is the depth map only when no custom
 * texture binds it: a custom texture on depthtex0 is an ordinary texture of the type the program declares.
 */
final class GbuffersKindTest {

	@Test
	void aDepthtexNameWithNoCustomTextureIsTheDepthMap() {
		assertEquals(TextureKind.DEPTH, ProgramRequests.gbuffersKind("depthtex0", "sampler2D", false, false));
	}

	@Test
	void aCustomTextureOnADepthtexNameIsATextureOfItsDeclaredType() {
		assertEquals(TextureKind.COLOUR, ProgramRequests.gbuffersKind("depthtex0", "sampler2D", false, true));
		assertEquals(TextureKind.THREE_D, ProgramRequests.gbuffersKind("depthtex0", "sampler3D", false, true));
		assertEquals(TextureKind.INTEGER, ProgramRequests.gbuffersKind("depthtex1", "isampler2D", false, true));
	}

	@Test
	void theOtherKindsDoNotDependOnTheCustomTexture() {
		assertEquals(TextureKind.COMPARE, ProgramRequests.gbuffersKind("shadowtex0", "sampler2DShadow", true, false));
		assertEquals(TextureKind.CUBE, ProgramRequests.gbuffersKind("envMap", "samplerCube", false, false));
		assertEquals(TextureKind.COLOUR, ProgramRequests.gbuffersKind("colortex1", "sampler2D", false, false));
	}
}
