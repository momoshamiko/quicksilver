// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.VertexLayout;
import dev.mcms.shaders.runtime.EngineSlots;
import java.util.List;

/**
 * The clouds' synthetic vertex layout and slot wiring (plan.md E4): the game's clouds pipelines (CLOUDS,
 * FLAT_CLOUDS, the OIT variants) bind no vertex format — the game's 26.3 {@code clouds.vsh} pulls three texels per
 * quad from the {@code isamplerBuffer} CloudFaces and builds position and colour from the vertex index, so today they
 * draw as game variants and a pack's {@code gbuffers_clouds} never runs. A request for the clouds program carries
 * {@link #LAYOUT} (no attributes) and {@link #PROLOGUE}; the translator's prologue then fills {@code gl_Vertex},
 * {@code gl_Color} and {@code gl_Normal} from the pulling, and the delegate binds the game's CloudFaces texel buffer
 * at {@link EngineSlots#CLOUD_FACES} and the game's CloudInfo block at {@link EngineSlots#CLOUD_INFO} for the clouds
 * draws (the game binds both itself by name, {@code RenderPass.setUniform}, javap 2026-11-14).
 */
public final class CloudsLayout {

	/** The layout of a clouds draw: no attributes; the prologue is the game's own vertex pulling. */
	public static final VertexLayout LAYOUT = new VertexLayout(List.of(), false, null);

	/** The prologue record every clouds request carries, with the engine's slot map. */
	public static final ProgramRequest.CloudsPrologue PROLOGUE =
		new ProgramRequest.CloudsPrologue(EngineSlots.CLOUD_INFO, EngineSlots.CLOUD_FACES);

	/**
	 * The CloudInfo block the game writes with Std140Builder: {@code vec4 CloudColor} at 0, {@code vec3 CloudOffset}
	 * at 16, {@code vec3 CellSize} at 32 (the order the renderer puts them, 48 bytes with the std140 vec3 padding);
	 * the engine binds the game's own buffer, so these offsets name where the prologue reads, not what we write.
	 */
	public static final int CLOUD_COLOR_OFFSET = 0, CLOUD_OFFSET_OFFSET = 16, CELL_SIZE_OFFSET = 32, CLOUD_INFO_BYTES = 48;

	/**
	 * Three BYTES per quad, one R8_SINT texel each ({@code texelFetch} returns the byte as an int): {@code cellX >> 1},
	 * {@code cellZ >> 1}, then the packed direction and flags (3 bits direction, bit 4 inside, bit 5 top colour,
	 * bit 6 cellZ's low bit, bit 7 cellX's low bit) — the game's {@code CloudRenderer.encodeFace}, and the pipeline's
	 * layout declares CloudFaces R8_SINT (javap 2026-11-14: BindGroupLayouts.CLOUD_INFO).
	 */
	public static final int FACES_TEXELS_PER_QUAD = 3;

	private CloudsLayout() {
	}
}
