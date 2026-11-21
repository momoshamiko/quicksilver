// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.runtime.gbuffers.GbufferPhase;
import dev.mcms.shaders.runtime.gbuffers.PhaseTargets;
import dev.mcms.shaders.runtime.uniforms.CustomUniforms;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The mip chain of a colour target follows Iris's rule (engine-pack-format.md section 7; measured 2026-11-15 with the
 * mcms-mips test pack): one program that declares {@code <target>MipmapEnabled} allocates the chain, every program that
 * samples the target reads its levels through the mip filter, and the chain is regenerated before a reader after each write.
 * The tests below check that a declaring and a non-declaring program both read level 2 of a one-texel checkerboard as its
 * 4 by 4 average (128), that a non-declaring reader sees the chain of a later rewrite (a solid 0.25, 64), that the phase
 * boundary and a compute kernel refill the chain before they read it.
 */
@Tag("gpu")
final class MipmapDeclarationGpuTest {

	private static final int W = 64, H = 64, RGBA8 = 70;
	private static final int RT_READ = ShaderNative.USAGE_SHADER_READ | ShaderNative.USAGE_RENDER_TARGET;

	private static final String VERTEX = """
		#version 120
		void main() {
			gl_Position = ftransform();
		}
		""";

	private static final String CHECKER = """
		#version 120
		/* DRAWBUFFERS:3 */
		void main() {
			float c = mod(floor(gl_FragCoord.x) + floor(gl_FragCoord.y), 2.0);
			gl_FragData[0] = vec4(c, c, c, 1.0);
		}
		""";

	private static final String DECLARED = """
		#version 120
		const bool colortex3MipmapEnabled = true;
		/* DRAWBUFFERS:1 */
		uniform sampler2D colortex3;
		void main() {
			vec2 uv = gl_FragCoord.xy / vec2(64.0);
			gl_FragData[0] = vec4(texture2DLod(colortex3, uv, 2.0).rgb, 1.0);
		}
		""";

	private static final String UNDECLARED = """
		#version 120
		/* DRAWBUFFERS:2 */
		uniform sampler2D colortex3;
		void main() {
			vec2 uv = gl_FragCoord.xy / vec2(64.0);
			gl_FragData[0] = vec4(texture2DLod(colortex3, uv, 2.0).rgb, 1.0);
		}
		""";

	private static final String SOLID = """
		#version 120
		/* DRAWBUFFERS:3 */
		void main() {
			gl_FragData[0] = vec4(0.25);
		}
		""";

	private static final String AFTER_REWRITE = """
		#version 120
		/* DRAWBUFFERS:5 */
		uniform sampler2D colortex3;
		void main() {
			vec2 uv = gl_FragCoord.xy / vec2(64.0);
			gl_FragData[0] = vec4(texture2DLod(colortex3, uv, 2.0).rgb, 1.0);
		}
		""";

	/**
	 * Shows colortex1. It also samples the targets the tests read back (colortex2, 4 and 5) at zero weight: a target no
	 * program reads is dead, and a pass that writes only dead targets does not run (E9, DimensionSet#deadTargets).
	 */
	private static final String FINAL = """
		#version 120
		uniform sampler2D colortex1;
		uniform sampler2D colortex2;
		uniform sampler2D colortex4;
		uniform sampler2D colortex5;
		void main() {
			vec2 uv = gl_FragCoord.xy / vec2(64.0);
			gl_FragColor = texture2D(colortex1, uv)
				+ 0.0 * (texture2D(colortex2, uv) + texture2D(colortex4, uv) + texture2D(colortex5, uv));
		}
		""";
	/** Four pixels per texel: the implicit derivative level is 2 (the checkerboard's chain is 0.5 there, its level 0 is 0 or 1). */
	private static final String DECLARED_IMPLICIT = """
		#version 120
		const bool colortex3MipmapEnabled = true;
		/* DRAWBUFFERS:1 */
		uniform sampler2D colortex3;
		void main() {
			vec2 uv = (mod(gl_FragCoord.xy, 16.0) * 4.0 + 0.5) / 64.0;
			gl_FragData[0] = vec4(texture2D(colortex3, uv).rgb, 1.0);
		}
		""";

