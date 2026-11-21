// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.standardroad;

import static org.lwjgl.util.spvc.Spvc.*;

import dev.mcms.glsl.glsl450.Glsl450Printer;
import dev.mcms.glsl.glsl450.Glsl450Program;
import dev.mcms.glsl.glsl450.Glsl450Program.ResourceBinding;
import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.SamplerState;
import dev.mcms.glsl.sema.Stage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.SpvcMslConstexprSampler;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;

/**
 * The standard road (ADR 0006 sections 2 and 4): a program's Vulkan GLSL 4.50 (from {@link Glsl450Printer}) compiled by
 * LWJGL shaderc to SPIR-V (Vulkan 1.0), then converted by LWJGL SPIRV-Cross to MSL 3.1 for macOS. Every resource binding
 * maps to the Metal slot the translator uses (buffers and textures by slot, texture samplers constexpr as the translator's
 * are), the vertex stage writes the clip space the translator writes (the epilogue is in the GLSL, so SPIRV-Cross does no
 * Y flip), and the entry points are named as the translator names them. Each stage is its own library: the runtime builds
 * a pipeline from two libraries, as the backend does for the two stages of a game pipeline.
 *
 * <p>This is the test oracle and the logged fallback; the translator is the main road. Only the stages' interfaces are
 * shared with it, through the printer, never code.
 */
public final class StandardRoad {

	/** The entry names the translator gives its stages (W3-B's emitter): the same names here, for the same callers. */
	public static final String VERTEX_ENTRY = "qs_vs", FRAGMENT_ENTRY = "qs_fs", COMPUTE_ENTRY = "qs_cs";

	/** One stage's MSL library and the name of its entry function. */
	public record StageMsl(Stage stage, String msl, String entry) {
	}

	/**
	 * The result: the stages' libraries when {@code ok}, else the errors (the printer's refusals, shaderc's messages,
	 * SPIRV-Cross's). The nanosecond times are the printer, shaderc and SPIRV-Cross totals.
	 */
	public record Program(String name, boolean ok, List<StageMsl> stages, List<String> errors, long printNanos,
			long shadercNanos, long spirvCrossNanos) {

		public Program {
			stages = List.copyOf(stages);
			errors = List.copyOf(errors);
		}

		/** The library of a stage, or null when the program has no such stage or it failed. */
		public StageMsl stage(Stage stage) {
			for (StageMsl s : stages) {
				if (s.stage() == stage) {
					return s;
				}
			}
			return null;
		}
	}

	private static final int SHADERC_SUCCESS = Shaderc.shaderc_compilation_status_success;

	private StandardRoad() {
	}

	/** Compiles one program through the standard road. Never throws: a failure is an error in the result. */
	public static Program compile(ProgramRequest request) {
		try {
			return compileOrThrow(request);
		} catch (Throwable t) {
			return new Program(nameOf(request), false, List.of(), List.of("standard road failed: " + describe(t)), 0, 0,
				0);
		}
	}

	private static Program compileOrThrow(ProgramRequest request) {
		long t0 = System.nanoTime();
		Glsl450Program glsl = Glsl450Printer.print(request);
		long printed = System.nanoTime() - t0;
		if (!glsl.ok()) {
			return new Program(request.name(), false, List.of(), glsl.errors(), printed, 0, 0);
		}
		List<String> errors = new ArrayList<>();
		List<StageMsl> stages = new ArrayList<>();
		long shaderc = 0;
		long spirvCross = 0;
		for (Glsl450Program.StageText st : glsl.stages()) {
			long a = System.nanoTime();
			byte[] spirv;
			try {
				spirv = compileGlsl(st.source(), st.stage(), request.name());
			} catch (Throwable t) {
				errors.add(describe(t));
				continue;
			}
			long b = System.nanoTime();
			shaderc += b - a;
			try {
				stages.add(new StageMsl(st.stage(), toMsl(spirv, st, request), entryOf(st.stage())));
			} catch (Throwable t) {
				errors.add(describe(t));
			}
			spirvCross += System.nanoTime() - b;
		}
		return new Program(request.name(), errors.isEmpty(), stages, errors, printed, shaderc, spirvCross);
	}

