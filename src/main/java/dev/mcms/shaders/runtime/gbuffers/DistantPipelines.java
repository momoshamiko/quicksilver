// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.mcms.shaders.runtime.distant.DistantVertices;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/**
 * Distant Horizons' terrain pipelines as the engine plans them (plan.md E7, "Distant Horizons, as built"; DH 3.3.4's
 * {@code BlazeDhTerrainRenderer.tryInit}, javap 2026-11-15).
 *
 * <p>DH creates its two terrain pipelines lazily, in its first render, and they appear in no game pipeline list, so the
 * engine plans the same objects itself at set load, as {@link GbufferPlans}' Sodium terrain pipelines do: location
 * {@code distanthorizons:opaque_terrain} (no blend) and {@code distanthorizons:transparent_terrain} (translucent blend),
 * DH's 16-byte terrain vertex ({@link DistantVertices}), counter-clockwise triangles culled as DH culls them, and the
 * reverse-Z depth test DH uses ({@code GREATER_THAN}, DH's {@code EDhDepthTest.GREATER} with depth direction REVERSE_Z).
 * The plans match DH's real pipelines by name and vertex stride (16), so DH's own objects draw through them.
 *
 * <p>The vertex format is registered with {@link VertexLayoutRegistry} at {@link DistantVertices#layout()}, so the pack's
 * {@code dh_} programs see the record as {@link DistantVertices} documents it. The format is excluded from the Sodium
 * terrain loops ({@link GbufferPlans}), which build Sodium's own pipelines for every registered format.
 */
public final class DistantPipelines {

	/** The namespace of DH's pipelines (DH's {@code RenderPipelineBuilderWrapper.NAME_PREFIX}). */
	public static final String NAMESPACE = "distanthorizons";
	/** The location paths of the two pipelines. */
	public static final String OPAQUE = "opaque_terrain";
	public static final String TRANSPARENT = "transparent_terrain";

	/**
	 * DH's terrain vertex (16 bytes): the element names are DH's own ({@code BlazeDhTerrainRenderer}), the formats and offsets
	 * those of {@link DistantVertices} ("Short XYZ Pos", "meta", the RGBA colour, the material, the direction, the tile).
	 */
	public static final VertexFormat FORMAT = VertexFormat.builder(0)
		.addAttribute("vPosition", GpuFormat.RGB16_UINT)
		.addAttribute("meta", GpuFormat.R16_UINT)
		.addAttribute("vColor", GpuFormat.RGBA8_UNORM)
		.addAttribute("irisMaterial", GpuFormat.R8_UINT)
		.addAttribute("irisNormal", GpuFormat.R8_UINT)
		.addAttribute("textureTile", GpuFormat.R16_UINT)
		.build();

	private DistantPipelines() {
	}

	/**
	 * Registers DH's vertex format with its layout (idempotent). Called before a plan build reads the registry, so the
	 * {@code dh_} programs translate against the record.
	 */
	public static void register() {
		VertexLayoutRegistry.register(FORMAT, DistantVertices.layout(), PrologueFacts.NONE);
	}

	/** Whether a pack program is one of Distant Horizons' terrain programs ({@code dh_terrain}, {@code dh_water}). */
	public static boolean isProgram(String name) {
		return name.equals("dh_terrain") || name.equals("dh_water");
	}

	/** Whether a pipeline name is one of DH's terrain pipelines ({@code distanthorizons:opaque_terrain} and its sibling). */
	public static boolean isPipeline(String name) {
		return name.startsWith(NAMESPACE + ":") && GbufferMapping.isDistant(name.substring(NAMESPACE.length() + 1));
	}

	/**
	 * The two pipelines the engine plans for DH (the class comment). Each call builds new objects; the plan build calls it
	 * once per set.
	 */
	public static List<RenderPipeline> pipelines() {
		List<RenderPipeline> out = new ArrayList<>(2);
		out.add(RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath(NAMESPACE, OPAQUE))
			.withCull(true)
			.withVertexShader(Identifier.fromNamespaceAndPath(NAMESPACE, "terrain/blaze/vert"))
			.withFragmentShader(Identifier.fromNamespaceAndPath(NAMESPACE, "terrain/blaze/frag"))
			.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN, true, 0f, 0f))
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withVertexBinding(0, FORMAT)
			.withColorTargetState(ColorTargetState.DEFAULT)
			.build());
		out.add(RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath(NAMESPACE, TRANSPARENT))
			.withCull(true)
			.withVertexShader(Identifier.fromNamespaceAndPath(NAMESPACE, "terrain/blaze/vert"))
			.withFragmentShader(Identifier.fromNamespaceAndPath(NAMESPACE, "terrain/blaze/frag"))
			.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN, true, 0f, 0f))
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withVertexBinding(0, FORMAT)
			.withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT), GpuFormat.RGBA8_UNORM, -1))
			.build());
		return out;
	}
}
