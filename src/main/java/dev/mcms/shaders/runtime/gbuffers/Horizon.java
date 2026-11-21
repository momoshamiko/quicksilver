// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.runtime.EngineSlots;
import dev.mcms.shaders.runtime.Targets;
import dev.mcms.shaders.runtime.uniforms.FrameUniforms;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The horizon draw (plan.md E4): the geometry of {@link HorizonGeometry} drawn in the sky phase through the game's sky
 * pipeline, so a pack's {@code gbuffers_skybasic} (the chain {@code sky} maps to: skybasic, then basic) paints the
 * directions below the horizon, which vanilla leaves at the fog colour.
 *
 * <p>Design (decisions, E4):
 * <ul>
 * <li>Where: the clear hook, after the sky phase's load copies and before the frame's encoders end. The game's sky pass
 * then loads the picture, so its own draws (the top disc, the sun, the stars) stay on top of the horizon.</li>
 * <li>Pack only: the horizon draws through the pack's sky program. A pack without one leaves the directions below the
 * horizon to the game's fog colour, so the horizon draws nothing and logs nothing: the game's own sky shader would run
 * with no {@code DynamicTransforms} bound.</li>
 * <li>Depth: none. The game's sky pipeline has no depth state (javap 2026-11-14: its builder default is empty, so it
 * neither tests nor writes depth), and the horizon's pass has no depth attachment, so its pipelines are the no-depth
 * variants. A depth write at the fan's finite distance would hide the terrain behind it (see {@link HorizonGeometry}).</li>
 * <li>Colour: the fog colour. The game clears the main target to it, so the horizon is the colour the game shows there;
 * it reaches the pack as {@code gl_Color} through {@code DynamicTransforms.ColorModulator} (the sky pipeline takes its
 * colour from there, as the game's own sky disc does).</li>
 * <li>Model-view: the frame's view rotation, as the game's sky disc (its model-view is the view rotation). The
 * projection is the frame's, through the delegate.</li>
 * </ul>
 *
 * <p>Allocation: the draw allocates nothing. Three slots of GPU buffers rotate per draw, so a draw never writes memory
 * the GPU may still read from a frame earlier; a slot rebuilds its fan only when the far plane changes. The slots' buffers
 * share one owner per context ({@link MetalBridge#bufferOwner}), not one native encoder each.
 */
public final class Horizon implements AutoCloseable {

	/** The game pipeline the horizon draws through: POSITION vertices, TRIANGLE_FAN, the sky's depth state. */
	public static final String PIPELINE = "minecraft:pipeline/sky";

	private static final String TRANSFORMS = "DynamicTransforms";
	/**
	 * DynamicTransforms (std140, the game's shader include): mat4 ModelViewMat at 0, mat4 TextureMat at 64, vec4
	 * ColorModulator at 128, vec3 ModelOffset at 144.
	 */
	private static final int TRANSFORMS_BYTES = 160;
	private static final int MODEL_VIEW_AT = 0, TEXTURE_AT = 64, COLOR_MODULATOR_AT = 128, MODEL_OFFSET_AT = 144;
	private static final int VERTICES = HorizonGeometry.vertexCount(HorizonGeometry.SEGMENTS);
	private static final int VERTEX_BYTES = 3 * Float.BYTES * VERTICES;
	/** Slots in the ring: a draw's buffers are reused only after two later draws. */
	private static final int SLOTS = 3;

	/** One ring slot: the transforms block, its slice (made once), the fan and the far plane the fan was built for. */
	private static final class Slot {
		final GpuBuffer transforms, vertices;
		final GpuBufferSlice transformsSlice;
		final MemorySegment transformsMemory, vertexMemory;
		double far = Double.NaN;

		Slot(Object owner, long ctx) {
			this.transforms = MetalBridge.newGpuBuffer(owner, ctx, TRANSFORMS_BYTES);
			this.vertices = MetalBridge.newGpuBuffer(owner, ctx, VERTEX_BYTES);
			this.transformsSlice = this.transforms.slice();
			this.transformsMemory = MemorySegment.ofAddress(MetalBridge.bufferAddress(this.transforms))
				.reinterpret(TRANSFORMS_BYTES);
			this.vertexMemory = MemorySegment.ofAddress(MetalBridge.bufferAddress(this.vertices)).reinterpret(VERTEX_BYTES);
		}
	}

	private final List<BindGroupLayout.UniformDescription> uniforms;
	private final FrameUniforms frame;
	private final Slot[] slots = new Slot[SLOTS];
	private final float[] fan = new float[3 * VERTICES];
	private final float[] modelView = new float[16];
	private final float[] fog = new float[3];
	/** The pass's colour handles per attachment group of the sky layout (each filled per draw; allocated on its first use). */
	private final long[][] colours;
	/** The sky pipeline's resolved plan, resolved once per delegate: the set's plans are fixed while its delegate lives. */
	private @Nullable GbufferDelegate skyPipelineDelegate;
	private GbufferDelegate.@Nullable Resolved skyPipeline;
	private int next;
	private boolean closed;

	/**
	 * @param ctx         the backend context the buffers are made in
	 * @param gamePipelines the game's pipelines (the sky pipeline is looked up by its location)
	 * @param groups      the sky phase's attachment group count
	 * @param frame       the frame's uniforms (the view rotation, far plane and fog colour the draw reads)
	 */
	public Horizon(long ctx, List<RenderPipeline> gamePipelines, int groups, FrameUniforms frame) {
		this.uniforms = skyUniforms(gamePipelines);
		this.frame = frame;
		this.colours = new long[groups][];
		Object owner = MetalBridge.bufferOwner(ctx);
		for (int i = 0; i < SLOTS; i++) {
			this.slots[i] = new Slot(owner, ctx);
		}
	}

	/** The uniform descriptions of the game's sky pipeline, in its bind-group order (the delegate's select takes them). */
	private static List<BindGroupLayout.UniformDescription> skyUniforms(List<RenderPipeline> gamePipelines) {
		for (RenderPipeline pipeline : gamePipelines) {
			if (pipeline.getLocation().toString().equals(PIPELINE)) {
				return pipeline.getBindGroupLayouts().stream().flatMap(group -> group.uniforms().stream()).toList();
			}
		}
		throw new IllegalStateException("the game has no " + PIPELINE + " pipeline: the horizon cannot be drawn");
	}

	/**
	 * Draws the horizon into the sky phase's write targets. Opens its own pass (colour only, load), draws, and leaves
	 * the pass open for the caller to end with the frame's encoders. Draws nothing, and opens no pass, when there is no
	 * delegate or the pack has no sky program (a pack's {@code gbuffers_skybasic} or {@code gbuffers_basic}).
	 *
	 * @param enc      the native encoder of the clear hook
	 * @param delegate the active delegate (null: nothing is drawn)
	 * @param targets  the frame's live targets
	 * @param sky      the sky phase's targets: the pass opens on the attachment group of the sky program
	 */
	public void draw(long enc, @Nullable GbufferDelegate delegate, Targets targets, PhaseTargets sky) {
		if (this.closed || delegate == null || sky.groups()[0].length == 0) {
			return;
		}
		GbufferDelegate.Resolved pipeline = skyPipelineOf(delegate);
		if (pipeline == null || pipeline.plan == null
				|| !(pipeline.plan.variant(PassKind.GBUFFERS, GbufferPhase.SKY) instanceof GbufferPlans.PackVariant pack)) {
			return;
		}
		int group = pack.group();
		int[] layout = sky.groups()[group];
		Slot slot = this.slots[this.next];
		this.next = (this.next + 1) % SLOTS;
		this.frame.gbufferModelView(this.modelView);
		this.frame.fogColor(this.fog);
		double far = this.frame.farPlane();
		if (slot.far != far) {
			HorizonGeometry.fan(far, HorizonGeometry.SEGMENTS, this.fan);
			for (int i = 0; i < this.fan.length; i++) {
				slot.vertexMemory.setAtIndex(ValueLayout.JAVA_FLOAT, i, this.fan[i]);
			}
			slot.far = far;
		}
		writeTransforms(slot);
		long[] colours = this.colours[group];
		if (colours == null) {
			colours = new long[layout.length];
			this.colours[group] = colours;
		}
		for (int i = 0; i < layout.length; i++) {
			colours[i] = targets.write(layout[i]);
		}
		MetalBridge.renderBegin(enc, colours, null, 0, false, 0, targets.width(0), targets.height(0));
		// The phase and the label first: the delegate's phase still holds the last phase of the previous frame at the
		// clear hook (preview 9 logged the horizon's pass as TRANSLUCENT), and begin() logs the pass with both.
		delegate.reversedDepth(false);
		delegate.phase(GbufferPhase.SKY);
		delegate.notePass("Quicksilver horizon", null);
		delegate.begin(enc, false);
		delegate.openedOnGroup(group);
		int[] bound = delegate.select(enc, pipeline, null, this.uniforms);
		if (bound != null) {
			delegate.uniform(enc, TRANSFORMS, slot.transformsSlice);
			MetalBridge.vertexBuffer(enc, EngineSlots.VERTEX_DATA, MetalBridge.bufferHandle(slot.vertices), 0);
			if (delegate.beforeDraw(enc)) {
				MetalBridge.renderDraw(enc, VERTICES, 1, 0, 0);
			}
		}
		delegate.end(enc);
	}

	/** The sky pipeline's resolved plan under {@code delegate}: resolved once per delegate, then kept. */
	private GbufferDelegate.@Nullable Resolved skyPipelineOf(GbufferDelegate delegate) {
		if (delegate != this.skyPipelineDelegate) {
			this.skyPipelineDelegate = delegate;
			this.skyPipeline = delegate.resolve(PIPELINE, null);
		}
		return this.skyPipeline;
	}

	/** Writes the view rotation, the texture matrix (identity, unused), the fog colour (alpha 1) and no model offset. */
	private void writeTransforms(Slot slot) {
		MemorySegment m = slot.transformsMemory;
		for (int i = 0; i < 16; i++) {
			m.setAtIndex(ValueLayout.JAVA_FLOAT, MODEL_VIEW_AT / Float.BYTES + i, this.modelView[i]);
			m.setAtIndex(ValueLayout.JAVA_FLOAT, TEXTURE_AT / Float.BYTES + i, i % 5 == 0 ? 1f : 0f);
		}
		m.setAtIndex(ValueLayout.JAVA_FLOAT, COLOR_MODULATOR_AT / Float.BYTES, this.fog[0]);
		m.setAtIndex(ValueLayout.JAVA_FLOAT, COLOR_MODULATOR_AT / Float.BYTES + 1, this.fog[1]);
		m.setAtIndex(ValueLayout.JAVA_FLOAT, COLOR_MODULATOR_AT / Float.BYTES + 2, this.fog[2]);
		m.setAtIndex(ValueLayout.JAVA_FLOAT, COLOR_MODULATOR_AT / Float.BYTES + 3, 1f);
		for (int i = 0; i < 3; i++) {
			m.setAtIndex(ValueLayout.JAVA_FLOAT, MODEL_OFFSET_AT / Float.BYTES + i, 0f);
		}
	}

	/** Frees the ring's buffers. Idempotent; the horizon draws nothing after it. */
	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		for (Slot slot : this.slots) {
			if (slot != null) {
				MetalBridge.release(MetalBridge.bufferHandle(slot.transforms));
				MetalBridge.release(MetalBridge.bufferHandle(slot.vertices));
			}
		}
	}
}
