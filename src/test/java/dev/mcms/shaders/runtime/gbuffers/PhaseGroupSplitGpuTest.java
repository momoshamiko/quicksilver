// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout.UniformDescription;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import dev.mcms.metal.BackendPassHarness;
import dev.mcms.metal.MetalBridge;
import dev.mcms.metal.MetalHooks;
import dev.mcms.metal.PassDelegate;
import dev.mcms.metal.PhaseSplitResources;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.metal.ShaderNative;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A gbuffers phase whose programs need two attachment groups (2026-11-15, the phase split): the pass opens on group 0, and
 * each draw whose program writes another group's targets splits the open pass onto that group through the backend. The
 * game-pipeline variant (no pack program) is compiled per group, so it draws in either group without changing the other
 * attachments. Checked on the GPU: each target's texels hold the last colour a draw wrote there, the targets one group does
 * not write carry over the splits, the pass keeps its depth attachment, vertex and index buffers across the splits (no
 * draw rebinds them), and the encoder trace names exactly the group switches and the depth each one reopens on.
 */
@Tag("gpu")
class PhaseGroupSplitGpuTest {

	@BeforeAll
	static void headless() {
		// MetalPipeline's cache directory asks Fabric for the game dir, which a headless test has none of.
		System.setProperty("mcms.metal.mslCache", "false");
	}

	private static final int W = 4, H = 4, TARGETS = 9;
	private static final int RGBA8 = 70;
	private static final GbufferPhase PHASE = GbufferPhase.TRANSLUCENT;
	private static final String PACK_A = "gbuffers_a", PACK_B = "gbuffers_b";
	/** The game pipelines of the synthetic phase: two pack programs and one game-pipeline variant (no pack program). */
	private static final String GAME_A = "minecraft:pipeline/groups_a", GAME_B = "minecraft:pipeline/groups_b",
		GAME_G = "minecraft:pipeline/groups_game";
	/**
	 * The draws in order. Draws 2, 5, 7 and 9 change the group (A, B, A, B), so the pass splits four times; draws 3, 4, 6 and 8
	 * keep the open group (a second A, the game variant in each group, and a B in the group it already has).
	 */
	private static final List<String> DRAWS = List.of(GAME_B, GAME_A, GAME_A, GAME_G, GAME_B, GAME_G, GAME_A, GAME_B, GAME_G);
	private static final float[] RED = {1, 0, 0, 1}, GREEN = {0, 1, 0, 1}, BLUE = {0, 0, 1, 1};
	/** A full-screen triangle's positions, and the indices that draw it (the draws read both from the buffers). */
	private static final float[] POSITIONS = {-1, -1, 3, -1, -1, 3};
	private static final int[] INDICES = {0, 1, 2};
	/**
	 * The game variant's uniforms: its tint (a uniform buffer at index 0) and its texel (a texture at index 1, sampled with
	 * the sampler at fragment slot 0: the game's reflection numbers samplers apart from textures).
	 */
	private static final List<UniformDescription> GAME_UNIFORMS = List.of(
		new UniformDescription("Tint", UniformType.UNIFORM_BUFFER),
		new UniformDescription("Tex", UniformType.COMBINED_IMAGE_SAMPLER));
	/** The game variant's sampler slots per uniform: none in the vertex stage, the texel's at fragment slot 0. */
	private static final int[] GAME_VERTEX_SAMPLERS = {-1, -1}, GAME_FRAGMENT_SAMPLERS = {-1, 0};

	/**
	 * A plan that serves one pack or game variant in the translucent phase of the entity domain, and nothing elsewhere; the
	 * game variant is given per attachment group.
	 */
	private record SyntheticPlan(String location, GbufferPlans.Variant variant, Map<Integer, GbufferPlans.GameVariant> games)
			implements GbufferPlans.Plan {
		@Override
		public GbufferPlans.@Nullable Variant variant(PassKind kind, GbufferPhase phase, GbufferMapping.Domain domain) {
			return kind == PassKind.GBUFFERS && phase == PHASE && domain == GbufferMapping.Domain.ENTITY ? this.variant : null;
		}

		@Override
		public GbufferPlans.@Nullable GameVariant gameVariant(PassKind kind, GbufferPhase phase, GbufferMapping.Domain domain,
				int group) {
			return kind == PassKind.GBUFFERS && phase == PHASE && domain == GbufferMapping.Domain.ENTITY
				? this.games.get(group) : null;
		}

		@Override
		public String reason(PassKind kind, GbufferPhase phase, GbufferMapping.Domain domain) {
			return "";
		}
	}

