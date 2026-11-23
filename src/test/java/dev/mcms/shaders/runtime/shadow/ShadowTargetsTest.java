// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.shadow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.mcms.shaders.pack.ConstValue;
import dev.mcms.shaders.pack.TargetUnion;
import dev.mcms.shaders.runtime.TargetFormats;
import dev.mcms.shaders.runtime.Targets;

/**
 * The shadow targets' layout and bookkeeping against the documented constants (engine-pack-format.md section 16; the
 * Iris shadowtex and shadowcolor pages): sizes, formats, the mip, filter and hardware rules, the name aliases, the
 * declaration rewrite of shadowHardwareFiltering, the mip staleness, and the allocation (a refused texture leaves
 * nothing allocated). A recording fake stands in for the device.
 */
class ShadowTargetsTest {

	/** Records every texture it creates and releases, and refuses the Nth creation when asked. */
	static final class FakeTextures implements Targets.Textures {
		final List<long[]> created = new ArrayList<>();
		final List<Long> released = new ArrayList<>();
		int refuseAt = -1;
		long next = 100;

		@Override
		public long create(int format, int width, int height, int mips) {
			if (created.size() == refuseAt) {
				return 0;
			}
			long handle = next++;
			created.add(new long[] {handle, format, width, height, mips});
			return handle;
		}

		@Override
		public void release(long handle) {
			released.add(handle);
		}
	}

	private static ConstValue flag(boolean value) {
		return new ConstValue("bool", List.of(value ? 1.0 : 0.0));
	}

	private static TargetUnion union(Map<String, ConstValue> constants, Map<Integer, String> formats) {
		return new TargetUnion(Map.of(), Map.of(), Map.of(), Map.of(), formats, constants, List.of());
	}

	private static ShadowTargets targets(Map<String, ConstValue> constants, Map<Integer, String> formats,
			boolean waterShadow) {
		return new ShadowTargets(union(constants, formats), waterShadow, true);
	}

	@Test
	void resolutionIsTheDeclaredSizeOrTheDocumentedDefault() {
		assertEquals(2048, targets(Map.of("shadowMapResolution", new ConstValue("int", List.of(2048.0))),
			Map.of(), false).resolution());
		assertEquals(1024, targets(Map.of(), Map.of(), false).resolution());
	}

	@Test
	void colourFormatsComeFromTheShadowFormatWordsAndDefaultToRgba8() {
		ShadowTargets t = targets(Map.of(), Map.of(0, "RGBA16F"), false);
		assertEquals(TargetFormats.of("RGBA16F", 0), t.colorFormat(0));
		assertEquals(TargetFormats.RGBA8, t.colorFormat(1));
	}

	@Test
	void aShadowBufferWordIsTheSameFormatAtEveryIndex() {
		// The colortex1 rule (RGBA upgraded to RGBA32F) does not apply to shadowcolor1: its RGBA is RGBA8.
		ShadowTargets t = targets(Map.of(), Map.of(1, "RGBA"), false);
		assertEquals(TargetFormats.RGBA8, t.colorFormat(1));
	}

	@Test
	void aShadowcolourNamedOnlyInACommentNeedsNoBuffer() {
		assertEquals(0, ShadowTargets.colorsNamedBy("// read shadowcolor7 here\nvoid main() {}"));
		assertEquals(0, ShadowTargets.colorsNamedBy("/* shadowcolor5 */ void main() {}"));
		assertEquals(8, ShadowTargets.colorsNamedBy("/* note */ vec4 c = texture2D(shadowcolor7, uv);"));
	}

	@Test
	void twoColourBuffersExistAndDeclarationsGrowTheCount() {
		assertEquals(2, targets(Map.of(), Map.of(), false).colorCount());
		assertEquals(5, targets(Map.of(), Map.of(4, "RGBA8"), false).colorCount());
	}

	@Test
	void namesResolveToDepthAndColourIndexesWithTheAliasesOfTheDocs() {
		ShadowTargets plain = targets(Map.of(), Map.of(), false);
		assertEquals(0, plain.depthIndexOf("shadowtex0"));
		assertEquals(1, plain.depthIndexOf("shadowtex1"));
		assertEquals(0, plain.depthIndexOf("shadow"));
		assertEquals(0, plain.depthIndexOf("watershadow"));
		assertEquals(0, plain.depthIndexOf("shadowtex0HW"));
		assertEquals(1, plain.depthIndexOf("shadowtex1HW"));
		assertEquals(-1, plain.depthIndexOf("shadowcolor0"));
		assertEquals(-1, plain.depthIndexOf("depthtex0"));
		assertEquals(1, targets(Map.of(), Map.of(), true).depthIndexOf("shadow"));
		assertEquals(0, targets(Map.of(), Map.of(), true).depthIndexOf("watershadow"));

		for (int i = 0; i < 8; i++) {
			assertEquals(i, plain.colorIndexOf("shadowcolor" + i), "shadowcolor" + i);
		}
		assertEquals(0, plain.colorIndexOf("shadowcolor"));
		assertEquals(-1, plain.colorIndexOf("shadowcolor8"));
		assertEquals(-1, plain.colorIndexOf("shadowcolor08"));
		assertEquals(-1, plain.colorIndexOf("shadowtex0"));
		assertEquals(-1, plain.depthIndexOf("shadowtex2"));
		assertEquals(-1, plain.depthIndexOf("shadowtex0hw"));
	}