	private static String nameOf(ProgramRequest request) {
		try {
			return request == null ? "unknown" : request.name();
		} catch (Throwable t) {
			return "unknown";
		}
	}

	/** The throwable's class and message, so a refused program names what failed (LWJGL errors, stack overflow). */
	static String describe(Throwable t) {
		String message = t.getMessage();
		return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
	}

	static String entryOf(Stage stage) {
		return switch (stage) {
			case VERTEX -> VERTEX_ENTRY;
			case FRAGMENT -> FRAGMENT_ENTRY;
			case COMPUTE -> COMPUTE_ENTRY;
		};
	}

	/** GLSL to Vulkan 1.0 SPIR-V with shaderc, no optimization (Metal optimizes the MSL, as it does ours). */
	static byte[] compileGlsl(String source, Stage stage, String name) {
		int kind = switch (stage) {
			case VERTEX -> Shaderc.shaderc_vertex_shader;
			case FRAGMENT -> Shaderc.shaderc_fragment_shader;
			case COMPUTE -> Shaderc.shaderc_compute_shader;
		};
		long compiler = Shaderc.shaderc_compiler_initialize();
		long options = Shaderc.shaderc_compile_options_initialize();
		try {
			Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan,
				Shaderc.shaderc_env_version_vulkan_1_0);
			Shaderc.shaderc_compile_options_set_optimization_level(options,
				Shaderc.shaderc_optimization_level_zero);
			// The strings are malloc'd: LWJGL's CharSequence overload copies them onto the thread's MemoryStack, which
			// (64 KiB by default) overflows for a pack's full preprocessed program.
			ByteBuffer text = MemoryUtil.memUTF8(source, false);
			ByteBuffer file = MemoryUtil.memUTF8(name + "." + stage.word());
			ByteBuffer entry = MemoryUtil.memUTF8("main");
			long result;
			try {
				result = Shaderc.shaderc_compile_into_spv(compiler, text, kind, file, entry, options);
			} finally {
				MemoryUtil.memFree(text);
				MemoryUtil.memFree(file);
				MemoryUtil.memFree(entry);
			}
			try {
				if (Shaderc.shaderc_result_get_compilation_status(result) != SHADERC_SUCCESS) {
					throw new IllegalStateException("shaderc, " + stage.word() + " of " + name + ": "
						+ Shaderc.shaderc_result_get_error_message(result));
				}
				ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
				byte[] out = new byte[bytes.remaining()];
				bytes.get(out);
				return out;
			} finally {
				Shaderc.shaderc_result_release(result);
			}
		} finally {
			Shaderc.shaderc_compile_options_release(options);
			Shaderc.shaderc_compiler_release(compiler);
		}
	}

	/** SPIR-V to MSL with SPIRV-Cross: the translator's options and slots, the vertex stage's Y flip left to the GLSL. */
	static String toMsl(byte[] spirv, Glsl450Program.StageText st, ProgramRequest request) {
		int model = switch (st.stage()) {
			case VERTEX -> Spv.SpvExecutionModelVertex;
			case FRAGMENT -> Spv.SpvExecutionModelFragment;
			case COMPUTE -> Spv.SpvExecutionModelGLCompute;
		};
		ByteBuffer direct = ByteBuffer.allocateDirect(spirv.length).order(ByteOrder.LITTLE_ENDIAN).put(spirv).flip();
		IntBuffer words = direct.asIntBuffer();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer out = stack.callocPointer(1);
			check(0, spvc_context_create(out), "context");
			long context = out.get(0);
			try {
				check(context, spvc_context_parse_spirv(context, words, words.remaining(), out), "parse");
				long ir = out.get(0);
				check(context, spvc_context_create_compiler(context, SPVC_BACKEND_MSL, ir,
					SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, out), "compiler");
				long compiler = out.get(0);
				check(context, spvc_compiler_create_compiler_options(compiler, out), "options");
				long options = out.get(0);
				spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_VERSION, 30100);
				spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_PLATFORM, SPVC_MSL_PLATFORM_MACOS);
				spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true);
				spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_MSL_PAD_FRAGMENT_OUTPUT_COMPONENTS, true);
				spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, false);
				spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_MSL_ENABLE_POINT_SIZE_BUILTIN,
					st.stage() == Stage.VERTEX && request.drawsPoints());
				check(context, spvc_compiler_install_compiler_options(compiler, options), "install options");
				for (ResourceBinding b : st.bindings()) {
					bind(stack, context, compiler, model, b);
				}
				String entry = entryOf(st.stage());
				check(context, spvc_compiler_rename_entry_point(compiler, "main", entry, model), "entry point");
				check(context, spvc_compiler_compile(compiler, out), "compile");
				return MemoryUtil.memUTF8(out.get(0));
			} finally {
				spvc_context_destroy(context);
			}
		}
	}

	/** One binding: a buffer, a texture (with its constexpr sampler or the game's sampler slot) or an image. */
	private static void bind(MemoryStack stack, long context, long compiler, int model, ResourceBinding b) {
		SpvcMslResourceBinding rb = SpvcMslResourceBinding.calloc(stack);
		spvc_msl_resource_binding_init(rb);
		rb.stage(model).desc_set(0).binding(b.binding()).msl_buffer(b.slot()).msl_texture(b.slot())
			.msl_sampler(Math.max(0, b.samplerSlot()));
		check(context, spvc_compiler_msl_add_resource_binding(compiler, rb), "binding " + b.name());
		if (b.sampler() != null) {
			SpvcMslConstexprSampler s = SpvcMslConstexprSampler.calloc(stack);
			spvc_msl_constexpr_sampler_init(s);
			SamplerState st = b.sampler();
			s.min_filter(filter(st.filter())).mag_filter(filter(st.filter()))
				.coord(st.pixel() ? SPVC_MSL_SAMPLER_COORD_PIXEL : SPVC_MSL_SAMPLER_COORD_NORMALIZED)
				.mip_filter(st.mip() == null ? SPVC_MSL_SAMPLER_MIP_FILTER_NONE
					: st.mip() == ProgramRequest.Filter.NEAREST ? SPVC_MSL_SAMPLER_MIP_FILTER_NEAREST
					: SPVC_MSL_SAMPLER_MIP_FILTER_LINEAR)
				.s_address(address(st.wrap())).t_address(address(st.wrap())).r_address(address(st.wrap()))
				.compare_enable(st.compare()).compare_func(SPVC_MSL_SAMPLER_COMPARE_FUNC_LESS_EQUAL);
			check(context, spvc_compiler_msl_remap_constexpr_sampler_by_binding(compiler, 0, b.binding(), s),
				"constexpr sampler " + b.name());
		}
	}

	private static int filter(ProgramRequest.Filter f) {
		return f == ProgramRequest.Filter.NEAREST ? SPVC_MSL_SAMPLER_FILTER_NEAREST : SPVC_MSL_SAMPLER_FILTER_LINEAR;
	}

	private static int address(ProgramRequest.Wrap w) {
		return switch (w) {
			case REPEAT -> SPVC_MSL_SAMPLER_ADDRESS_REPEAT;
			case CLAMP_TO_EDGE -> SPVC_MSL_SAMPLER_ADDRESS_CLAMP_TO_EDGE;
			case MIRROR_REPEAT -> SPVC_MSL_SAMPLER_ADDRESS_MIRRORED_REPEAT;
		};
	}

	private static void check(long context, int result, String what) {
		if (result != SPVC_SUCCESS) {
			throw new IllegalStateException("SPIRV-Cross, " + what + ": "
				+ (context == 0 ? "no context" : spvc_context_get_last_error_string(context)));
		}
	}
}