	/** The engine's frame as the delegate reads it: each attachment group's write handles, and nothing else. */
	private record Frame(long[][] groups) implements GbufferDelegate.Bindings {
		@Override
		public long @Nullable [] phaseColours(GbufferPhase phase, int group) {
			return phase == PHASE ? this.groups[group] : null;
		}

		@Override
		public long @Nullable [] frameBlock(String program) {
			return null;
		}

		@Override
		public long @Nullable [] customBlock() {
			return null;
		}

		@Override
		public long @Nullable [] block(String name) {
			return null;
		}

		@Override
		public long texture(String colortexName, boolean write) {
			return 0;
		}

		@Override
		public long depth(String name) {
			return 0;
		}

		@Override
		public long noise() {
			return 0;
		}

		@Override
		public long defaultTexture(String name) {
			return 0;
		}

		@Override
		public void projection(float[] out) {
			new Matrix4f().get(out);
		}
	}

	/** The synthetic phase on a context: its delegate, its colour textures per group, and the pipelines the draws set. */
	private record Scene(PhaseLayout.Groups groups, long[][] colours, GbufferDelegate delegate,
			Map<String, BackendRenderPipeline> pipelines) {
	}

	/** A render encoder the backend began: its trace label and its depth attachment. */
	private record Begin(String why, long depth) {
	}

	@Test
	void theDrawsAlternateBetweenTheGroupsAndEveryTargetHoldsItsLastWrite() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long[] textures = colourTextures(ctx);
			long depthTexture = PhaseSplitResources.depthTexture(ctx, W, H);
			Scene scene = scene(ctx, textures, depthTexture);
			CommandEncoderBackend encoder = (CommandEncoderBackend) BackendPassHarness.newEncoder(ctx);
			// The resources the draws read: the vertex positions and indices, the game variant's tint and texel.
			GpuBuffer vertices = PhaseSplitResources.buffer(encoder, ctx, floats(POSITIONS));
			GpuBuffer indices = PhaseSplitResources.buffer(encoder, ctx, ints(INDICES));
			GpuBuffer tint = PhaseSplitResources.buffer(encoder, ctx, floats(GREEN));
			TextureViewAndSampler texel = PhaseSplitResources.sampled(encoder, ctx, new byte[] {-1, -1, -1, -1});

			List<Begin> begins = new ArrayList<>();
			MetalHooks.encoderTrace = recorder(begins);
			RenderPassDescriptor descriptor = BackendPassHarness.passDescriptor(encoder, ctx, "phase groups", W, H);
			try {
				MetalHooks.setRedirector(opened -> new MetalHooks.Redirect(scene.colours()[0], null, W, H, depthTexture, 1f,
					scene.delegate()));
				RenderPassBackend pass = encoder.createRenderPass(descriptor);
				// Bound once: the group switches must keep them bound, since no draw below binds them again.
				pass.setVertexBuffer(0, vertices.slice(0, POSITIONS.length * 4L));
				pass.setIndexBuffer(indices, IndexType.INT);
				for (String name : DRAWS) {
					pass.setPipeline(scene.pipelines().get(name));
					if (name.equals(GAME_G)) {
						pass.setUniform(0, tint.slice(0, 16));
						pass.setUniform(1, texel);
					}
					pass.drawIndexed(INDICES.length, 1, 0, 0, 0);
				}
				encoder.submitRenderPass();
				encoder.submit();
			} finally {
				MetalHooks.setRedirector(null);
				MetalHooks.encoderTrace = null;
			}