	private static final String UNDECLARED_IMPLICIT = """
		#version 120
		/* DRAWBUFFERS:2 */
		uniform sampler2D colortex3;
		void main() {
			vec2 uv = (mod(gl_FragCoord.xy, 16.0) * 4.0 + 0.5) / 64.0;
			gl_FragData[0] = vec4(texture2D(colortex3, uv).rgb, 1.0);
		}
		""";

	private static final String UNDECLARED_GRADIENT = """
		#version 130
		/* DRAWBUFFERS:4 */
		uniform sampler2D colortex3;
		void main() {
			vec2 uv = (mod(gl_FragCoord.xy, 16.0) * 4.0 + 0.5) / 64.0;
			gl_FragData[0] = vec4(textureGrad(colortex3, uv, vec2(4.0 / 64.0, 0.0), vec2(0.0, 4.0 / 64.0)).rgb, 1.0);
		}
		""";


	/** The checkerboard of composite1 in the kernel test; it declares colortex3's chain, which the kernel's reads sample. */
	private static final String DECLARING_CHECKER = """
		#version 120
		const bool colortex3MipmapEnabled = true;
		/* DRAWBUFFERS:3 */
		void main() {
			float c = mod(floor(gl_FragCoord.x) + floor(gl_FragCoord.y), 2.0);
			gl_FragData[0] = vec4(c, c, c, 1.0);
		}
		""";

	/** A compute kernel that reads colortex3's level 2 and stores it into colortex4 (the kernels' refill; plan.md E6). */
	private static final String KERNEL = """
		#version 430
		layout(local_size_x = 1, local_size_y = 1) in;
		const ivec3 workGroups = ivec3(64, 64, 1);
		layout(rgba8) uniform image2D colorimg4;
		uniform sampler2D colortex3;
		void main() {
			vec2 uv = (vec2(gl_GlobalInvocationID.xy) + 0.5) / vec2(64.0);
			float v = textureLod(colortex3, uv, 2.0).r;
			imageStore(colorimg4, ivec2(gl_GlobalInvocationID.xy), vec4(v, v, v, 1.0));
		}
		""";

	@TempDir
	Path dir;

	@Test
	void aDeclaringAndANonDecaringProgramReadTheChainAndANonDeclaringReaderSeesARewrite() throws Exception {
		PackModel model = load("rewrite",
			"composite1.vsh", VERTEX, "composite1.fsh", CHECKER,
			"composite2.vsh", VERTEX, "composite2.fsh", DECLARED,
			"composite3.vsh", VERTEX, "composite3.fsh", UNDECLARED,
			"composite4.vsh", VERTEX, "composite4.fsh", SOLID,
			"composite5.vsh", VERTEX, "composite5.fsh", AFTER_REWRITE,
			"final.vsh", VERTEX, "final.fsh", FINAL);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context(), enc = gpu.encoder();
			DimensionSet set = DimensionSet.load(ctx, "mip-declaration", model, model.rootFolder(), CustomUniforms.of(model),
				RGBA8, model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
			assertEquals(0, set.absent(), "the programs must compile: " + set.notes());
			Targets targets = targetsOf(ctx, set, model);
			long world = ShaderNative.textureNew(ctx, RGBA8, ShaderNative.TYPE_2D, W, H, 1, 1, RT_READ,
				ShaderNative.STORAGE_PRIVATE);
			EngineFrame.Main main = new EngineFrame.Main();
			main.set(world, RGBA8, W, H, null);
			EngineFrame frame = frame(set, targets, gbuffersWithReads(new int[][] {{}, {}, {}}));
			try {
				assertTrue(targets.mipmapped(3), "the chain is allocated: composite2 declares it");
				frame.clearHook(enc, null, targets, passBlocks());
				frame.compositeHook(enc, null, targets, main, passBlocks());
				MetalBridge.commitAndWait(enc);
				assertTexels(gpu, targets.read(1), 0, W, 128, "the declaring composite2 reads the level 2 average");
				assertTexels(gpu, targets.read(2), 0, W, 128, "the non-declaring composite3 reads the same chain (Iris's rule)");
				assertTexels(gpu, targets.read(5), 0, W, 64, "the non-declaring composite5 reads the chain of the 0.25 rewrite");
			} finally {
				frame.close();
				targets.release();
				MetalBridge.release(world);
			}
		}
	}

