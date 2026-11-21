// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import dev.mcms.glsl.msl.ProgramRequest.Semantic;
import dev.mcms.glsl.msl.ProgramRequest.VertexAttribute;
import dev.mcms.glsl.msl.ProgramRequest.VertexLayout;

/**
 * The {@link VertexLayout} of {@link TerrainVertexType} as the translator sees it (plan.md E4; the registry contract
 * with E4-A): every attribute of buffer 0 at its byte offset, and a decode expression where the packed encoding needs
 * more than the format's standard conversion. The expressions are MSL over the stage-in values {@code qs_a<n>} (n is
 * the attribute's index in this list) and produce the semantic's GLSL value. They may read the {@code u_Globals}
 * mirror through {@code qs_globals} (the prologue binds it when the layout has one).
 *
 * <ul>
 * <li>position: the 20-bit words de-interleaved ({@code hi << 10 | lo} per component at shifts 0/10/20), scaled by
 * {@code 32/2^20} with the -8 offset, plus the intra-region section translation
 * {@code (draw_id >> (5,0,2) & (7,3,7)) * 16} from the data word's section index. The region offset (push constants,
 * slot 26) and, on releases that declare it, the camera offset are added around this value by the prologue the
 * translator emits from the registered facts (E4-A's contract: exactly once).
 * <li>texture: {@code q / 32768 + bias * u_TexCoordShrink}, with {@code q} the 15-bit magnitude and {@code bias} the
 * sign bit's {@code mix(-1, 1, bit)}: Sodium's {@code chunk_vertex.glsl} and {@code block_layer_opaque.vsh}, both
 * jars. The shrink is per frame (it depends on the atlas size), so it is read from the globals mirror at its
 * {@code std140} offset.
 * <li>light: the two biased bytes minus the bias of 8, so the value is the light level 0 to 240 that the translator's
 * lightmap matrix ({@code /256 + 1/32}) expects. Sodium's shader divides the biased byte by 256 instead; the
 * two agree because the matrix adds the 8 back.
 * <li>entity: {@code mc_Entity.x} is the stored id + 1 minus one; {@code mc_Entity.y} is 1.0 for a fluid and -1.0 for
 * every other block (shaders.properties, {@code mc_Entity}).
 * <li>mid-block: the signed-normalised bytes scaled back to the 1/64 m units the attribute defines, the w component
 * the block emission.
 * </ul>
 *
 * <p>The plain formats (colour, tangent, normal, sprite centre) decode through the standard conversion; the tangent's
 * handedness is exact because {@code ±127/127} is {@code ±1}.
 */
public final class TerrainLayout {

	private TerrainLayout() {
	}

	/**
	 * The layout, with the decode expressions of the packed attributes.
	 *
	 * @param texCoordShrinkAt the byte offset of {@code u_TexCoordShrink} inside {@code u_Globals} (from Sodium's own
	 *                         globals.glsl; {@link SodiumGlobals#offsetOf})
	 */
	public static VertexLayout layout(long texCoordShrinkAt) {
		return VertexLayout.of(
			new VertexAttribute(Semantic.POSITION, "uint2", 0, TerrainVertexType.A_POSITION, POSITION_DECODE),
			new VertexAttribute(Semantic.COLOR, "uchar4Normalized", 0, TerrainVertexType.A_COLOR),
			new VertexAttribute(Semantic.TEXCOORD0, "uint2", 0, TerrainVertexType.A_TEXCOORD,
				texcoordDecode(texCoordShrinkAt)),
			new VertexAttribute(Semantic.LIGHTMAP, "uint4", 0, TerrainVertexType.A_LIGHT_AND_DATA, LIGHT_DECODE),
			new VertexAttribute(Semantic.ENTITY, "ushort2", 0, TerrainVertexType.A_ENTITY, ENTITY_DECODE),
			new VertexAttribute(Semantic.TANGENT, "char4Normalized", 0, TerrainVertexType.A_TANGENT),
			new VertexAttribute(Semantic.NORMAL, "char4Normalized", 0, TerrainVertexType.A_NORMAL),
			new VertexAttribute(Semantic.MID_BLOCK, "char4Normalized", 0, TerrainVertexType.A_MID_BLOCK, MID_BLOCK_DECODE),
			new VertexAttribute(Semantic.MID_TEXCOORD, "float2", 0, TerrainVertexType.A_MID_TEXCOORD));
	}

	// The shifts splat the scalar word first (uint3(word) >> uint3(...)): MSL takes vector << vector, not
	// scalar >> vector (the GPU decode test's first compile caught the original scalar-first form).
	private static final String POSITION_DECODE =
		"((float3(((((uint3(qs_a0.x) >> uint3(0u, 10u, 20u)) & uint3(0x3FFu)) << 10u)"
			+ " | ((uint3(qs_a0.y) >> uint3(0u, 10u, 20u)) & uint3(0x3FFu)))) * (32.0 / 1048576.0)) - float3(8.0))"
			+ " + (float3((uint3(qs_a3.w) >> uint3(5u, 0u, 2u)) & uint3(7u, 3u, 7u)) * 16.0)";

	/** {@code q / 32768 + bias * u_TexCoordShrink}, the shrink read from the globals mirror at {@code shrinkAt}. */
	private static String texcoordDecode(long shrinkAt) {
		return "(float2(qs_a2 & uint2(0x7FFFu)) / 32768.0"
			+ " + mix(float2(-1.0), float2(1.0), float2((qs_a2 >> 15u) & uint2(1u)))"
			+ " * *(constant float2*)(qs_globals + " + shrinkAt + "L))";
	}

	private static final String LIGHT_DECODE = "float2(qs_a3.xy) - 8.0";

	private static final String ENTITY_DECODE = "float3(float(qs_a4.x) - 1.0, float(qs_a4.y) * 2.0 - 1.0, 0.0)";

	/** The four snorm bytes: xyz offsets and w emission, all in 1/64 m units (the attribute's own form, at_midBlock). */
	private static final String MID_BLOCK_DECODE = "float4(qs_a7 * 127.0)";
}
