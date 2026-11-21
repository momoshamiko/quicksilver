// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.mcms.shaders.runtime.gbuffers.GbufferMapping;
import dev.mcms.shaders.runtime.gbuffers.GbufferPhase;
import dev.mcms.shaders.runtime.gbuffers.GbufferPlans;
import dev.mcms.shaders.runtime.gbuffers.PassKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A text dump of every routing answer a gbuffers plans object gives: per pipeline (location and vertex strides), kind,
 * phase and domain, the variant (program, render stage, depth bindings, slots, state presence) or the game variant and
 * its reason, plus each phase's layout, feedback and read targets. Object handles are left out, so two builds of the
 * same pack dump the same lines. Sorted, so the order of the game's pipeline list and of each target list does not matter.
 */
final class GbufferRoutingDump {

	private GbufferRoutingDump() {
	}

	/** The dump of one plans object over the game's pipelines and the two Sodium terrain names the engine plans. */
	static List<String> of(GbufferPlans plans, List<RenderPipeline> pipelines) {
		List<String> lines = new ArrayList<>();
		lines.add("servesWorld " + plans.servesWorld());
		for (GbufferPhase phase : GbufferPhase.values()) {
			StringBuilder attachments = new StringBuilder();
			int[] layout = plans.layout(phase);
			for (int attachment = 0; attachment < layout.length; attachment++) {
				attachments.append(' ').append(layout[attachment]).append(':').append(plans.attachmentFormat(phase, attachment));
			}
			lines.add("layout " + phase + " [" + attachments.toString().trim() + "] feedback "
				+ sortedText(plans.feedbackTargets(phase)) + " reads " + sortedText(plans.readTargets(phase)) + " sampled "
				+ sortedText(plans.sampledTargets(phase)));
		}
		Set<String> seen = new HashSet<>();
		for (RenderPipeline game : pipelines) {
			String name = game.getLocation().toString();
			int[] strides = strides(game);
			String key = name + " " + Arrays.toString(strides);
			if (seen.add(key)) {
				lines.addAll(lines(plans, key, plans.planOf(name, strides), name, game));
			}
		}
		for (String sodium : List.of("sodium:pipeline/solid_terrain", "sodium:pipeline/cutout_terrain",
				"sodium:pipeline/translucent_terrain")) {
			lines.addAll(lines(plans, sodium, plans.planOf(sodium), sodium, null));
		}
		Collections.sort(lines);
		return lines;
	}

	private static List<String> lines(GbufferPlans plans, String key, GbufferPlans.Plan plan, String name,
			RenderPipeline game) {
		List<String> out = new ArrayList<>();
		if (plan == null) {
			out.add(key + " NO PLAN");
			return out;
		}
		for (PassKind kind : PassKind.values()) {
			for (GbufferPhase phase : GbufferPhase.values()) {
				for (GbufferMapping.Domain domain : GbufferMapping.Domain.values()) {
					String head = key + " " + kind + " " + phase + " " + domain + " ";
					GbufferPlans.Variant variant = plan.variant(kind, phase, domain);
					if (variant == null) {
						out.add(head + "none " + plan.reason(kind, phase, domain));
					} else if (variant instanceof GbufferPlans.PackVariant pv) {
						out.add(head + "pack " + pv.program() + " stage=" + pv.renderStage() + " alpha=" + pv.alphaTestRef()
							+ " cull=" + pv.cull() + " wire=" + pv.wireframe() + " prim=" + pv.primitive()
							+ " depthEngine=" + pv.depth(false).description() + " bias=" + pv.depth(false).biasConstant()
							+ "," + pv.depth(false).biasSlope() + " depthReversed=" + pv.depth(true).description()
							+ " bias=" + pv.depth(true).biasConstant() + "," + pv.depth(true).biasSlope()
							+ " hasDepthState=" + (pv.pipeline(true) != 0) + " noDepthState=" + (pv.pipeline(false) != 0)
							+ " perVertex=" + pv.perVertexIdSlot() + " overlay=" + pv.overlaySlot()
							+ " textures=" + pv.textures() + " images=" + pv.images() + " buffers=" + pv.buffers()
							+ " prologue=" + pv.prologue());
					} else if (variant instanceof GbufferPlans.GameVariant gv) {
						out.add(head + "game own=" + gv.ownPipeline() + " copy=" + (gv.copy() != null)
							+ " cull=" + gv.cull() + " wire=" + gv.wireframe() + " prim=" + gv.primitive()
							+ " depthEngine=" + gv.depth(false).description() + " depthReversed=" + gv.depth(true).description()
							+ " reason=" + plan.reason(kind, phase, domain));
					}
				}
			}
		}
		return out;
	}

	/** The vertex strides of a game pipeline in binding order (a null binding adds none), as the plans key them. */
	static int[] strides(RenderPipeline game) {
		List<Integer> strides = new ArrayList<>();
		for (VertexFormat binding : game.getVertexFormatBindings()) {
			if (binding != null) {
				strides.add(binding.getVertexSize());
			}
		}
		return strides.stream().mapToInt(Integer::intValue).toArray();
	}

	/** A target list as text, in sorted order: the plans keep their order, which the routing does not depend on. */
	private static String sortedText(int[] targets) {
		int[] sorted = targets.clone();
		Arrays.sort(sorted);
		return Arrays.toString(sorted);
	}
}
