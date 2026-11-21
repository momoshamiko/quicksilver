// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.shaders.runtime.SamplerSources.Source;
import org.junit.jupiter.api.Test;

/**
 * The gbuffers sampler table (engine-pack-format.md sections 2 and 12): each documented name reads its source, so a name
 * the table leaves UNBOUND is one the engine binds to nothing (and the delegate logs once per set).
 */
class SamplerSourcesTest {

	@Test
	void theAtlasHasItsThreeGbuffersNames() {
		for (String name : new String[] {"texture", "gtexture", "tex"}) {
			assertEquals(Source.ATLAS, SamplerSources.of(name), name);
		}
		assertEquals(Source.LIGHTMAP, SamplerSources.of("lightmap"));
	}

	@Test
	void thePBRDefaultsAndTheEngineTexturesHaveSources() {
		assertEquals(Source.NORMALS, SamplerSources.of("normals"));
		assertEquals(Source.SPECULAR, SamplerSources.of("specular"));
		assertEquals(Source.NOISE, SamplerSources.of("noisetex"));
		for (String name : new String[] {"depthtex0", "depthtex1", "depthtex2"}) {
			assertEquals(Source.DEPTH, SamplerSources.of(name), name);
		}
	}

	@Test
	void theColourTargetsAndTheirAliasesAreTargets() {
		assertEquals(4, SamplerSources.targetOf("colortex4"));
		assertEquals(4, SamplerSources.targetOf("gaux1"));
		assertEquals(7, SamplerSources.targetOf("gaux4"));
		assertEquals(0, SamplerSources.targetOf("gcolor"));
		assertEquals(Source.TARGET, SamplerSources.of("gaux2"));
		assertEquals(Source.TARGET, SamplerSources.of("colortex15"));
	}

	@Test
	void shadowNamesReadTheShadowTargets() {
		for (String name : new String[] {"shadow", "watershadow", "shadowtex0", "shadowtex1", "shadowtex0HW",
				"shadowtex1HW", "shadowcolor", "shadowcolor0", "shadowcolor1", "shadowcolor7"}) {
			assertEquals(Source.SHADOW, SamplerSources.of(name), name);
		}
	}

	@Test
	void theDistantHorizonsDepthNamesReadTheDistantDepthTargets() {
		assertEquals(Source.DISTANT_DEPTH, SamplerSources.of("dhDepthTex0"));
		assertEquals(Source.DISTANT_DEPTH, SamplerSources.of("dhDepthTex1"));
		assertTrue(SamplerSources.unboundReason("dhDepthTex1").contains("Distant Horizons"));
	}

	@Test
	void theUnsuffixedDhDepthTexIsAliasOfDhDepthTex0() {
		// Complementary (lib/uniforms.glsl), Unbound, Bliss and Photon declare dhDepthTex; it is the same depth as dhDepthTex0.
		assertEquals(Source.DISTANT_DEPTH, SamplerSources.of("dhDepthTex"));
		assertEquals(0, SamplerSources.distantDepthOf("dhDepthTex"));
		assertEquals(0, SamplerSources.distantDepthOf("dhDepthTex0"));
		assertEquals(1, SamplerSources.distantDepthOf("dhDepthTex1"));
		assertEquals(-1, SamplerSources.distantDepthOf("depthtex0"));
		assertTrue(SamplerSources.unboundReason("dhDepthTex").contains("Distant Horizons"));
	}

	@Test
	void anyOtherNameIsUnboundWithoutAShadowReason() {
		for (String name : new String[] {"shadowcolor8", "colortex16", "dhDepthTex2", "vxDepthTexOpaque", "voxel_sampler",
				"shadowtex2", "shadowtex0hw", "Shadowtex0", "shadowcolor08", "shadowcolorimg0", "watershadow0"}) {
			assertEquals(Source.UNBOUND, SamplerSources.of(name), name);
			assertFalse(SamplerSources.unboundReason(name).contains("E5"), name);
		}
	}

	@Test
	void everyShadowColourBufferNameReadsTheShadowTargets() {
		for (int i = 0; i < 8; i++) {
			assertEquals(Source.SHADOW, SamplerSources.of("shadowcolor" + i), "shadowcolor" + i);
		}
	}

	@Test
	void aLookupAllocatesNothing() {
		// RIL-567: the binds call this per sampler of every draw; a regex Matcher per call was most of the render thread's
		// allocation. 100000 lookups of every kind of name must not allocate.
		String[] names = {"texture", "lightmap", "colortex4", "gaux1", "shadowtex1", "shadowcolor3", "watershadow",
			"dhDepthTex", "noisetex", "voxel_sampler", "shadowcolor8"};
		com.sun.management.ThreadMXBean threads =
			(com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
		int sink = 0;
		for (int i = 0; i < 10_000; i++) {
			sink += SamplerSources.of(names[i % names.length]).ordinal();  // warm up
		}
		long before = threads.getCurrentThreadAllocatedBytes();
		for (int i = 0; i < 100_000; i++) {
			sink += SamplerSources.of(names[i % names.length]).ordinal();
		}
		long allocated = threads.getCurrentThreadAllocatedBytes() - before;
		assertTrue(allocated < 64 * 1024, "100000 lookups allocated " + allocated + " bytes (sink " + sink + ")");
	}
}
