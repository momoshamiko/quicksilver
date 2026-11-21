// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.shaders.pack.ProgramCatalog.Phase;
import dev.mcms.shaders.runtime.compute.ComputeKernel;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One program of a dimension set after the background load (plan.md E3): the pipeline handle (0 when the program has no
 * render stages or is absent), the reflection the frame path binds with, and what the dimension load line reports. A
 * program our parser or type checker rejects has no pipeline and its diagnostics say why (ADR 0006 section 4: it goes
 * straight to absent, no standard-road try). A program whose MSL Metal rejected was tried once through the standard road;
 * when that served, the pipeline comes from the road's stages and {@code road} is true (each fallback use is a translator
 * bug to fix).
 *
 * @param name      the program's documented name (composite2)
 * @param phase     where the program runs (prepare before the world copy, the others after; final last)
 * @param pipeline  the native pipeline handle, or 0 when the program has no render stages or is absent
 * @param road      compiled through the standard road (ADR 0006 section 4)
 * @param outputs   the colour attachments the pipeline was built for (the fragment outputs that exist, in order)
 * @param blending  per output: blending enabled ({@code blend.<program>[.<buffer>]}; a blended pass loads its
 *                   target instead of discarding it, so the factors read the earlier content)
 * @param unknownUniforms uniforms no engine block serves (read as zero; logged once per program)
 * @param reflection the translator's reflection (bound slots and flags)
 * @param reason    when the program is absent: the first line of why
 * @param kernels   the program's compute kernels in dispatch order (E6: {@code .csh}, then {@code _a}..{@code _z}); they
 *                   run before the program's own stages, and a program with kernels and no render stages is compute-only
 */
public record CompiledProgram(String name, Phase phase, long pipeline, boolean road,
		List<ProgramRequest.ColorTarget> outputs, List<Boolean> blending, List<String> unknownUniforms,
		ProgramTranslation.Reflection reflection, @Nullable String reason, List<ComputeKernel> kernels) {

	public CompiledProgram {
		outputs = List.copyOf(outputs);
		blending = List.copyOf(blending);
		unknownUniforms = List.copyOf(unknownUniforms);
		kernels = List.copyOf(kernels);
	}

	/** A program with no compute kernels (every program before E6). */
	public CompiledProgram(String name, Phase phase, long pipeline, boolean road,
			List<ProgramRequest.ColorTarget> outputs, List<Boolean> blending, List<String> unknownUniforms,
			ProgramTranslation.Reflection reflection, @Nullable String reason) {
		this(name, phase, pipeline, road, outputs, blending, unknownUniforms, reflection, reason, List.of());
	}

	/** A compute-only program (E6): its kernels and no render stages. */
	public static CompiledProgram computeOnly(String name, Phase phase, List<ComputeKernel> kernels) {
		return new CompiledProgram(name, phase, 0, false, List.of(), List.of(), List.of(),
			ProgramTranslation.Reflection.none(), null, kernels);
	}

	/** This program with its compute kernels (the render part is compiled first, the kernels after). */
	public CompiledProgram withKernels(List<ComputeKernel> kernels) {
		return new CompiledProgram(this.name, this.phase, this.pipeline, this.road, this.outputs, this.blending,
			this.unknownUniforms, this.reflection, this.reason, kernels);
	}

	/** Whether the program has a render pipeline (its own vertex and fragment stages). */
	public boolean runs() {
		return this.pipeline != 0;
	}

	/** Whether the program runs anything in a frame: its render stages or its compute kernels. */
	public boolean active() {
		return this.runs() || !this.kernels.isEmpty();
	}

	public boolean finalPass() {
		return this.phase == Phase.FINAL;
	}

	public boolean isPrepare() {
		return this.phase == Phase.PREPARE;
	}
}
