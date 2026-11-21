// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

/**
 * The buffer indices every pipeline the engine's translator builds reads, one map for all of them (plan.md E4; decided
 * by Main on 2026-11-14 after a device probe: Metal's {@code [[buffer(n)]]} table holds indices 0 to 30, and 31 and up
 * fail to compile, see {@code BufferIndexRangeGpuTest}).
 *
 * <p>The reason for the high end: during a redirected draw the backend binds the game's push constants at 26 and the
 * game's vertex buffers at 27 to 30 (the backend's {@code PUSH_CONSTANTS_INDEX} and {@code VERTEX_BUFFER_BASE}), so an
 * engine pipeline reads those directly at the backend's own indices and never puts anything else there.
 *
 * <ul>
 * <li>0 to {@link #PACK_BLOCKS} - 1 (0..18): the pack's own uniform and buffer blocks ({@link BufferSlots}). A buffer block
 *     (an SSBO) with {@code binding = N} takes N, the index of the pack's {@code bufferObject.N} (E6). Every other block
 *     takes the lowest index no buffer binding takes, in declaration order. The corpus (measured 2026-11-14, the eight
 *     packs in bench/cache/shaderpacks) declares no uniform block and at most two buffer blocks per stage (Complementary
 *     Reimagined and Unbound, bindings 0 and 3, the same numbers as its {@code bufferObject.0} and {@code bufferObject.3}).
 *     A binding at 19 or above, two buffer blocks with one binding, or more blocks than the range holds, refuses the
 *     program (logged; the program counts as absent).
 * <li>{@link #DISTANT_HORIZONS} (19): the Distant Horizons block ({@code QsDh}, E7). Reserved from the pack range for every
 *     program, so a pack's blocks never take it (the cost: one index less for the pack's own blocks).
 * <li>{@link #PER_VERTEX_IDS} (20): E4-D's per-vertex id side buffer, read at {@code [[vertex_id]]}.
 * <li>{@link #SODIUM_GLOBALS} (21): Sodium's {@code u_Globals} mirror (E4-B, ADR 0007).
 * <li>{@link #CLOUD_INFO} (22): the game's CloudInfo block, read by the clouds prologue (E4; the clouds' CloudFaces
 *     texel buffer is a texture index instead, {@link #CLOUD_FACES}).
 * <li>{@link #CUSTOM} (23): the pack's custom uniform block ({@code QsCustom}).
 * <li>{@link #FRAME} (24): the frame block ({@code QsFrame}, {@link dev.mcms.shaders.runtime.uniforms.FrameUniforms}).
 * <li>{@link #DRAW} (25): the per-draw block ({@code QsDraw}: the draw's matrices, render stage, alpha reference).
 * <li>{@link #PUSH_CONSTANTS} (26): the backend's push constants, read directly (nothing is bound here by the engine).
 * <li>{@link #VERTEX_DATA} (27) to 30: the backend's vertex buffers (slot 0 at 27, the game's), read directly.
 * </ul>
 */
public final class EngineSlots {

	/** The Distant Horizons uniform block ({@code QsDh}, plan.md E7): the dh_ programs read it; every program reserves it. */
	public static final int DISTANT_HORIZONS = 19;
	/**
	 * The pack's blocks take indices 0 to this minus one; the translator refuses a program that declares more. One index
	 * below {@link #DISTANT_HORIZONS}, which the Distant Horizons block holds for every program.
	 */
	public static final int PACK_BLOCKS = DISTANT_HORIZONS;
	public static final int PER_VERTEX_IDS = 20;
	/**
	 * The device buffer of a compute kernel's spilled shared arrays (E6; the translator binds it at the same index,
	 * ProgramRequest.SHARED_SPILL_SLOT). It shares the number with {@link #PER_VERTEX_IDS}: a compute kernel binds no
	 * per-vertex ids, and a render pipeline binds no spill buffer.
	 */
	public static final int SHARED_SPILL = dev.mcms.glsl.msl.ProgramRequest.SHARED_SPILL_SLOT;
	public static final int SODIUM_GLOBALS = 21;
	/** The game's CloudInfo block (the clouds' uniform block, E4), at the reserved buffer index below the engine's. */
	public static final int CLOUD_INFO = 22;
	/**
	 * The game's CloudFaces texel buffer (the clouds' three ints per quad, E4), a texture index because a Metal texel
	 * buffer is a texture: from the top, since a program's own textures count up from 0.
	 */
	public static final int CLOUD_FACES = 30;
	public static final int CUSTOM = 23;
	public static final int FRAME = 24;
	public static final int DRAW = 25;
	/** The backend's push-constant index (MetalConst.PUSH_CONSTANTS_INDEX); read directly, never bound by the engine. */
	public static final int PUSH_CONSTANTS = 26;
	/** The backend's first vertex buffer index (MetalConst.VERTEX_BUFFER_BASE); buffer slots 0 to 3 sit at 27 to 30. */
	public static final int VERTEX_DATA = 27;

	private EngineSlots() {
	}
}
