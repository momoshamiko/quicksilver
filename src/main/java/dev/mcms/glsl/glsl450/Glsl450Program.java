// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.glsl450;

import dev.mcms.glsl.msl.ProgramRequest.SamplerState;
import dev.mcms.glsl.sema.Stage;
import java.util.List;

/**
 * One program as Vulkan GLSL 4.50 (ADR 0006 section 4): one source per stage, each with {@code main} as its entry point.
 * The sources have explicit {@code layout(location)} for every stage interface and {@code layout(set = 0, binding)} for
 * every resource. The resource bindings say which Metal slot each binding stands for, so the standard road can map them
 * with resource-binding remaps. Errors are the printer's refusals; {@link #ok()} is false when there are any.
 */
public record Glsl450Program(String name, List<StageText> stages, List<String> errors, List<String> warnings) {

	/** The first binding number of uniform and buffer blocks; textures and images use their own slot numbers. */
	public static final int BLOCK_BINDING_BASE = 1000;

	public Glsl450Program {
		stages = List.copyOf(stages);
		errors = List.copyOf(errors);
		warnings = List.copyOf(warnings);
	}

	public boolean ok() {
		return errors.isEmpty();
	}

	/** The source of one stage, or null when the program has no such stage. */
	public StageText stage(Stage stage) {
		for (StageText s : stages) {
			if (s.stage() == stage) {
				return s;
			}
		}
		return null;
	}

	/**
	 * One stage's Vulkan GLSL. {@code bindings} lists the resources the source declares.
	 */
	public record StageText(Stage stage, String source, List<ResourceBinding> bindings) {
		public StageText {
			bindings = List.copyOf(bindings);
		}
	}

	/**
	 * One resource binding of a source. A {@link Kind#BLOCK} is a uniform or buffer block at Metal's buffer {@code slot};
	 * a {@link Kind#TEXTURE} is a sampled texture at Metal's texture {@code slot}, with its sampler: a constexpr sampler
	 * ({@code sampler} set) or the game's sampler at {@code samplerSlot}; an {@link Kind#IMAGE} is an image at texture
	 * {@code slot}. {@code binding} is the SPIR-V binding number in set 0.
	 */
	public record ResourceBinding(Kind kind, int binding, int slot, int samplerSlot, SamplerState sampler,
			String name) {

		/** The kind of a binding. */
		public enum Kind { BLOCK, TEXTURE, IMAGE }
	}
}