	@Test
	void aPhaseBoundaryRefillsTheChainOfItsReadersBeforeTheirDraws() throws Exception {
		PackModel model = load("phase",
			"composite1.vsh", VERTEX, "composite1.fsh", CHECKER,
			"composite2.vsh", VERTEX, "composite2.fsh", DECLARED,
			"final.vsh", VERTEX, "final.fsh", FINAL);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context(), enc = gpu.encoder();
			DimensionSet set = DimensionSet.load(ctx, "mip-phase", model, model.rootFolder(), CustomUniforms.of(model),
				RGBA8, model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
			assertEquals(0, set.absent(), "the programs must compile: " + set.notes());
			Targets targets = targetsOf(ctx, set, model);
			// colortex3's read texture holds the checkerboard, put there the way a copy puts it (the chain is not filled).
			byte[] checker = new byte[W * H * 4];
			for (int y = 0; y < H; y++) {
				for (int x = 0; x < W; x++) {
					byte v = (byte) (((x + y) & 1) == 0 ? 0 : 255);
					for (int c = 0; c < 3; c++) {
						checker[(y * W + x) * 4 + c] = v;
					}
					checker[(y * W + x) * 4 + 3] = (byte) 255;
				}
			}
			assertTrue(ShaderNative.textureUpload(ctx, targets.read(3), checker, (long) W * 4, 0, 0, 0, 0, 0, 0, W, H, 1));
			targets.bump(3);
			EngineFrame frame = frame(set, targets, gbuffersWithReads(new int[][] {{}, {3}, {}}));
			try {
				assertTrue(targets.mipsStale(3), "the copied content has no chain yet");
				frame.opaqueHook(enc, targets);
				MetalBridge.commitAndWait(enc);
				assertFalse(targets.mipsStale(3), "the opaque phase's boundary filled the chain its programs read");
				assertTexels(gpu, targets.read(3), 2, W / 4, 128, "level 2 of the refilled chain is the checkerboard's average");
			} finally {
				frame.close();
				targets.release();
			}
		}
	}

	@Test
	void aComputeKernelReadsTheCurrentChainOfAWrittenTarget() throws Exception {
		PackModel model = load("kernel",
			"composite1.vsh", VERTEX, "composite1.fsh", DECLARING_CHECKER,
			"composite2.csh", KERNEL,
			"final.vsh", VERTEX, "final.fsh", FINAL);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context(), enc = gpu.encoder();
			DimensionSet set = DimensionSet.load(ctx, "mip-kernel", model, model.rootFolder(), CustomUniforms.of(model),
				RGBA8, model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
			assertEquals(0, set.absent(), "the programs must compile: " + set.notes());
			Targets targets = targetsOf(ctx, set, model);
			long world = ShaderNative.textureNew(ctx, RGBA8, ShaderNative.TYPE_2D, W, H, 1, 1, RT_READ,
				ShaderNative.STORAGE_PRIVATE);
			EngineFrame.Main main = new EngineFrame.Main();
			main.set(world, RGBA8, W, H, null);
			EngineFrame frame = frame(set, targets, gbuffersWithReads(new int[][] {{}, {}, {}}));
			try {
				frame.compositeHook(enc, null, targets, main, passBlocks());
				MetalBridge.commitAndWait(enc);
				assertTexels(gpu, targets.read(4), 0, W, 128, "the kernel read the level 2 average of the checkerboard it was written");
			} finally {
				frame.close();
				targets.release();
				MetalBridge.release(world);
			}
		}
	}