	@Test
	void theIndexLookupsAllocateNothing() {
		// RIL-567: the binds call these per sampler of every draw; a regex Matcher per call allocated on every bind.
		ShadowTargets plain = targets(Map.of(), Map.of(), false);
		String[] names = {"shadow", "shadowtex1HW", "watershadow", "shadowcolor5", "shadowcolor", "colortex4", "shadowtex2"};
		com.sun.management.ThreadMXBean threads =
			(com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
		int sink = 0;
		for (int i = 0; i < 10_000; i++) {
			String name = names[i % names.length];
			sink += plain.depthIndexOf(name) + plain.colorIndexOf(name);  // warm up
		}
		long before = threads.getCurrentThreadAllocatedBytes();
		for (int i = 0; i < 100_000; i++) {
			String name = names[i % names.length];
			sink += plain.depthIndexOf(name) + plain.colorIndexOf(name);
		}
		long allocated = threads.getCurrentThreadAllocatedBytes() - before;
		assertTrue(allocated < 64 * 1024, "100000 lookups allocated " + allocated + " bytes (sink " + sink + ")");
	}

	@Test
	void depthMapsHaveNoMipChainWhateverThePackAsks() {
		// Depth32Float has no mip generation on Metal (E5 review item 10); a request is ignored with one WARN.
		Map<String, ConstValue> constants = Map.of(
			"generateShadowMipmap", flag(true),
			"shadowtex1Mipmap", flag(false));
		ShadowTargets t = targets(constants, Map.of(), false);
		assertFalse(t.mipmappedDepth(0));
		assertFalse(t.mipmappedDepth(1));

		ShadowTargets generic = targets(Map.of("shadowtexMipmap", flag(true)), Map.of(), false);
		assertFalse(generic.mipmappedDepth(0));
		assertFalse(targets(Map.of(), Map.of(), false).mipmappedDepth(0));
	}

	@Test
	void colourMipmapsFollowTheShadowColourConstants() {
		Map<String, ConstValue> constants = Map.of(
			"generateShadowColorMipmap", flag(true),
			"shadowColor1Mipmap", flag(false));
		ShadowTargets t = targets(constants, Map.of(), false);
		assertTrue(t.mipmappedColor(0));
		assertFalse(t.mipmappedColor(1));
	}

	@Test
	void nearestFilteringAppliesOnlyToTheNamedMap() {
		ShadowTargets t = targets(Map.of("shadowtexNearest", flag(true)), Map.of(), false);
		assertTrue(t.nearestDepth(0));
		assertFalse(t.nearestDepth(1));
		assertTrue(targets(Map.of("shadow1MinMagNearest", flag(true)), Map.of(), false).nearestDepth(1));
		assertFalse(targets(Map.of(), Map.of(), false).nearestDepth(0));
		assertTrue(targets(Map.of("shadowcolor1Nearest", flag(true)), Map.of(), false).nearestColor(1));
	}

	@Test
	void hardwareFilteringIsOffUnlessTheConstantIsDeclaredTrue() {
		// The docs give shadowHardwareFiltering = true as the example value and no default; with no declaration the
		// declared sampler type decides (ShadowTargets.rewriteDeclarations), so the engine turns nothing on.
		assertFalse(targets(Map.of(), Map.of(), false).hardwareDepth(0));
		ShadowTargets t = targets(Map.of("shadowHardwareFiltering", flag(true),
			"shadowHardwareFiltering0", flag(false)), Map.of(), false);
		assertFalse(t.hardwareDepth(0));
		assertTrue(targets(Map.of("shadowHardwareFiltering", flag(true)), Map.of(), false).hardwareDepth(1));
	}

	@Test
	void declaredHardwareFilteringRewritesTheShadowDeclarationsToComparisonSamplers() {
		// shadowtex samplers become sampler2DShadow when hardware filtering is on (shadowtex page, "Hardware Sampling").
		ShadowTargets t = targets(Map.of("shadowHardwareFiltering", flag(true)), Map.of(), false);
		String text = "uniform sampler2D shadowtex0;\nuniform sampler2D shadowtex1;\nuniform sampler2D shadow;\n"
			+ "uniform sampler2D shadowcolor0;\nuniform sampler2DShadow shadowtex1HW;\nuniform sampler2D colortex0;\n";
		assertEquals("uniform sampler2DShadow shadowtex0;\nuniform sampler2DShadow shadowtex1;\n"
			+ "uniform sampler2DShadow shadow;\nuniform sampler2D shadowcolor0;\n"
			+ "uniform sampler2DShadow shadowtex1HW;\nuniform sampler2D colortex0;\n", t.rewriteDeclarations(text));
	}

	@Test
	void theDeclaredTypeStandsWhenHardwareFilteringIsOff() {
		String text = "uniform sampler2D shadowtex0;\n";
		assertEquals(text, targets(Map.of("shadowHardwareFiltering", flag(false)), Map.of(), false)
			.rewriteDeclarations(text));
		assertEquals(text, targets(Map.of(), Map.of(), false).rewriteDeclarations(text));
	}

	@Test
	void stageLocalHardwareFilteringFalseAndPlainSamplerHelperKeepSampler2D() {
		ShadowTargets t = targets(Map.of("shadowHardwareFiltering", flag(true)), Map.of(), false);
		String stageOff = "const bool shadowHardwareFiltering = false;\nuniform sampler2D shadowtex0;\n";
		assertEquals(stageOff, t.rewriteDeclarations(stageOff), "stage-local false must keep sampler2D");

		String helperPass = """
			uniform sampler2D shadowtex1;
			float sampleDepth(sampler2D tex, ivec2 p) { return texelFetch(tex, p, 0).x; }
			void main() { float d = sampleDepth(shadowtex1, ivec2(0)); }
			""";
		assertEquals(helperPass, t.rewriteDeclarations(helperPass), "passing to sampler2D helper must keep sampler2D");

		String unrelatedHelper = """
			uniform sampler2D shadowtex0;
			float other(sampler2D shadowtex0, float depth);
			float other(sampler2D tex, float depth) { return texture(tex, vec2(depth)).x; }
			void main() { float a = other(colortex0, texture(shadowtex0, vec3(0.0)).x); }
			""";
		assertTrue(t.rewriteDeclarations(unrelatedHelper).contains("uniform sampler2DShadow shadowtex0;"),
			"unrelated sampler2D helper call or nested shadowtex0 in non-sampler arg must still allow shadowtex0 rewrite");

		String mixedHelper = """
			uniform sampler2D shadowtex0;
			uniform sampler2D shadowtex1;
			float sampleBoth(sampler2D raw, sampler2DShadow cmp, vec3 uv) {
				return texelFetch(raw, ivec2(uv.xy), 0).x + texture(cmp, uv);
			}
			void main() { float a = sampleBoth(shadowtex0, shadowtex1, vec3(0.0)); }
			""";
		String mixedOut = t.rewriteDeclarations(mixedHelper);
		assertTrue(mixedOut.contains("uniform sampler2D shadowtex0;"),
			"plain sampler2D slot of mixed helper must keep shadowtex0 as sampler2D");
		assertTrue(mixedOut.contains("uniform sampler2DShadow shadowtex1;"),
			"comparison sampler2DShadow slot of mixed helper must rewrite shadowtex1 to sampler2DShadow");

		String overloadedHelper = """
			uniform sampler2D shadowtex0;
			float sampleOne(sampler2D raw, vec3 uv) { return texelFetch(raw, ivec2(uv.xy), 0).x; }
			float sampleOne(sampler2DShadow cmp, vec3 uv) { return texture(cmp, uv); }
			void main() { float a = sampleOne(shadowtex0, vec3(0.0)); }
			""";
		assertTrue(t.rewriteDeclarations(overloadedHelper).contains("uniform sampler2DShadow shadowtex0;"),
			"overloaded helper with sampler2DShadow at same parameter index must allow shadowtex0 rewrite");
	}

	@Test
	void shadowDeclarationsAndTheWaterAliasAreRecognisedInStageText() {
		assertTrue(ShadowTargets.readsShadow("uniform sampler2D shadowtex1;"));
		assertTrue(ShadowTargets.readsShadow("uniform sampler2DShadow shadowtex0HW;"));
		assertTrue(ShadowTargets.readsShadow("uniform sampler2D shadowcolor0;"));
		assertFalse(ShadowTargets.readsShadow("uniform sampler2D colortex0;"));
		assertFalse(ShadowTargets.readsShadow("// no shadow sampler here"));
		assertTrue(ShadowTargets.readsWaterShadow("uniform sampler2D watershadow;"));
		assertTrue(ShadowTargets.readsWaterShadow("float x = waterShadow(uv);"));
		assertFalse(ShadowTargets.readsWaterShadow("uniform sampler2D shadowtex0;"));
	}

	@Test
	void colourClearsAndClearColoursFollowTheConstants() {
		ShadowTargets t = targets(Map.of("shadowcolor0Clear", flag(false),
			"shadowcolor0ClearColor", new ConstValue("vec4", List.of(0.5, 0.25, 0.125, 1.0))), Map.of(), false);
		assertFalse(t.clearsColor(0));
		assertTrue(t.clearsColor(1));
		assertArrayEquals(new float[] {0.5f, 0.25f, 0.125f, 1f}, t.clearColor(0));
		assertArrayEquals(new float[] {1f, 1f, 1f, 1f}, t.clearColor(1));
	}

	@Test
	void mipChainLengthIsTheFullChainOnlyWhenMipmapped() {
		assertEquals(11, ShadowTargets.mipLevels(1024, true));
		assertEquals(12, ShadowTargets.mipLevels(2048, true));
		assertEquals(1, ShadowTargets.mipLevels(1024, false));
	}

	@Test
	void mipStateIsStaleUntilFilledAndAgainAfterAChange() {
		ShadowTargets t = targets(Map.of(), Map.of(), false);
		int depth0 = ShadowTargets.keyOfDepth(0), color1 = ShadowTargets.keyOfColor(1);
		assertTrue(t.mipsStale(depth0));
		t.mipsFilled(depth0);
		assertFalse(t.mipsStale(depth0));
		t.changed();
		assertTrue(t.mipsStale(depth0));
		assertTrue(t.mipsStale(color1));
		t.mipsFilled(color1);
		assertFalse(t.mipsStale(color1));
		assertTrue(t.mipsStale(depth0));
	}

	@Test
	void keysAddressTheTexturesOfTheirNames() {
		FakeTextures textures = new FakeTextures();
		ShadowTargets t = targets(Map.of(), Map.of(), false);
		assertTrue(t.create(textures));
		assertEquals(t.texture("shadowtex1"), t.textureOf(ShadowTargets.keyOfDepth(1)));
		assertEquals(t.texture("shadowcolor1"), t.textureOf(ShadowTargets.keyOfColor(1)));
		assertEquals(t.mipmappedColor(0), t.mipmapped(ShadowTargets.keyOfColor(0)));
	}

	@Test
	void createMakesDepthMapsAndColourBuffersAtTheDeclaredSizeAndFormats() {
		FakeTextures textures = new FakeTextures();
		TargetUnion union = new TargetUnion(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(0, "RGBA16F"),
			Map.of("shadowMapResolution", new ConstValue("int", List.of(512.0)),
				"shadowtex0Mipmap", flag(true)), List.of());
		ShadowTargets t = new ShadowTargets(union, false, true);
		assertTrue(t.create(textures));
		assertEquals(4, textures.created.size());
		long[] depth0 = textures.created.get(0);
		assertEquals(TargetFormats.DEPTH32_FLOAT, depth0[1]);
		assertEquals(512, depth0[2]);
		assertEquals(1, depth0[4], "a depth map keeps one level: Metal cannot mip a depth format (E5 review item 10)");
		assertEquals(1, textures.created.get(1)[4]);
		assertEquals(TargetFormats.of("RGBA16F", 0).format(), textures.created.get(2)[1]);
		assertEquals(t.depth(0), depth0[0]);
		assertEquals(t.color(0), textures.created.get(2)[0]);
		assertEquals(t.texture("shadowtex1"), t.depth(1));
		assertEquals(t.texture("shadowcolor1"), t.color(1));
		assertEquals(0, t.texture("shadowcolor5"));

		t.release();
		assertEquals(4, textures.released.size());
		assertEquals(0, t.depth(0));
		assertEquals(0, t.color(1));
	}

	@Test
	void aRefusedTextureReleasesEverythingItMadeSoFar() {
		FakeTextures textures = new FakeTextures();
		textures.refuseAt = 3;
		ShadowTargets t = new ShadowTargets(union(Map.of(), Map.of()), false, true);
		assertFalse(t.create(textures));
		assertEquals(3, textures.released.size());
		assertEquals(0, t.depth(0));
		assertEquals(0, t.depth(1));
		assertEquals(0, t.color(0));
	}

	@Test
	void releaseIsIdempotent() {
		FakeTextures textures = new FakeTextures();
		ShadowTargets t = new ShadowTargets(union(new HashMap<>(), Map.of()), false, true);
		t.create(textures);
		t.release();
		t.release();
		assertEquals(4, textures.released.size());
	}
}
