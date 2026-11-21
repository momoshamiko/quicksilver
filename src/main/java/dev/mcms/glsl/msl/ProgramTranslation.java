// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl;

import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import java.util.List;

/**
 * The translator's answer for one program. {@code ok} is false when any stage failed to parse or type-check, or when
 * the emitter refused a construct; then {@code msl} is null and the diagnostics say why, each with the pack file and
 * line it came from. Reflection is complete only when {@code ok} is true.
 *
 * <p>Every pipeline keeps Metal's default clockwise front-facing winding. The vertex epilogue flips y, which turns
 * GL's counter-clockwise front faces into clockwise ones, and the runtime maps GL back-face culling to MTLCullModeBack
 * with that winding.
 */
public record ProgramTranslation(String name, boolean ok, String msl, String vertexEntry, String fragmentEntry,
		String computeEntry, Reflection reflection, List<Diagnostic> diagnostics, long translateNanos) {

	public ProgramTranslation {
		diagnostics = List.copyOf(diagnostics);
	}

	/** One diagnostic: its severity, the stage it belongs to, the pack line (when known) and the message. */
	public record Diagnostic(Severity severity, String stage, SourceLine origin, String code, String message) {
		public enum Severity { ERROR, WARNING }

		@Override
		public String toString() {
			String where = origin == null ? "" : origin.path() + ":" + origin.line() + " ";
			return severity + " " + stage + " " + where + code + ": " + message;
		}
	}

	/**
	 * What a program uses: uniforms, resources, inputs and outputs, flags and compute layout. {@code samplers} gives each stage's
	 * sampler slot for every texture the stage samples through a sampler: the index its MSL declares ({@code [[sampler(n)]]}).
	 */
	public record Reflection(List<UniformUse> uniforms, List<ResourceUse> textures, List<ResourceUse> images,
			List<ResourceUse> buffers, List<String> vertexInputs, List<OutputUse> outputs, Flags flags,
			int[] workGroupSize, int threadgroupBytes, List<Integer> feedbackTargets, List<SamplerUse> samplers) {

		public Reflection {
			uniforms = List.copyOf(uniforms);
			textures = List.copyOf(textures);
			images = List.copyOf(images);
			buffers = List.copyOf(buffers);
			vertexInputs = List.copyOf(vertexInputs);
			outputs = List.copyOf(outputs);
			feedbackTargets = List.copyOf(feedbackTargets);
			workGroupSize = workGroupSize.clone();
			samplers = List.copyOf(samplers);
		}

		/** The reflection of a program with no render stages and no compute stage of its own (E6's compute-only passes). */
		public static Reflection none() {
			return new Reflection(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				new Flags(false, false, false, false, false, false, false, false, false), new int[] {1, 1, 1}, 0, List.of(),
				List.of());
		}

		/**
		 * The sampler slot a texture's sampler takes in one stage, or -1 when the stage does not sample through it. The slot is the
		 * stage's own numbering: 0 to the stage's count minus one, without gaps.
		 */
		public int samplerSlot(String texture, Stage stage) {
			// Indexed: the pipeline switch calls this per texture and must not allocate an iterator (PipelineSwitchAllocationTest).
			for (int i = 0; i < this.samplers.size(); i++) {
				SamplerUse use = this.samplers.get(i);
				if (use.stage() == stage && use.texture().equals(texture)) {
					return use.slot();
				}
			}
			return -1;
		}

		/** One texture's sampler in one stage: the MSL sampler index that stage declares for it. */
		public record SamplerUse(String texture, Stage stage, int slot) {
		}

		/**
		 * A uniform the program reads. {@code servedBy} names the engine block that serves it, or {@code unknown} when
		 * no block serves it (it reads as zero).
		 */
		public record UniformUse(String name, String glslType, String servedBy) {
			public static final String UNKNOWN = "unknown";
		}

		/**
		 * A texture, image or buffer: its GLSL name, Metal slot, Metal type and the access the program needs. A storage buffer
		 * ({@code storage}) also names the pack's bufferObject index it binds ({@code bufferObject}; -1 for anything else).
		 * {@code levels} (textures, E9): some lookup can read a mip level other than 0, so a target's mip chain must be fresh
		 * before the program runs; false when every lookup reads level 0 only (or reads no texel).
		 */
		public record ResourceUse(String name, int slot, String mslType, String access, boolean storage, int bufferObject,
				boolean levels) {
			/** A texture, image or uniform block use (not a storage buffer) that reads no level other than 0. */
			public ResourceUse(String name, int slot, String mslType, String access) {
				this(name, slot, mslType, access, false, -1, false);
			}

			/** A use that reads no level other than 0 (images, buffers, blocks). */
			public ResourceUse(String name, int slot, String mslType, String access, boolean storage, int bufferObject) {
				this(name, slot, mslType, access, storage, bufferObject, false);
			}
		}

		/** A fragment output: its GLSL name, its location, its attachment and the components written. */
		public record OutputUse(String name, int location, int attachment, int components) {
		}

		/**
		 * Flags of the stages the program uses. {@code coversViewport}: a full-screen program's vertex stage puts the quad
		 * over the whole viewport ({@link ViewportCoverage}), so the pass writes every texel its fragments do not discard.
		 */
		public record Flags(boolean discards, boolean writesDepth, boolean readsFragCoord, boolean readsFrontFacing,
				boolean readsVertexId, boolean readsInstanceId, boolean readsPointCoord, boolean usesBarrier,
				boolean coversViewport) {
		}
	}
}
