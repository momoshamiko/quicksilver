/*
 * MC Metal Shaders, Apache License 2.0. Original code (2026-10-12, M4).
 */
package dev.mcms.metal.vitrail;

import dev.mcms.metal.MetalCompute;
import dev.vitrail.glsl.PackProgram;
import dev.vitrail.pack.model.TextureStage;
import dev.vitrail.render.ComputeShader;
import dev.vitrail.render.PackValues;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.jspecify.annotations.Nullable;

/**
 * What the Metal road of Vitrail's compute passes reads and writes of Vitrail's own private state. Implemented by mixins on Vitrail's
 * classes (mixin.VitrailPackComputeMixin, mixin.VitrailComputePassMixin); called by dev.vitrail.render.McmsMetalCompute.
 */
public final class ComputeAccess {
	private ComputeAccess() {
	}

	/** dev.vitrail.render.PackCompute. */
	public interface Computes {
		List<?> mcmsMetal$passes();

		boolean mcmsMetal$announced();

		void mcmsMetal$announced(boolean announced);

		Set<String> mcmsMetal$announcedChains();
	}

	/** dev.vitrail.render.PackCompute$Pass, plus the Metal kernel it compiled into. */
	public interface Pass {
		PackProgram.Compute mcmsMetal$compute();

		String mcmsMetal$path();

		String mcmsMetal$label();

		/** The full screen pass this compute hangs off, or null for a shadow compute. */
		@Nullable String mcmsMetal$program();

		@Nullable TextureStage mcmsMetal$textureStage();

		Set<String> mcmsMetal$failed();

		boolean mcmsMetal$compiled();

		void mcmsMetal$compiled(boolean compiled);

		List<ComputeShader.Binding> mcmsMetal$entries();

		void mcmsMetal$entries(List<ComputeShader.Binding> entries);

		long mcmsMetal$sharedBytes();

		long mcmsMetal$sharedBuffer();

		void mcmsMetal$sharedBuffer(long buffer);

		@Nullable MappableRingBuffer mcmsMetal$block();

		/** Pass.writeBlock: the pack's uniforms into the ring buffer's current buffer (allocating the ring the first time). */
		void mcmsMetal$writeBlock(PackValues values);

		/** Pass.sharedMemory: the text to compile, its shared variables moved into a buffer where they exceed Metal's threadgroup memory. */
		String mcmsMetal$sharedMemory(String translated);

		boolean mcmsMetal$servesShared(String name);

		/** Pass.compileSpirv (shaderc, compute kind). */
		@Nullable ByteBuffer mcmsMetal$compileSpirv(String source);

		MetalCompute.@Nullable Kernel mcmsMetal$kernel();

		void mcmsMetal$kernel(MetalCompute.@Nullable Kernel kernel);
	}
}
