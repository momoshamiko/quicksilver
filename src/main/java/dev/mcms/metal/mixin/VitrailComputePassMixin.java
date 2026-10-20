/*
 * MC Metal Shaders, Apache License 2.0. Original code (2026-10-12, M4).
 */
package dev.mcms.metal.mixin;

import dev.mcms.metal.MetalCompute;
import dev.mcms.metal.MetalStorage;
import dev.mcms.metal.vitrail.ComputeAccess;
import dev.vitrail.glsl.PackProgram;
import dev.vitrail.pack.model.TextureStage;
import dev.vitrail.render.ComputeShader;
import dev.vitrail.render.PackValues;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Seam part 2a (spec §5), compute: what McmsMetalCompute reads and writes of one of Vitrail's compute passes (PackCompute$Pass, a
 * private class), and the Metal kernel the pass compiled into, which its close() releases with the rest of the pass. Applied only
 * when Vitrail is loaded.
 */
@Mixin(targets = "dev.vitrail.render.PackCompute$Pass")
abstract class VitrailComputePassMixin implements ComputeAccess.Pass {
	@Shadow
	@Final
	private PackProgram.Compute compute;

	@Shadow
	@Final
	private String path;

	@Shadow
	@Final
	private String program;

	@Shadow
	@Final
	private TextureStage textureStage;

	@Shadow
	@Final
	private String label;

	@Shadow
	@Final
	private Set<String> failed;

	@Shadow
	private MappableRingBuffer block;

	@Shadow
	private List<ComputeShader.Binding> entries;

	@Shadow
	private boolean compiled;

	@Shadow
	private long sharedBytes;

	@Shadow
	private long sharedBuffer;

	@Unique
	private MetalCompute.@Nullable Kernel mcmsMetal$kernel;

	@Shadow
	private void writeBlock(PackValues values) {
	}

	@Shadow
	private String sharedMemory(String translated) {
		return translated;
	}

	@Shadow
	private boolean servesShared(String name) {
		return false;
	}

	@Shadow
	private static ByteBuffer compileSpirv(String source) {
		return null;
	}

	/** Pass.close on Metal: the kernel and the shared-memory buffer go with the pass (Vitrail's own close frees the uniform ring). */
	@Inject(method = "close", at = @At("HEAD"))
	private void mcmsMetal$close(CallbackInfo ci) {
		if (this.mcmsMetal$kernel != null) {
			this.mcmsMetal$kernel.close();
			this.mcmsMetal$kernel = null;
		}

		if (this.sharedBuffer != 0L && MetalStorage.active()) {
			MetalStorage.releaseAfterGpu(this.sharedBuffer);
			this.sharedBuffer = 0L;
		}
	}

	@Override
	public PackProgram.Compute mcmsMetal$compute() {
		return this.compute;
	}

	@Override
	public String mcmsMetal$path() {
		return this.path;
	}

	@Override
	public String mcmsMetal$label() {
		return this.label;
	}

	@Override
	public @Nullable String mcmsMetal$program() {
		return this.program;
	}

	@Override
	public @Nullable TextureStage mcmsMetal$textureStage() {
		return this.textureStage;
	}

	@Override
	public Set<String> mcmsMetal$failed() {
		return this.failed;
	}

	@Override
	public boolean mcmsMetal$compiled() {
		return this.compiled;
	}

	@Override
	public void mcmsMetal$compiled(boolean compiled) {
		this.compiled = compiled;
	}

	@Override
	public List<ComputeShader.Binding> mcmsMetal$entries() {
		return this.entries;
	}

	@Override
	public void mcmsMetal$entries(List<ComputeShader.Binding> entries) {
		this.entries = entries;
	}

	@Override
	public long mcmsMetal$sharedBytes() {
		return this.sharedBytes;
	}

	@Override
	public long mcmsMetal$sharedBuffer() {
		return this.sharedBuffer;
	}

	@Override
	public void mcmsMetal$sharedBuffer(long buffer) {
		this.sharedBuffer = buffer;
	}

	@Override
	public @Nullable MappableRingBuffer mcmsMetal$block() {
		return this.block;
	}

	@Override
	public void mcmsMetal$writeBlock(PackValues values) {
		this.writeBlock(values);
	}

	@Override
	public String mcmsMetal$sharedMemory(String translated) {
		return this.sharedMemory(translated);
	}

	@Override
	public boolean mcmsMetal$servesShared(String name) {
		return this.servesShared(name);
	}

	@Override
	public @Nullable ByteBuffer mcmsMetal$compileSpirv(String source) {
		return compileSpirv(source);
	}

	@Override
	public MetalCompute.@Nullable Kernel mcmsMetal$kernel() {
		return this.mcmsMetal$kernel;
	}

	@Override
	public void mcmsMetal$kernel(MetalCompute.@Nullable Kernel kernel) {
		this.mcmsMetal$kernel = kernel;
	}
}