	/** A kernel that samples a custom image (image.cimg, sampler csampler) next to colortex3's chain (preview 13's crash). */
	private static final String KERNEL_WITH_IMAGE = """
		#version 430
		layout(local_size_x = 1, local_size_y = 1) in;
		const ivec3 workGroups = ivec3(64, 64, 1);
		layout(rgba8) uniform image2D colorimg4;
		uniform sampler2D colortex3;
		uniform sampler2D csampler;
		void main() {
			vec2 uv = (vec2(gl_GlobalInvocationID.xy) + 0.5) / vec2(64.0);
			float v = textureLod(colortex3, uv, 2.0).r + texelFetch(csampler, ivec2(0, 0), 0).r;
			imageStore(colorimg4, ivec2(gl_GlobalInvocationID.xy), vec4(v, v, v, 1.0));
		}
		""";

	/**
	 * Preview 13 (2026-11-15) crashed Complementary's VERY_HIGH set at load: a kernel that sampled a custom image indexed the
	 * colour-target table with the image's source (IMAGE_CUSTOM + index, 300) in the kernel's mip bookkeeping. The custom image
	 * is cleared each frame, so the kernel stores the chain's level 2 average alone.
	 */
	@Test
	void aKernelThatSamplesACustomImageNextToAMippedTargetBuildsAndRuns() throws Exception {
		PackModel model = loadWith("kernel-image", "image.cimg = csampler rgba rgba8 unsigned_byte true false 4 4\n",
			"composite1.vsh", VERTEX, "composite1.fsh", DECLARING_CHECKER,
			"composite2.csh", KERNEL_WITH_IMAGE,
			"final.vsh", VERTEX, "final.fsh", FINAL);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context(), enc = gpu.encoder();
			DimensionSet set = DimensionSet.load(ctx, "mip-kernel-image", model, model.rootFolder(), CustomUniforms.of(model),
				RGBA8, model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
			assertEquals(0, set.absent(), "the programs must compile: " + set.notes());
			Targets targets = targetsOf(ctx, set, model);
			long world = ShaderNative.textureNew(ctx, RGBA8, ShaderNative.TYPE_2D, W, H, 1, 1, RT_READ,
				ShaderNative.STORAGE_PRIVATE);
			EngineFrame.Main main = new EngineFrame.Main();
			main.set(world, RGBA8, W, H, null);
			EngineFrame frame = frame(set, targets, gbuffersWithReads(new int[][] {{}, {}, {}}));
			try {
				frame.clearHook(enc, null, targets, passBlocks());
				frame.compositeHook(enc, null, targets, main, passBlocks());
				MetalBridge.commitAndWait(enc);
				assertTexels(gpu, targets.read(4), 0, W, 128, "the kernel read the chain and the zeroed custom image");
			} finally {
				frame.close();
				targets.release();
				MetalBridge.release(world);
			}
		}
	}

	/**
	 * The hybrid mip rule (E6, 2026-11-15; a deviation from Iris recorded in engine-pack-format.md section 7): the lookups of a
	 * program that does not declare a mipped target read level 0 when they have no explicit level or gradient (their implicit
	 * derivative level would otherwise read the chain at a coarse level on a divergent path), and read the chain when they have
	 * an explicit gradient. The declaring program's implicit lookups read the chain. Four pixels per texel gives level 2.
	 */
	@Test
	void aNonDeclaringProgramsImplicitLookupsReadLevelZeroAndItsGradientLookupsReadTheChain() throws Exception {
		PackModel model = load("implicit",
			"composite1.vsh", VERTEX, "composite1.fsh", CHECKER,
			"composite2.vsh", VERTEX, "composite2.fsh", DECLARED_IMPLICIT,
			"composite3.vsh", VERTEX, "composite3.fsh", UNDECLARED_IMPLICIT,
			"composite4.vsh", VERTEX, "composite4.fsh", UNDECLARED_GRADIENT,
			"final.vsh", VERTEX, "final.fsh", FINAL);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context(), enc = gpu.encoder();
			DimensionSet set = DimensionSet.load(ctx, "mip-implicit", model, model.rootFolder(), CustomUniforms.of(model),
				RGBA8, model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
			assertEquals(0, set.absent(), "the programs must compile: " + set.notes());
			Targets targets = targetsOf(ctx, set, model);
			long world = ShaderNative.textureNew(ctx, RGBA8, ShaderNative.TYPE_2D, W, H, 1, 1, RT_READ,
				ShaderNative.STORAGE_PRIVATE);
			EngineFrame.Main main = new EngineFrame.Main();
			main.set(world, RGBA8, W, H, null);
			EngineFrame frame = frame(set, targets, gbuffersWithReads(new int[][] {{}, {}, {}}));
			try {
				frame.compositeHook(enc, null, targets, main, passBlocks());
				MetalBridge.commitAndWait(enc);
				assertTexels(gpu, targets.read(1), 0, W, 128, "the declaring program's implicit lookup reads the chain (level 2)");
				assertTexels(gpu, targets.read(2), 0, W, 0, "the non-declaring program's implicit lookup reads level 0");
				assertTexels(gpu, targets.read(4), 0, W, 128, "the non-declaring program's textureGrad reads the chain (level 2)");
			} finally {
				frame.close();
				targets.release();
				MetalBridge.release(world);
			}
		}
	}

