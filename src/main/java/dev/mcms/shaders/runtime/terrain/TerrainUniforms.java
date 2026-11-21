// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import dev.mcms.shaders.runtime.gbuffers.GbufferDelegate;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import org.jspecify.annotations.Nullable;

/**
 * The engine's mirror of Sodium's {@code u_Globals} block for the terrain pipelines (plan.md E4; ADR 0007 decision 3).
 * {@code UniformBufferManager}'s {@code GlobalUniforms.write(ByteBuffer)} fills the block's bytes each frame (projection,
 * model-view, fog, the atlas shrink, the camera offset on beta.5). {@link #globalsWritten} copies those bytes into one
 * CPU staging segment, which the active delegate registers with {@link GbufferDelegate#constantBytes}: the delegate
 * copies the bytes into each draw's encoder when it binds them, as the backend copies its own push constants. A copy
 * made at bind time is safe while the GPU runs earlier draws, which a buffer rewritten per frame would not be: the
 * backend commits command buffers without waiting for the GPU.
 *
 * <p>The region offset is not mirrored here. Sodium sends its per-region push constants with
 * {@code RenderPass.pushConstants}, which the backend binds to every draw at the push-constant slot, and the terrain
 * prologue reads that slot directly ({@code EngineSlots.PUSH_CONSTANTS}).
 */
public final class TerrainUniforms {

	/** The buffer slot of the {@code u_Globals} mirror (the engine's slot map, {@code EngineSlots.SODIUM_GLOBALS}). */
	public static final int GLOBALS_SLOT = dev.mcms.shaders.runtime.EngineSlots.SODIUM_GLOBALS;
	/** The staging size: at least the block's size on every allowed release (184 bytes on beta.1, 200 on beta.5). */
	static final int GLOBALS_BYTES = 256;

	/** The CPU staging of the globals block; allocated once when the engine first activates, never freed. */
	private static @Nullable MemorySegment globals;
	/** Copy scratch: the render thread's {@link #globalsWritten} is the only writer, so one array suffices. */
	private static final byte[] SCRATCH = new byte[GLOBALS_BYTES];
	/** The delegate the staging is registered with; null while none is (the release listener clears it). */
	private static @Nullable GbufferDelegate registeredDelegate;

	private TerrainUniforms() {
	}

	/**
	 * Allocates the staging once (render thread, when the engine first becomes active). Idempotent; nothing else
	 * allocates on the frame path.
	 */
	public static void activate() {
		if (globals == null) {
			globals = Arena.ofAuto().allocate(GLOBALS_BYTES, 16);
		}
	}

	/**
	 * Copies the global block's bytes as {@code GlobalUniforms.write} just wrote them (render thread, once per frame).
	 * The writer flips the buffer, so its position is 0 and its remaining bytes are the block.
	 */
	public static void globalsWritten(ByteBuffer bytes) {
		MemorySegment staging = globals;
		if (staging == null) {
			return;
		}
		int n = Math.min(GLOBALS_BYTES, bytes.remaining());
		bytes.get(bytes.position(), SCRATCH, 0, n);
		MemorySegment.copy(SCRATCH, 0, staging, ValueLayout.JAVA_BYTE, 0, n);
	}

	/** The staged byte at {@code index} (tests only: the mirror's content after the writes). */
	static int stagingByteForTest(int index) {
		return globals.get(ValueLayout.JAVA_BYTE, index);
	}

	/**
	 * Registers the staging with the active delegate (the E4-A {@code constantBytes} contract). Called every frame from the
	 * terrain latch. It registers only when the active delegate changed (a set installs one delegate once), and it forgets
	 * the registration when no delegate is active. When a delegate exists, {@link #activate} allocates the staging in the
	 * same call, so a set's first frame's terrain draws find the globals bound. With no delegate nothing is allocated, so
	 * the engine stays inert.
	 */
	public static void syncConstantBlocks() {
		GbufferDelegate delegate = GbufferDelegate.active();
		if (delegate != null) {
			activate();
		}
		if (!registrationNeeded(delegate, registeredDelegate)) {
			return;
		}
		if (delegate != null) {
			delegate.constantBytes(GLOBALS_SLOT, globals);
		}
		registeredDelegate = delegate;
	}

	/**
	 * The registration decision, apart from where the inputs come from (the unit test drives it): register when the
	 * delegate instance changed, which a set does once. A null delegate forgets the previous registration.
	 */
	static boolean registrationNeeded(@Nullable GbufferDelegate delegate, @Nullable GbufferDelegate registered) {
		return delegate != registered;
	}

	/** The engine's release listener: the delegate that held the staging is gone, so the next frame registers again. */
	static void release() {
		registeredDelegate = null;
	}

	/** Test seam: forgets the staging and the registration, as before the engine's first activation. */
	static void forgetStagingForTest() {
		globals = null;
		registeredDelegate = null;
	}
}
