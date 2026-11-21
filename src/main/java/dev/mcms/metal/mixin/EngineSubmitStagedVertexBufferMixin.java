/*
 * Quicksilver: original code (ADR 0006), Apache-2.0. The per-entity id range bookkeeping (plan.md E4).
 */
package dev.mcms.metal.mixin;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import dev.mcms.shaders.runtime.entities.EntityCapture;
import dev.mcms.shaders.runtime.gbuffers.GbufferDelegate;
import java.util.List;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records the staging byte ranges each submit's vertices wrote, per staging buffer (plan.md E4). All of a buffer's
 * draws share one staging {@link ByteBufferBuilder}; the bytes are written during {@code Model.renderToBuffer},
 * between one builder request and the next, so a range opens where a builder is handed to the draw being prepared
 * and closes where the next builder is requested or at the upload's last flush. Targets, javap -p -c against
 * minecraft-merged-deobf-26.3.jar (2026-11-14):
 *
 * <ul>
 * <li>{@code getVertexBuilder(Draw)} (returns {@code VertexConsumer}, so its handler takes
 * {@code CallbackInfoReturnable<VertexConsumer>}): at HEAD the previous draw is still {@code lastBuildingDraw} and
 * the staging write offset is the close of the previous range; the builder then handed out belongs to the prepared
 * record. The returned builder is the same one for an unchanged draw (bytecode 17-35).</li>
 * <li>{@code upload()}: the final flush is the invoke of {@code finishLastVertexBuilder} at bytecode 30; the write
 * offset read there is the staging total. Later, at the loop of bytecode 79-93, each non-empty draw gets its
 * {@code vertexOffset = Mth.roundToward(running, stride)}; at RETURN the draws carry those offsets and the ranges
 * resolve. The staging write offset is zeroed by {@code uploadDrawsToBuffers} (through {@code Draw.freeVertexData}),
 * so it is captured before, never at RETURN.</li>
 * <li>{@code endFrame()}: the buffer's teardown ({@code endDraw} at bytecode 1); the ranges reset at its RETURN.</li>
 * </ul>
 *
 * Inert while no set is active.
 */
@Mixin(StagedVertexBuffer.class)
abstract class EngineSubmitStagedVertexBufferMixin {

	@Shadow
	@Final
	private ByteBufferBuilder stagingBuffer;

	@Shadow
	@Final
	private List<StagedVertexBuffer.Draw> draws;

	/** The vertex buffer the upload wrote (javap 2026-11-14: {@code currentVertexBuffer}, set by upload before its RETURN). */
	@Shadow
	private GpuBuffer currentVertexBuffer;

	/** This buffer's ranges (created on first use; the buffer is long-lived). */
	private EntityCapture.Ranges mcms$ranges;

	/** The staging total at the upload's last flush (captured before the upload discards it). */
	private long mcms$uploadedBytes;

	private long mcms$writeOffset() {
		return ((EngineSubmitByteBufferBuilderAccessor) this.stagingBuffer).mcms$writeOffset();
	}

	private EntityCapture.Ranges mcms$ranges() {
		if (mcms$ranges == null) {
			mcms$ranges = new EntityCapture.Ranges();
		}
		return mcms$ranges;
	}

	@Inject(method = "getVertexBuilder", at = @At("HEAD"))
	private void mcms$builderRequested(StagedVertexBuffer.Draw draw, CallbackInfoReturnable<VertexConsumer> cir) {
		if (EntityCapture.active()) {
			this.mcms$ranges().builderRequested(this.mcms$writeOffset(), draw, EntityCapture.preparingIds());
		}
	}

	@Inject(method = "upload", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/StagedVertexBuffer;finishLastVertexBuilder()V"))
	private void mcms$lastBuilderFlushed(CallbackInfo ci) {
		if (EntityCapture.active()) {
			this.mcms$uploadedBytes = this.mcms$writeOffset();
			this.mcms$ranges().lastBuilderFlushed(this.mcms$uploadedBytes);
		}
	}

	@Inject(method = "upload", at = @At("RETURN"))
	private void mcms$uploaded(CallbackInfo ci) {
		if (EntityCapture.active() && !this.draws.isEmpty()) {
			EntityCapture.Ranges ranges = this.mcms$ranges();
			for (int i = 0; i < this.draws.size(); i++) {
				StagedVertexBuffer.Draw draw = this.draws.get(i);
				if (draw.isEmpty()) {
					continue;
				}
				int stride = ((EngineSubmitDrawAccessor) (Object) draw).mcms$format().getVertexSize();
				int offset = ((EngineSubmitDrawVertexOffsetAccessor) (Object) draw).mcms$vertexOffset();
				ranges.addUploadedDraw(draw, stride, offset / stride);
			}
			int[] defaults = EntityCapture.defaultIds();
			ranges.resolve(this.mcms$uploadedBytes, defaults);
			// E4-D: the vertex buffer this upload wrote serves the ids its ranges packed (GbufferDelegate.publishVertexIds).
			GbufferDelegate delegate = GbufferDelegate.active();
			if (delegate != null && this.currentVertexBuffer != null) {
				delegate.publishVertexIds(this.currentVertexBuffer, ranges, defaults);
			}
		}
	}

	@Inject(method = "endFrame", at = @At("RETURN"))
	private void mcms$frameEnded(CallbackInfo ci) {
		// Not gated on the engine: a buffer's ranges and records end with its frame, whichever state the engine is in
		// (an off period must not leave them to resolve against the next set's upload bases).
		if (mcms$ranges != null) {
			mcms$ranges.reset();
		}
		EntityCapture.frameEnded();
	}
}
