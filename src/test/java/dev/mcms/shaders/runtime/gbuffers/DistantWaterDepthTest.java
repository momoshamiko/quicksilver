// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Complementary's dh_water reads the solid depth under depthtex0 (Iris draws it before the game's translucents). The routing of
 * dh_water's depthtex0 to the solid copy (depthtex1) applies on both water paths: in the phase hook the copy equals the live depth,
 * and on the after-level fallback only the copy holds the solid depth. Every other program and name keeps its own depth.
 */
class DistantWaterDepthTest {

	@Test
	void dhWaterReadsTheSolidCopyUnderDepthtex0() {
		assertEquals("depthtex1", GbufferDelegate.depthNameFor("dh_water", "depthtex0"));
	}

	@Test
	void dhTerrainKeepsTheLiveDepth() {
		assertEquals("depthtex0", GbufferDelegate.depthNameFor("dh_terrain", "depthtex0"));
	}

	@Test
	void dhWaterKeepsItsOwnDepthtex1AndDepthtex2() {
		assertEquals("depthtex1", GbufferDelegate.depthNameFor("dh_water", "depthtex1"));
		assertEquals("depthtex2", GbufferDelegate.depthNameFor("dh_water", "depthtex2"));
	}

	@Test
	void aProgramWithNoNameKeepsTheName() {
		assertEquals("depthtex0", GbufferDelegate.depthNameFor(null, "depthtex0"));
	}

	@Test
	void theDhDepthNamesAreUnchangedForDhWater() {
		assertEquals("dhDepthTex0", GbufferDelegate.depthNameFor("dh_water", "dhDepthTex0"));
	}
}