	/** A helper that looks up its sampler parameter with an implicit level; a second helper passes the parameter on. */
	private static final String TAP_IMPLICIT = """
		#version 120
		/* DRAWBUFFERS:2 */
		uniform sampler2D colortex3;
		vec4 tap(sampler2D s, vec2 uv) {
			return texture2D(s, uv);
		}
		vec4 outer(sampler2D s, vec2 uv) {
			return tap(s, uv);
		}
		void main() {
			vec2 uv = (mod(gl_FragCoord.xy, 16.0) * 4.0 + 0.5) / 64.0;
			gl_FragData[0] = outer(colortex3, uv);
		}
		""";

	/** The same kind of helper with an explicit level: it reads the chain in a program that does not declare the target. */
	private static final String TAP_EXPLICIT = """
		#version 130
		/* DRAWBUFFERS:4 */
		uniform sampler2D colortex3;
		vec4 tap(sampler2D s, vec2 uv) {
			return textureLod(s, uv, 2.0);
		}
		void main() {
			vec2 uv = (mod(gl_FragCoord.xy, 16.0) * 4.0 + 0.5) / 64.0;
			gl_FragData[0] = tap(colortex3, uv);
		}
		""";

	/**
	 * Declares colortex7's chain and writes colortex8. A target's chain exists only below the set's target count (the highest
	 * used index plus one), so a used target above 7 is needed for colortex7 to carry its flag; no program uses colortex7.
	 */
	private static final String DECLARES_UNUSED = """
		#version 120
		const bool colortex7MipmapEnabled = true;
		/* DRAWBUFFERS:8 */
		void main() {
			gl_FragData[0] = vec4(0.5);
		}
		""";

	/** A shadow program that names colortex7, which no compiled pass or gbuffers program uses (so it has no texture). */
	private static final String SHADOW_NAMES_UNUSED = """
		#version 120
		/* DRAWBUFFERS:0 */
		uniform sampler2D colortex7;
		void main() {
			gl_FragData[0] = texture2D(colortex7, gl_FragCoord.xy / vec2(64.0));
		}
		""";

	/**
	 * A sampler parameter carries the implicit-LOD sampler of its argument (E6-fix-F, review item 1): in a program that does
	 * not declare colortex3, the texture2D in a helper reads level 0 (through a second helper too), and the textureLod in a
	 * helper reads the chain (level 2, 128). The declaring program's implicit lookup reads the chain, which proves the chain is
	 * there.
	 */
	@Test
	void aHelpersSamplerParameterReadsLevelZeroForImplicitLookupsAndTheChainForExplicitLevels() throws Exception {
		PackModel model = load("helper",
			"composite1.vsh", VERTEX, "composite1.fsh", CHECKER,
			"composite2.vsh", VERTEX, "composite2.fsh", DECLARED_IMPLICIT,
			"composite3.vsh", VERTEX, "composite3.fsh", TAP_IMPLICIT,
			"composite4.vsh", VERTEX, "composite4.fsh", TAP_EXPLICIT,
			"final.vsh", VERTEX, "final.fsh", FINAL);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context(), enc = gpu.encoder();
			DimensionSet set = DimensionSet.load(ctx, "mip-helper", model, model.rootFolder(), CustomUniforms.of(model),
				RGBA8, model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
			assertEquals(0, set.absent(), "the programs must compile: " + set.notes());
			Targets targets = targetsOf(ctx, set, model);
			long world = ShaderNative.textureNew(ctx, RGBA8, ShaderNative.TYPE_2D, W, H, 1, 1, RT_READ,
				ShaderNative.STORAGE_PRIVATE);
			EngineFrame.Main main = new EngineFrame.Main();
			main.set(world, RGBA8, W, H, null);
			EngineFrame frame = frame(set, targets, gbuffersWithReads(new int[][] {{}, {}, {}}));
			try {
				frame.compositeHook(enc, null, targets, main, passBlocks());
				MetalBridge.commitAndWait(enc);
				assertTexels(gpu, targets.read(1), 0, W, 128, "the declaring program's implicit lookup reads the chain (level 2)");
				assertTexels(gpu, targets.read(2), 0, W, 0, "a helper's texture2D through its sampler parameter reads level 0");
				assertTexels(gpu, targets.read(4), 0, W, 128, "a helper's textureLod through its sampler parameter reads the chain");
			} finally {
				frame.close();
				targets.release();
				MetalBridge.release(world);
			}
		}
	}