			assertEquals(List.of(), scene.delegate().warnings(), "no draw was dropped");
			// The last draw is the game variant's: its texel's sampler binds at the copy's own fragment slot 0, and the vertex stage
			// samples none (the review of 2026-11-15: a game variant bound no sampler at all).
			assertEquals(0, scene.delegate().fragmentSamplerSlot(1), "the game variant's sampler binds at its fragment slot");
			assertEquals(-1, scene.delegate().vertexSamplerSlot(1), "and at no vertex slot");
			List<Begin> switches = groupBegins(begins);
			assertEquals(4, switches.size(), "the group changes split the pass four times, and no other draw does");
			for (Begin split : switches) {
				assertEquals(depthTexture, split.depth(), "a group switch reopens the pass on its depth attachment");
			}
			// The last writes: colortex0 from the game variant (draw 9), colortex1 to colortex7 from A (draw 7, carried over the
			// split back to B's group by draw 8), colortex8 from B (draw 8). Every target must hold those colours.
			for (int t = 0; t < TARGETS; t++) {
				float[] expected = t == 0 ? GREEN : t == 8 ? BLUE : RED;
				byte[] pixels = gpu.readTexture(textures[t], 0, 0, W, H, 1, 4);
				for (int y = 0; y < H; y++) {
					for (int x = 0; x < W; x++) {
						for (int c = 0; c < 4; c++) {
							assertEquals(Math.round(expected[c] * 255), channel(pixels, x, y, c),
								"colortex" + t + " channel " + c + " at " + x + "," + y);
						}
					}
				}
			}
			scene.delegate().close();
		}
	}

	/**
	 * A group switch binds the game's pipeline no more than the game sets it: the switch's split restores with reapply false, and
	 * the pack binds once after the split (MetalRenderPass.restoreAfterSplit). Counted at the delegate the pass calls.
	 */
	@Test
	void aGroupSwitchBindsEachPipelineOnceNotAgain() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long[] textures = colourTextures(ctx);
			long depthTexture = PhaseSplitResources.depthTexture(ctx, W, H);
			Scene scene = scene(ctx, textures, depthTexture);
			CommandEncoderBackend encoder = (CommandEncoderBackend) BackendPassHarness.newEncoder(ctx);
			GpuBuffer vertices = PhaseSplitResources.buffer(encoder, ctx, floats(POSITIONS));
			GpuBuffer indices = PhaseSplitResources.buffer(encoder, ctx, ints(INDICES));
			GpuBuffer tint = PhaseSplitResources.buffer(encoder, ctx, floats(GREEN));
			TextureViewAndSampler texel = PhaseSplitResources.sampled(encoder, ctx, new byte[] {-1, -1, -1, -1});

			List<Begin> begins = new ArrayList<>();
			AtomicInteger binds = new AtomicInteger();
			MetalHooks.encoderTrace = recorder(begins);
			RenderPassDescriptor descriptor = BackendPassHarness.passDescriptor(encoder, ctx, "phase group binds", W, H);
			try {
				PassDelegate counted = counting(scene.delegate(), binds);
				MetalHooks.setRedirector(opened -> new MetalHooks.Redirect(scene.colours()[0], null, W, H, depthTexture, 1f,
					counted));
				RenderPassBackend pass = encoder.createRenderPass(descriptor);
				pass.setVertexBuffer(0, vertices.slice(0, POSITIONS.length * 4L));
				pass.setIndexBuffer(indices, IndexType.INT);
				for (String name : DRAWS) {
					pass.setPipeline(scene.pipelines().get(name));
					if (name.equals(GAME_G)) {
						pass.setUniform(0, tint.slice(0, 16));
						pass.setUniform(1, texel);
					}
					pass.drawIndexed(INDICES.length, 1, 0, 0, 0);
				}
				encoder.submitRenderPass();
				encoder.submit();
			} finally {
				MetalHooks.setRedirector(null);
				MetalHooks.encoderTrace = null;
			}

			assertEquals(List.of(), scene.delegate().warnings(), "no draw was dropped");
			assertEquals(4, groupBegins(begins).size(), "the premise: the draws switch groups four times");
			assertEquals(DRAWS.size(), binds.get(), "one bind per game setPipeline: a group switch binds nothing again");
			scene.delegate().close();
		}
	}

	/** A delegate that counts the pipelines the pass sets on it, and forwards every call to {@code target}. */
	private static PassDelegate counting(PassDelegate target, AtomicInteger setPipelines) {
		return (PassDelegate) Proxy.newProxyInstance(PassDelegate.class.getClassLoader(), new Class<?>[] {PassDelegate.class},
			(proxy, method, args) -> {
				if (method.getName().equals("setPipeline")) {
					setPipelines.incrementAndGet();
				}
				try {
					return method.invoke(target, args);
				} catch (InvocationTargetException e) {
					throw e.getCause();
				}
			});
	}

	/**
	 * The engine splits the open pass onto other attachments with another depth attachment (the phase's own depth) before a
	 * group switch. The switch must reopen the pass on that depth: a switch reopens on the depth of the last split, not the
	 * depth the pass opened with. Observed through the encoder trace, which names the depth of every render encoder begun.
	 */
	@Test
	void aGroupSwitchReopensOnTheDepthOfTheLastSplit() {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			long[] textures = colourTextures(ctx);
			long opened = PhaseSplitResources.depthTexture(ctx, W, H);
			long split = PhaseSplitResources.depthTexture(ctx, W, H);
			Scene scene = scene(ctx, textures, opened);
			int groupB = scene.groups().groupOf().get(PACK_B);
			CommandEncoderBackend encoder = (CommandEncoderBackend) BackendPassHarness.newEncoder(ctx);
			GpuBuffer vertices = PhaseSplitResources.buffer(encoder, ctx, floats(POSITIONS));
			GpuBuffer indices = PhaseSplitResources.buffer(encoder, ctx, ints(INDICES));
			RenderPassDescriptor descriptor = BackendPassHarness.passDescriptor(encoder, ctx, "split depth", W, H);

			List<Begin> begins = new ArrayList<>();
			MetalHooks.encoderTrace = recorder(begins);
			try {
				MetalHooks.setRedirector(redirected -> new MetalHooks.Redirect(scene.colours()[0], null, W, H, opened, 1f,
					scene.delegate()));
				RenderPassBackend pass = encoder.createRenderPass(descriptor);
				pass.setVertexBuffer(0, vertices.slice(0, POSITIONS.length * 4L));
				pass.setIndexBuffer(indices, IndexType.INT);
				pass.setPipeline(scene.pipelines().get(GAME_B));
				pass.drawIndexed(INDICES.length, 1, 0, 0, 0);
				// The engine's split onto group B's colours, with the other depth attachment.
				MetalBridge.splitRenderPass(encoder, scene.colours()[groupB], null, split, false, 0f, W, H);
				pass.setPipeline(scene.pipelines().get(GAME_A));
				pass.drawIndexed(INDICES.length, 1, 0, 0, 0);
				encoder.submitRenderPass();
				encoder.submit();
			} finally {
				MetalHooks.setRedirector(null);
				MetalHooks.encoderTrace = null;
			}

			assertEquals(List.of(), scene.delegate().warnings(), "no draw was dropped");
			List<Begin> switches = groupBegins(begins);
			assertEquals(1, switches.size(), "the one group change splits the pass once");
			assertEquals(split, switches.get(0).depth(),
				"the group switch reopens the pass on the depth of the engine's split, not the depth it opened with");
			scene.delegate().close();
		}
	}

	/** The group-switch begins of a trace, in order. */
	private static List<Begin> groupBegins(List<Begin> begins) {
		return begins.stream().filter(begin -> begin.why().equals("group")).toList();
	}

	/** Records every render encoder the backend begins, with its label and depth attachment. */
	private static MetalHooks.EncoderTrace recorder(List<Begin> begins) {
		return new MetalHooks.EncoderTrace() {
			@Override
			public void begin(String why, int mode, boolean endedPrevious, long[] colors, int[] loads, long depth,
					boolean clearDepth) {
				begins.add(new Begin(why, depth));
			}

			@Override
			public void split(String why, long texture) {
			}

			@Override
			public void restore(String pipeline) {
			}
		};
	}

	/** The colour textures of the phase's targets: TARGETS RGBA8 render targets of the context. */
	private static long[] colourTextures(long ctx) {
		long[] textures = new long[TARGETS];
		for (int t = 0; t < TARGETS; t++) {
			textures[t] = MetalBridge.newTexture(ctx, GpuFormat.RGBA8_UNORM, W, H, false);
		}
		return textures;
	}

	/**
	 * The synthetic phase on a context: pack A and B, and the game variant in each group, with the pass opening on group 0
	 * (B's) and the depth binding of every variant.
	 */
	private static Scene scene(long ctx, long[] textures, long depthTexture) {
		// Program A writes colortex0 to colortex7 and program B colortex0 and colortex8: the union is nine targets, so the
		// phase splits into two groups. B's group opens the pass (it holds colortex0 and colortex8), A's is the other.
		PhaseLayout.Groups groups = PhaseLayout.groups(PHASE.name(), Map.of(PACK_A, range(0, 7), PACK_B, Set.of(0, 8)));
		assertEquals(2, groups.layouts().size(), "the premise: the two programs need two attachment groups");
		int groupA = groups.groupOf().get(PACK_A), groupB = groups.groupOf().get(PACK_B);
		assertTrue(groupA != groupB, "A and B are in different groups");
		assertEquals(0, groupB, "the group the pass opens with holds B");

		long[][] colours = new long[groups.layouts().size()][];
		for (int g = 0; g < colours.length; g++) {
			int[] layout = groups.layouts().get(g).targets();
			colours[g] = new long[layout.length];
			for (int i = 0; i < layout.length; i++) {
				colours[g][i] = textures[layout[i]];
			}
		}

		int[] layoutA = groups.layouts().get(groupA).targets(), layoutB = groups.layouts().get(groupB).targets();
		long psoA = pso(ctx, "a", RED, layoutA, range(0, 7), true, false);
		long psoANoDepth = pso(ctx, "a", RED, layoutA, range(0, 7), false, false);
		long psoB = pso(ctx, "b", BLUE, layoutB, Set.of(0, 8), true, false);
		long psoBNoDepth = pso(ctx, "b", BLUE, layoutB, Set.of(0, 8), false, false);
		// The game variant's copy of each group: colortex0 written, every other attachment of the group masked.
		long psoGGroupB = pso(ctx, "g", GREEN, layoutB, Set.of(0), true, true);
		long psoGGroupBNoDepth = pso(ctx, "g", GREEN, layoutB, Set.of(0), false, true);
		long psoGGroupA = pso(ctx, "g", GREEN, layoutA, Set.of(0), true, true);
		long psoGGroupANoDepth = pso(ctx, "g", GREEN, layoutA, Set.of(0), false, true);
		int primitive = MetalBridge.primitive(PrimitiveTopology.TRIANGLES);
		GbufferPlans.DepthBinding depth = new GbufferPlans.DepthBinding(passingDepth(ctx), 0, 0);

		Map<String, Map<String, GbufferPlans.Plan>> plans = new HashMap<>();
		plans.put(GAME_A, Map.of("", new SyntheticPlan(GAME_A,
			pack(PACK_A, groupA, psoA, psoANoDepth, depth, primitive), Map.of())));
		plans.put(GAME_B, Map.of("", new SyntheticPlan(GAME_B,
			pack(PACK_B, groupB, psoB, psoBNoDepth, depth, primitive), Map.of())));
		Map<Integer, GbufferPlans.GameVariant> gameByGroup = Map.of(
			groupB, gameCopy(psoGGroupB, psoGGroupBNoDepth, depth, primitive),
			groupA, gameCopy(psoGGroupA, psoGGroupANoDepth, depth, primitive));
		plans.put(GAME_G, Map.of("", new SyntheticPlan(GAME_G, gameByGroup.get(groupB), gameByGroup)));
		PhaseLayout[][] layouts = new PhaseLayout[GbufferPhase.values().length][];
		for (GbufferPhase phase : GbufferPhase.values()) {
			layouts[phase.ordinal()] = new PhaseLayout[] {PhaseLayout.of(phase.name(), range(0, 0))};
		}
		layouts[PHASE.ordinal()] = groups.layouts().toArray(PhaseLayout[]::new);
		GbufferDelegate delegate = new GbufferDelegate(GbufferPlans.forTestGroups(layouts, plans), new Frame(colours));
		delegate.phase(PHASE);

		Map<String, BackendRenderPipeline> pipelines = new HashMap<>();
		pipelines.put(GAME_A, BackendPassHarness.pipelineWithStates(GAME_A, List.of(), 0, 0));
		pipelines.put(GAME_B, BackendPassHarness.pipelineWithStates(GAME_B, List.of(), 0, 0));
		pipelines.put(GAME_G, BackendPassHarness.pipelineWithStates(GAME_G, GAME_UNIFORMS, 0, 0));
		return new Scene(groups, colours, delegate, pipelines);
	}

	/** The target indices from {@code first} to {@code last} inclusive. */
	private static Set<Integer> range(int first, int last) {
		TreeSet<Integer> set = new TreeSet<>();
		for (int t = first; t <= last; t++) {
			set.add(t);
		}
		return set;
	}

	/** A pack program's variant of group {@code group}: its states (with and without depth) and its depth binding. */
	private static GbufferPlans.PackVariant pack(String program, int group, long pso, long psoNoDepth,
			GbufferPlans.DepthBinding depth, int primitive) {
		return new GbufferPlans.Pack(pso, psoNoDepth, depth, depth, false, false, primitive, program, 0, 0.1f, null,
			List.of(), List.of(), List.of(), -1, -1, PrologueFacts.NONE, false, group);
	}

	/** A game-pipeline variant drawing with a copy whose states are {@code withDepth} and {@code withoutDepth}. */
	private static GbufferPlans.GameVariant gameCopy(long withDepth, long withoutDepth, GbufferPlans.DepthBinding depth,
			int primitive) {
		GbufferPlans.CopyRef copy = new GbufferPlans.CopyRef(null);
		copy.compiled = BackendPassHarness.pipelineWithStates(GAME_G, GAME_UNIFORMS, withDepth, withoutDepth,
			GAME_VERTEX_SAMPLERS, GAME_FRAGMENT_SAMPLERS);
		return new GbufferPlans.Game(copy, depth, depth, false, false, primitive);
	}

	/**
	 * A render pipeline state writing {@code layout} (every target RGBA8; the ones in {@code written} drawn) in {@code colour},
	 * with or without the depth attachment. A uniformed one reads the game variant's tint and texel.
	 */
	private static long pso(long ctx, String name, float[] colour, int[] layout, Set<Integer> written, boolean depth,
			boolean uniformed) {
		int[] formats = new int[layout.length];
		Arrays.fill(formats, RGBA8);
		int[] descriptor = GbufferPlans.descriptorWords(GbufferPlans.vertexWords(0, List.of()), layout, formats, written,
			target -> null, MetalBridge.topologyClass(PrimitiveTopology.TRIANGLES), depth);
		ShaderNative.Compiled library = ShaderNative.libraryNew(ctx, shaders(name, layout.length, colour, uniformed),
			ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true);
		assertTrue(library.ok(), "library " + name + " failed: " + library.error());
		try {
			return MetalBridge.pipelineNew(ctx, library.library(), "vs_" + name, library.library(), "fs_" + name, descriptor);
		} finally {
			MetalBridge.release(library.library());
		}
	}

	/**
	 * A full-screen triangle whose positions come from buffer 27 (the first vertex buffer slot, MetalConst.VERTEX_BUFFER_BASE),
	 * and a fragment writing {@code colour} to each of {@code outputs} attachments. A uniformed fragment writes its tint times
	 * its texel, read from buffer 0 and sampled from texture 1 with the sampler at slot 0 (the game variant's uniforms). What a
	 * draw reads with no sampler bound depends on the GPU (here it read white), so the test asserts the delegate's slots.
	 */
	private static String shaders(String name, int outputs, float[] colour, boolean uniformed) {
		StringBuilder fields = new StringBuilder(), body = new StringBuilder();
		for (int k = 0; k < outputs; k++) {
			fields.append("\tfloat4 c").append(k).append(" [[color(").append(k).append(")]];\n");
			String value = uniformed ? "colour"
				: "float4(" + colour[0] + ", " + colour[1] + ", " + colour[2] + ", " + colour[3] + ")";
			body.append("\to.c").append(k).append(" = ").append(value).append(";\n");
		}
		String params = uniformed
			? ", constant float4 &tint [[buffer(0)]], texture2d<float> tex [[texture(1)]], sampler smp [[sampler(0)]]" : "";
		String pixel = uniformed ? "\tfloat4 colour = tint * tex.sample(smp, float2(0.5, 0.5));\n" : "";
		return """
			#include <metal_stdlib>
			using namespace metal;
			struct VOut { float4 position [[position]]; };
			struct FOut {
			%s};
			vertex VOut vs_%s(uint vid [[vertex_id]], constant float2 *positions [[buffer(27)]]) {
				VOut o;
				o.position = float4(positions[vid], 0.5, 1.0);
				return o;
			}
			fragment FOut fs_%s(VOut in [[stage_in]]%s) {
				FOut o;
			%s%s	return o;
			}
			""".formatted(fields, name, name, params, pixel, body);
	}

	/** A depth test that passes z 0.5 against the depth clear of 1.0 (and after the draw's own write of 0.5). */
	private static long passingDepth(long ctx) {
		return MetalBridge.depthStateNew(ctx, MetalBridge.compareWord(CompareOp.LESS_THAN_OR_EQUAL), true);
	}

	/** Vertex positions or indices as little-endian bytes: a float or int buffer's contents. */
	private static ByteBuffer floats(float... values) {
		ByteBuffer bytes = ByteBuffer.allocate(4 * values.length).order(ByteOrder.LITTLE_ENDIAN);
		for (float value : values) {
			bytes.putFloat(value);
		}
		return bytes.flip();
	}

	private static ByteBuffer ints(int... values) {
		ByteBuffer bytes = ByteBuffer.allocate(4 * values.length).order(ByteOrder.LITTLE_ENDIAN);
		for (int value : values) {
			bytes.putInt(value);
		}
		return bytes.flip();
	}

	/** The channel of a pixel in RGBA8 bytes. */
	private static int channel(byte[] pixels, int x, int y, int c) {
		return pixels[(y * W + x) * 4 + c] & 0xff;
	}
}