	/**
	 * Mellow 3.4.1a's bloom shape (composite2's blur3x3(colortex0, texcoord) with colortex0MipmapEnabled): a program that
	 * declares colortex3's chain reads it only through a helper's sampler parameter with an implicit level. The call passes
	 * the declared chain's mip-filtered sampler as the parameter's implicit sampler, so the lookup reads level 2.
	 */
	private static final String DECLARED_TAP_IMPLICIT = """
		#version 120
		const bool colortex3MipmapEnabled = true;
		/* DRAWBUFFERS:1 */
		uniform sampler2D colortex3;
		vec4 tap(sampler2D s, vec2 uv) {
			return texture2D(s, uv);
		}
		void main() {
			vec2 uv = (mod(gl_FragCoord.xy, 16.0) * 4.0 + 0.5) / 64.0;
			gl_FragData[0] = vec4(tap(colortex3, uv).rgb, 1.0);
		}
		""";

	/**
	 * E9 (review of the branch, P1): the chain is refilled before a declaring program whose only read of it is an implicit
	 * lookup through a helper's sampler parameter. The reflection marked no level read there, so the chain was never filled
	 * and the helper read an empty level 2.
	 */
	@Test
	void aDeclaringProgramThatReadsTheChainOnlyThroughAHelperGetsItRefilled() throws Exception {
		PackModel model = load("declared-helper",
			"composite1.vsh", VERTEX, "composite1.fsh", CHECKER,
			"composite2.vsh", VERTEX, "composite2.fsh", DECLARED_TAP_IMPLICIT,
			"final.vsh", VERTEX, "final.fsh", FINAL);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context(), enc = gpu.encoder();
			DimensionSet set = DimensionSet.load(ctx, "mip-declared-helper", model, model.rootFolder(),
				CustomUniforms.of(model), RGBA8, model.rootFolder().union().colorFormats(), Engine.gamePipelines(),
				Engine.COPIES_NOT_COMPILED);
			assertEquals(0, set.absent(), "the programs must compile: " + set.notes());
			Targets targets = targetsOf(ctx, set, model);
			long world = ShaderNative.textureNew(ctx, RGBA8, ShaderNative.TYPE_2D, W, H, 1, 1, RT_READ,
				ShaderNative.STORAGE_PRIVATE);
			EngineFrame.Main main = new EngineFrame.Main();
			main.set(world, RGBA8, W, H, null);
			EngineFrame frame = frame(set, targets, gbuffersWithReads(new int[][] {{}, {}, {}}));
			try {
				assertTrue(targets.mipmapped(3), "the chain is allocated: composite2 declares it");
				frame.compositeHook(enc, null, targets, main, passBlocks());
				MetalBridge.commitAndWait(enc);
				assertTexels(gpu, targets.read(1), 0, W, 128, "the helper's implicit lookup reads the refilled chain (level 2)");
			} finally {
				frame.close();
				targets.release();
				MetalBridge.release(world);
			}
		}
	}

	/**
	 * A shadow program names a mipped target that no compiled pass or gbuffers program uses (E6-fix-F, review item 2): the
	 * target holds no texture, so the frame head must not fill its chain (generateMipmaps with a zero texture). The chain stays
	 * stale, and the head returns.
	 */
	@Test
	void aShadowProgramsMippedTargetWithNoTextureIsNotFilledAtTheFrameHead() throws Exception {
		PackModel model = load("shadow-unused",
			"composite1.vsh", VERTEX, "composite1.fsh", DECLARES_UNUSED,
			"shadow.vsh", VERTEX, "shadow.fsh", SHADOW_NAMES_UNUSED,
			"final.vsh", VERTEX, "final.fsh", FINAL);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context(), enc = gpu.encoder();
			DimensionSet set = DimensionSet.load(ctx, "mip-shadow-unused", model, model.rootFolder(), CustomUniforms.of(model),
				RGBA8, model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
			assertEquals(0, set.absent(), "the programs must compile: " + set.notes());
			Targets targets = targetsOf(ctx, set, model);
			assertTrue(targets.mipmapped(7), "colortex7's chain is allocated: composite1 declares it");
			assertEquals(0L, targets.read(7), "no program uses colortex7, so it has no texture");
			EngineFrame frame = frame(set, targets, gbuffersWithReads(new int[][] {{}, {}, {}}));
			try {
				frame.frameHead(enc, targets, passBlocks());
				MetalBridge.commitAndWait(enc);
				assertTrue(targets.mipsStale(7), "the frame head filled no chain of a target with no texture");
			} finally {
				frame.close();
				targets.release();
			}
		}
	}

	/** The first kernel of composite2 stores a solid 0.25 into colortex3 through colorimg3 (E6-fix-F: its own store). */
	private static final String KERNEL_STORE = """
		#version 430
		layout(local_size_x = 1, local_size_y = 1) in;
		const ivec3 workGroups = ivec3(64, 64, 1);
		layout(rgba8) uniform image2D colorimg3;
		void main() {
			imageStore(colorimg3, ivec2(gl_GlobalInvocationID.xy), vec4(0.25));
		}
		""";

	/** The second kernel (composite2_a) reads colortex3's level 2 after the first kernel's store; it stores that into colortex4. */
	private static final String KERNEL_READ_AFTER_STORE = """
		#version 430
		layout(local_size_x = 1, local_size_y = 1) in;
		const ivec3 workGroups = ivec3(64, 64, 1);
		layout(rgba8) uniform image2D colorimg4;
		uniform sampler2D colortex3;
		void main() {
			vec2 uv = (vec2(gl_GlobalInvocationID.xy) + 0.5) / vec2(64.0);
			float v = textureLod(colortex3, uv, 2.0).r;
			imageStore(colorimg4, ivec2(gl_GlobalInvocationID.xy), vec4(v, v, v, 1.0));
		}
		""";

	/**
	 * The chain is refilled before each kernel, not once per pass (E6-fix-F, review item 3): kernel B of a pass reads the chain of
	 * the store kernel A made through colorimg3. The checkerboard's chain is 128 at level 2; after the solid store it is 64 (0.25).
	 */
	@Test
	void aKernelReadsTheChainOfAnEarlierKernelsStoreInTheSamePass() throws Exception {
		PackModel model = load("kernel-chain",
			"composite1.vsh", VERTEX, "composite1.fsh", DECLARING_CHECKER,
			"composite2.csh", KERNEL_STORE,
			"composite2_a.csh", KERNEL_READ_AFTER_STORE,
			"final.vsh", VERTEX, "final.fsh", FINAL);
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context(), enc = gpu.encoder();
			DimensionSet set = DimensionSet.load(ctx, "mip-kernel-chain", model, model.rootFolder(), CustomUniforms.of(model),
				RGBA8, model.rootFolder().union().colorFormats(), Engine.gamePipelines(), Engine.COPIES_NOT_COMPILED);
			assertEquals(0, set.absent(), "the programs must compile: " + set.notes());
			Targets targets = targetsOf(ctx, set, model);
			long world = ShaderNative.textureNew(ctx, RGBA8, ShaderNative.TYPE_2D, W, H, 1, 1, RT_READ,
				ShaderNative.STORAGE_PRIVATE);
			EngineFrame.Main main = new EngineFrame.Main();
			main.set(world, RGBA8, W, H, null);
			EngineFrame frame = frame(set, targets, gbuffersWithReads(new int[][] {{}, {}, {}}));
			try {
				frame.compositeHook(enc, null, targets, main, passBlocks());
				MetalBridge.commitAndWait(enc);
				assertTexels(gpu, targets.read(3), 0, W, 64, "the first kernel stored the solid 0.25 at level 0");
				assertTexels(gpu, targets.read(4), 0, W, 64,
					"the second kernel read the chain of the first kernel's store (level 2 of 0.25), not the checkerboard's 128");
			} finally {
				frame.close();
				targets.release();
				MetalBridge.release(world);
			}
		}
	}

	/** Writes the pack's files under a fresh folder and loads its model; the arguments are file name and text pairs. */
	private PackModel load(String name, String... pairs) throws Exception {
		return loadWith(name, "", pairs);
	}

	/** As {@link #load}, with the pack's shaders.properties text. */
	private PackModel loadWith(String name, String properties, String... pairs) throws Exception {
		Path shaders = dir.resolve(name).resolve("shaders");
		Files.createDirectories(shaders);
		Files.writeString(shaders.resolve("shaders.properties"), properties);
		for (int i = 0; i < pairs.length; i += 2) {
			Files.writeString(shaders.resolve(pairs[i]), pairs[i + 1]);
		}
		PackModel model;
		try (PackSource source = PackSource.open(dir.resolve(name))) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
		assertTrue(model.problems().isEmpty(), model.problems().toString());
		return model;
	}

	private static Targets targetsOf(long ctx, DimensionSet set, PackModel model) {
		Targets targets = new Targets(set.dimension().union(), model.properties().bufferSizes(), set.usedTargets(),
			set.imageTargets(), false, false, W, H, Targets.engineTextures(ctx), null,
			CustomImages.create(ctx, model.properties().images(), W, H));
		assertTrue(targets.create());
		return targets;
	}

	private static EngineFrame frame(DimensionSet set, Targets targets, EngineFrame.Gbuffers gbuffers) {
		return new EngineFrame(set, targets, null, 0L, 0, 0, (bytes, out) -> { }, Map.of(), Map.of(), gbuffers);
	}

	/** The read sets of the sky, opaque and translucent phases (in that order), over single-group layouts with no draws. */
	private static EngineFrame.Gbuffers gbuffersWithReads(int[][] reads) {
		int[][] byPhase = new int[GbufferPhase.values().length][0];
		byPhase[GbufferPhase.SKY.ordinal()] = reads[0];
		byPhase[GbufferPhase.OPAQUE.ordinal()] = reads[1];
		byPhase[GbufferPhase.TRANSLUCENT.ordinal()] = reads[2];
		return new EngineFrame.Gbuffers(PhaseTargets.single(new int[0]), PhaseTargets.single(new int[0]),
			PhaseTargets.single(new int[0]), false, phase -> { }, null, new int[0], new int[0], new int[0], byPhase, new int[0],
			new int[0]);
	}

	/**
	 * Asserts that every texel of a level of a colour target is {@code expected} within two code values (0 to 255). The
	 * level's size is {@code size} by {@code size}.
	 */
	private static void assertTexels(HeadlessGpu gpu, long texture, int mip, int size, int expected, String what) {
		byte[] texels = gpu.readTexture(texture, 0, mip, size, size, 1, 4);
		for (int i = 0; i < size * size; i++) {
			int value = texels[i * 4] & 0xff;
			assertTrue(Math.abs(value - expected) <= 2, what + ": texel " + i + " is " + value + ", expected " + expected);
		}
	}

	private static EngineFrame.PassBlocks passBlocks() {
		return new EngineFrame.PassBlocks() {
			@Override public long @Nullable [] frameBlock(String program) { return null; }
			@Override public long @Nullable [] customBlock() { return null; }
		};
	}
}
