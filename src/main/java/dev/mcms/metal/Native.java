/*
 * Derived from mcopt v0.3.0-alpha.2 (https://github.com/noahdunnagan/mcopt), metal/src/main/java/mcopt/metal/Native.java.
 * Copyright Noah Dunnagan, Apache License 2.0. Changed by MC Metal Shaders, 2026-10-11: package renamed to dev.mcms.metal; the
 * library is libmcmsmetal.dylib (same mc_* symbols); cut every FrameCapture hook from the wrappers and the bindings nothing binds
 * anymore after the cut (mc_cpu_flags, the arena pool, mc_texture_resident); bindings for mc_enc_commit_early and
 * mc_blit_generate_mipmaps added. 2026-10-12 (M4): bindings for storage images and buffers, compute passes, box copies and
 * per-stage sampler slots. 2026-10-13 (M5): bindings for the VSync-on wait, the memory counters and the failed-command-buffer count.
 * After M5: bindings for mc_vsync_view (the display link follows the game window's display, RIL-476) and mc_vsync_wait_next
 * (-Dmcms.metal.vsyncLowLatency). 2026-10-16 (RIL-481): binding for mc_layer_colorspace_report. 2026-10-18 (ADR 0002, the settings
 * page): bindings for mc_layer_colorspace and mc_layer_hud.
 */
package dev.mcms.metal;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.Arena;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Downcalls into libmcmsmetal.dylib. Every pointer crosses as a long (arm64 passes both in x registers).
 * Encoder calls are linked "critical": no thread-state transition, so a draw costs a few nanoseconds of glue.
 */
final class Native {
	private static final Linker LINKER = Linker.nativeLinker();
	private static final SymbolLookup LIB = SymbolLookup.libraryLookup(extract(), Arena.global());

	private static final MethodHandle CREATE = fn("mc_create", false, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT);
	private static final MethodHandle MAX_BUFFER_LENGTH = fn("mc_max_buffer_length", true, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle RELEASE = fn("mc_release", false, null, JAVA_LONG);
	private static final MethodHandle BUFFER_NEW = fn("mc_buffer_new", false, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle BUFFER_PRIVATE = fn("mc_buffer_private", false, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle BUFFER_CONTENTS = fn("mc_buffer_contents", true, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle TEXTURE_NEW = fn("mc_texture_new", false, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle TEXTURE_VIEW = fn("mc_texture_view", false, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT);
	private static final MethodHandle TEXTURE_BUFFER = fn("mc_texture_buffer", false, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle TEXTURE_BUFFER_ALIGNMENT = fn("mc_texture_buffer_alignment", true, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle SAMPLER_NEW = fn("mc_sampler_new", false, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_FLOAT);
	private static final MethodHandle LIBRARY_NEW = fn("mc_library_new", false, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle PIPELINE_NEW = fn("mc_pipeline_new", false, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle DEPTH_STATE_NEW = fn("mc_depth_state_new", false, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT);
	private static final MethodHandle ENC_NEW = fn("mc_enc_new", false, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle ENC_COMMIT = fn("mc_enc_commit", false, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle ENC_COMMIT_EARLY = fn("mc_enc_commit_early", false, null, JAVA_LONG);
	private static final MethodHandle CMD_WAIT = fn("mc_cmd_wait", false, null, JAVA_LONG);
	private static final MethodHandle CMD_DONE = fn("mc_cmd_done", true, JAVA_INT, JAVA_LONG);
	private static final MethodHandle ENC_EMPTY = fn("mc_enc_empty", true, JAVA_INT, JAVA_LONG);
	private static final MethodHandle CMD_GPU_END = fn("mc_cmd_gpu_end", false, JAVA_DOUBLE, JAVA_LONG);
	private static final MethodHandle CMD_GPU_START = fn("mc_cmd_gpu_start", false, JAVA_DOUBLE, JAVA_LONG);
	private static final MethodHandle HOST_SECONDS = fn("mc_host_seconds", false, JAVA_DOUBLE);
	private static final MethodHandle CADENCE_PRESENT = fn("mc_cadence_present", false, null, JAVA_LONG, JAVA_INT);
	private static final MethodHandle CADENCE_HANDLER = fn("mc_cadence_handler", false, JAVA_LONG, JAVA_INT);
	private static final MethodHandle CADENCE_PRESENTED = fn("mc_cadence_presented", false, JAVA_LONG, JAVA_INT);
	private static final MethodHandle CMD_GPU_US = fn("mc_cmd_gpu_us", false, JAVA_DOUBLE, JAVA_LONG);
	private static final MethodHandle DIAG_ENABLE = fn("mc_diag_enable", false, null, JAVA_DOUBLE);
	private static final MethodHandle GPUCOUNT_ENABLE = fn("mc_gpucount_enable", false, null, JAVA_LONG, JAVA_INT, JAVA_INT);  // -Dmcms.metal.gpuCount (mcmetal.m, GPU counting)
	private static final MethodHandle GPUCOUNT_LABEL = fn("mc_gpucount_label", false, null, JAVA_LONG);
	private static final MethodHandle BUFFER_LENGTH = fn("mc_buffer_length", true, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle POOL_ADD = fn("mc_pool_add", false, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle POOL_TAKE = fn("mc_pool_take", false, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle PROFILE_BEGIN = fn("mc_profile_begin", false, JAVA_INT, JAVA_LONG, JAVA_INT);
	private static final MethodHandle RP_LOG = fn("mc_set_rp_log", false, null, JAVA_INT);
	private static final MethodHandle PROFILE_END = fn("mc_profile_end", false, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle PROFILE_READ = fn("mc_profile_read", false, null, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG);
	private static final MethodHandle PROFILE_READ_ABS = fn("mc_profile_read_abs", false, null, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG);
	private static final MethodHandle PROFILE_COUNT = fn("mc_profile_count", true, JAVA_INT, JAVA_LONG);
	private static final MethodHandle DEVICE_ALLOCATED = fn("mc_device_allocated", false, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle PASS_TEST = fn("mc_pass_test", false, null, JAVA_LONG, JAVA_INT, JAVA_INT);
	private static final MethodHandle ENC_COUNTERS = fn("mc_enc_counters", true, null, JAVA_LONG);
	private static final MethodHandle PROFILE_LABELS = fn("mc_profile_labels", false, null, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT);
	private static final MethodHandle BLIT_BUFFER = fn("mc_blit_buffer", false, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle BLIT_FILL = fn("mc_blit_fill", false, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle BLIT_BUFFER_TO_TEXTURE = fn("mc_blit_buffer_to_texture", false, null,
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle BLIT_TEXTURE_TO_BUFFER = fn("mc_blit_texture_to_buffer", false, null,
		JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle BLIT_TEXTURE_TO_TEXTURE = fn("mc_blit_texture_to_texture", false, null,
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle BLIT_GENERATE_MIPMAPS = fn("mc_blit_generate_mipmaps", false, JAVA_INT, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle RENDER_BEGIN = fn("mc_render_begin", false, JAVA_INT,
		JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_FLOAT, JAVA_INT, JAVA_INT);
	private static final MethodHandle DISCARD = fn("mc_discard", true, null, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle R_PIPELINE = fn("mc_r_pipeline", true, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_FLOAT, JAVA_FLOAT, JAVA_INT);
	private static final MethodHandle R_BUFFER = fn("mc_r_buffer", true, null, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle R_VERTEX_BUFFER = fn("mc_r_vertex_buffer", true, null, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle R_BYTES = fn("mc_r_bytes", true, null, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT);
	private static final MethodHandle R_TEXTURE = fn("mc_r_texture", true, null, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle R_SCISSOR = fn("mc_r_scissor", true, null, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle R_INDEX = fn("mc_r_index", true, null, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle R_USE = fn("mc_r_use", true, null, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle GPU_ADDRESS = fn("mc_buffer_gpu_address", true, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle PRE_BLIT_BUFFER = fn("mc_pre_blit_buffer", true, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle PRE_FILL = fn("mc_pre_fill", true, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle RENDER_RESUME = fn("mc_render_resume", false, null, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle R_DRAW = fn("mc_r_draw", true, null, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle R_DRAW_INDEXED = fn("mc_r_draw_indexed", true, null, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle R_MULTI_DRAW_INDEXED = fn("mc_r_multi_draw_indexed", true, null, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle R_MULTI_DRAW_INDEXED_SEPARATE = fn("mc_r_multi_draw_indexed_separate", true, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle R_MULTI_DRAW = fn("mc_r_multi_draw", true, null, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle R_MULTI_DRAW_SEPARATE = fn("mc_r_multi_draw_separate", true, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle R_DRAW_INDEXED_INDIRECT = fn("mc_r_draw_indexed_indirect", true, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle R_DRAW_INDIRECT = fn("mc_r_draw_indirect", true, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle R_DEBUG_PUSH = fn("mc_r_debug_push", false, null, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle R_DEBUG_POP = fn("mc_r_debug_pop", true, null, JAVA_LONG);
	private static final MethodHandle CLEAR_RECT = fn("mc_clear_rect", false, null, JAVA_LONG, JAVA_LONG, JAVA_LONG,
		JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle LAYER_CONFIGURE = fn("mc_layer_configure", false, null, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle LAYER_NEXT = fn("mc_layer_next", false, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle PRESENT = fn("mc_present", false, null, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle PRESENT_QUEUED = fn("mc_present_queued", false, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle PRESENT_SKIPPED = fn("mc_present_skipped", false, JAVA_LONG);
	private static final MethodHandle PRESENT_QUEUED_ACQUIRE = fn("mc_present_queued_acquire", false, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle PRESENT_DROPPED = fn("mc_present_dropped", false, JAVA_LONG);
	private static final MethodHandle PACE_ADAPT = fn("mc_pace_adapt", false, null, JAVA_INT);
	private static final MethodHandle PACE_EXTRA_MS = fn("mc_pace_extra_ms", false, JAVA_DOUBLE);
	private static final MethodHandle PACE = fn("mc_pace", false, JAVA_INT, JAVA_DOUBLE);
	private static final MethodHandle SLEEP_PRECISE = fn("mc_sleep_precise", false, null, JAVA_LONG);
	// M5: the VSync-on wait, memory counters, failed command buffers (mcmetal.m, "M5")
	private static final MethodHandle VSYNC_WAIT = fn("mc_vsync_wait", false, JAVA_DOUBLE, JAVA_DOUBLE);
	private static final MethodHandle VSYNC_TEST = fn("mc_vsync_test", false, null, JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE);
	// RIL-476: the game's view, whose window's display the display link follows
	private static final MethodHandle VSYNC_VIEW = fn("mc_vsync_view", false, null, JAVA_LONG);
	// RIL-481 (colors check): the colour spaces of the layer, its window and the window's screen, as text
	private static final MethodHandle LAYER_COLORSPACE_REPORT = fn("mc_layer_colorspace_report", false, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	// ADR 0002 (the settings page): the layer's colour space now (0 none, 1 sRGB, 2 Display P3), and Apple's Metal HUD on or off
	private static final MethodHandle LAYER_COLORSPACE = fn("mc_layer_colorspace", false, null, JAVA_LONG, JAVA_INT);
	private static final MethodHandle LAYER_HUD = fn("mc_layer_hud", false, null, JAVA_LONG, JAVA_INT);
	// -Dmcms.metal.vsyncLowLatency (experiment): the VSync-on wait after the present, before the next frame's CPU work
	private static final MethodHandle VSYNC_WAIT_NEXT = fn("mc_vsync_wait_next", false, JAVA_DOUBLE, JAVA_DOUBLE);
	private static final MethodHandle MEM_RESIDENT = fn("mc_mem_resident", false, JAVA_LONG);
	private static final MethodHandle MEM_FOOTPRINT = fn("mc_mem_footprint", false, JAVA_LONG);
	private static final MethodHandle MEM_METAL = fn("mc_mem_metal", false, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle CB_ERRORS = fn("mc_cb_errors", false, JAVA_LONG);
	private static final MethodHandle CB_LAST_ERROR = fn("mc_cb_last_error", false, JAVA_LONG);
	private static final MethodHandle TRACK_ON = fn("mc_track_on", false, null);
	private static final MethodHandle TRACK_COUNT = fn("mc_track_count", false, JAVA_LONG, JAVA_INT);
	private static final MethodHandle TRACK_BYTES = fn("mc_track_bytes", false, JAVA_LONG, JAVA_INT);
	// M4: storage images and buffers, compute passes (mcmetal.m, "M4")
	private static final MethodHandle TEXTURE_NEW_SHAPED = fn("mc_texture_new_shaped", false, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle TEXTURE_VIEW_FORMAT = fn("mc_texture_view_format", false, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle BUFFER_NEW_PRIVATE = fn("mc_buffer_new_private", false, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle COMPUTE_PIPELINE_NEW = fn("mc_compute_pipeline_new", false, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG);
	private static final MethodHandle C_PIPELINE = fn("mc_c_pipeline", false, null, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle C_BUFFER = fn("mc_c_buffer", false, null, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle C_TEXTURE = fn("mc_c_texture", false, null, JAVA_LONG, JAVA_INT, JAVA_LONG);
	private static final MethodHandle C_SAMPLER = fn("mc_c_sampler", false, null, JAVA_LONG, JAVA_INT, JAVA_LONG);
	private static final MethodHandle C_DISPATCH = fn("mc_c_dispatch", false, null, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle CLEAR_STORAGE = fn("mc_clear_storage", false, null, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle BLIT_TEXTURE_BOX = fn("mc_blit_texture_box", false, null,
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle R_TEXTURE_SLOTS = fn("mc_r_texture_slots", true, null, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT);
	private static final MethodHandle END_ENCODERS = fn("mc_end_encoders", false, null, JAVA_LONG);

	private Native() {
	}

	/** The loaded libmcmetal, for MetalBridge: the shaderpack runtime binds its own functions (mcpack.m) from the same image. */
	static SymbolLookup lookup() {
		return LIB;
	}

	private static Path extract() {
		try (InputStream in = Native.class.getResourceAsStream("/natives/libmcmsmetal.dylib")) {
			Path dir = Files.createTempDirectory("mcmsmetal");
			Path lib = dir.resolve("libmcmsmetal.dylib");
			Files.copy(in, lib, StandardCopyOption.REPLACE_EXISTING);
			lib.toFile().deleteOnExit();
			dir.toFile().deleteOnExit();
			return lib;
		} catch (IOException e) {
			throw new IllegalStateException("can't extract libmcmsmetal.dylib", e);
		}
	}

	private static MethodHandle fn(String name, boolean critical, MemoryLayout result, MemoryLayout... args) {
		FunctionDescriptor d = result == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args);
		var symbol = LIB.find(name).orElseThrow(() -> new IllegalStateException("missing native symbol " + name));
		return critical ? LINKER.downcallHandle(symbol, d, Linker.Option.critical(false)) : LINKER.downcallHandle(symbol, d);
	}

	// The wrappers below exist only to turn invokeExact's Throwable into unchecked exceptions.

	static long create(long name, int nameCap, long err, int errCap) { try { long c = (long) CREATE.invokeExact(name, nameCap, err, errCap); return c; } catch (Throwable t) { throw rethrow(t); } }
	static long maxBufferLength(long ctx) { try { return (long) MAX_BUFFER_LENGTH.invokeExact(ctx); } catch (Throwable t) { throw rethrow(t); } }
	static void release(long obj) { try { RELEASE.invokeExact(obj); } catch (Throwable t) { throw rethrow(t); } }
	static long bufferNew(long ctx, long size) { try { return (long) BUFFER_NEW.invokeExact(ctx, size); } catch (Throwable t) { throw rethrow(t); } }
	static long bufferPrivate(long ctx, long src, long size) { try { return (long) BUFFER_PRIVATE.invokeExact(ctx, src, size); } catch (Throwable t) { throw rethrow(t); } }
	static long bufferContents(long buffer) { try { return (long) BUFFER_CONTENTS.invokeExact(buffer); } catch (Throwable t) { throw rethrow(t); } }
	static long textureNew(long ctx, int format, int w, int h, int layers, int mips, int usage, int cube) { try { return (long) TEXTURE_NEW.invokeExact(ctx, format, w, h, layers, mips, usage, cube); } catch (Throwable t) { throw rethrow(t); } }
	static long textureView(long texture, int baseMip, int mips) { try { return (long) TEXTURE_VIEW.invokeExact(texture, baseMip, mips); } catch (Throwable t) { throw rethrow(t); } }
	static long textureBuffer(long ctx, long buffer, int format, long offset, long length, int texelSize) { try { return (long) TEXTURE_BUFFER.invokeExact(ctx, buffer, format, offset, length, texelSize); } catch (Throwable t) { throw rethrow(t); } }
	static long textureBufferAlignment(long ctx, int format) { try { return (long) TEXTURE_BUFFER_ALIGNMENT.invokeExact(ctx, format); } catch (Throwable t) { throw rethrow(t); } }
	static long samplerNew(long ctx, int u, int v, int min, int mag, int mip, int aniso, float maxLod) { try { long h = (long) SAMPLER_NEW.invokeExact(ctx, u, v, min, mag, mip, aniso, maxLod); return h; } catch (Throwable t) { throw rethrow(t); } }
	static long libraryNew(long ctx, long source, long err, int errCap) { try { long h = (long) LIBRARY_NEW.invokeExact(ctx, source, err, errCap); return h; } catch (Throwable t) { throw rethrow(t); } }
	static long pipelineNew(long ctx, long vlib, long vname, long flib, long fname, long desc, long err, int errCap) { try { long h = (long) PIPELINE_NEW.invokeExact(ctx, vlib, vname, flib, fname, desc, err, errCap); return h; } catch (Throwable t) { throw rethrow(t); } }
	static long depthStateNew(long ctx, int compare, int write) { try { long h = (long) DEPTH_STATE_NEW.invokeExact(ctx, compare, write); return h; } catch (Throwable t) { throw rethrow(t); } }
	static long encNew(long ctx) { try { return (long) ENC_NEW.invokeExact(ctx); } catch (Throwable t) { throw rethrow(t); } }
	static long encCommit(long enc) { try { long c = (long) ENC_COMMIT.invokeExact(enc); return c; } catch (Throwable t) { throw rethrow(t); } }
	static void encCommitEarly(long enc) { try { ENC_COMMIT_EARLY.invokeExact(enc); } catch (Throwable t) { throw rethrow(t); } }
	static void cmdWait(long cmd) { try { CMD_WAIT.invokeExact(cmd); } catch (Throwable t) { throw rethrow(t); } }
	static boolean cmdDone(long cmd) { try { return (int) CMD_DONE.invokeExact(cmd) != 0; } catch (Throwable t) { throw rethrow(t); } }
	static boolean encEmpty(long enc) { try { return (int) ENC_EMPTY.invokeExact(enc) != 0; } catch (Throwable t) { throw rethrow(t); } }
	static double cmdGpuEnd(long cmd) { try { return (double) CMD_GPU_END.invokeExact(cmd); } catch (Throwable t) { throw rethrow(t); } }
	static double cmdGpuStart(long cmd) { try { return (double) CMD_GPU_START.invokeExact(cmd); } catch (Throwable t) { throw rethrow(t); } }
	static double hostSeconds() { try { return (double) HOST_SECONDS.invokeExact(); } catch (Throwable t) { throw rethrow(t); } }
	static void cadencePresent(long drawable, int slot) { try { CADENCE_PRESENT.invokeExact(drawable, slot); } catch (Throwable t) { throw rethrow(t); } }
	static long cadenceHandler(int slot) { try { return (long) CADENCE_HANDLER.invokeExact(slot); } catch (Throwable t) { throw rethrow(t); } }
	static long cadencePresented(int slot) { try { return (long) CADENCE_PRESENTED.invokeExact(slot); } catch (Throwable t) { throw rethrow(t); } }
	static double cmdGpuMicros(long cmd) { try { return (double) CMD_GPU_US.invokeExact(cmd); } catch (Throwable t) { throw rethrow(t); } }
	static void diagEnable(double thresholdMs) { try { DIAG_ENABLE.invokeExact(thresholdMs); } catch (Throwable t) { throw rethrow(t); } }
	static void gpuCountEnable(String path, int every, boolean groups) {
		try (Arena a = Arena.ofConfined()) { GPUCOUNT_ENABLE.invokeExact(a.allocateFrom(path).address(), every, groups ? 1 : 0); } catch (Throwable t) { throw rethrow(t); }
	}
	static void gpuCountLabel(String name) {
		try (Arena a = Arena.ofConfined()) { GPUCOUNT_LABEL.invokeExact(a.allocateFrom(name).address()); } catch (Throwable t) { throw rethrow(t); }
	}
	static long bufferLength(long buffer) { try { return (long) BUFFER_LENGTH.invokeExact(buffer); } catch (Throwable t) { throw rethrow(t); } }
	static boolean poolAdd(long ctx, long size, int delayMs) { try { return (int) POOL_ADD.invokeExact(ctx, size, delayMs) != 0; } catch (Throwable t) { throw rethrow(t); } }
	static long poolTake(long minLength, long maxLength) { try { return (long) POOL_TAKE.invokeExact(minLength, maxLength); } catch (Throwable t) { throw rethrow(t); } }
	/** Measurement: log every render encoder's load / store actions (the traced submit only). */
	static void rpLog(boolean on) { try { RP_LOG.invokeExact(on ? 1 : 0); } catch (Throwable t) { throw rethrow(t); } }
	static boolean profileBegin(long enc, int maxEncoders) { try { return (int) PROFILE_BEGIN.invokeExact(enc, maxEncoders) != 0; } catch (Throwable t) { throw rethrow(t); } }
	static long profileEnd(long enc, long countOut) { try { return (long) PROFILE_END.invokeExact(enc, countOut); } catch (Throwable t) { throw rethrow(t); } }
	static void profileRead(long ctx, long samples, int count, long out) { try { PROFILE_READ.invokeExact(ctx, samples, count, out); } catch (Throwable t) { throw rethrow(t); } }
	static void profileReadAbs(long ctx, long samples, int count, long out) { try { PROFILE_READ_ABS.invokeExact(ctx, samples, count, out); } catch (Throwable t) { throw rethrow(t); } }
	static void profileLabels(long enc, long out, int stride, int max) { try { PROFILE_LABELS.invokeExact(enc, out, stride, max); } catch (Throwable t) { throw rethrow(t); } }
	static void encCounters(long out) { try { ENC_COUNTERS.invokeExact(out); } catch (Throwable t) { throw rethrow(t); } }
	static void passTest(long enc, int n, int mode) { try { PASS_TEST.invokeExact(enc, n, mode); } catch (Throwable t) { throw rethrow(t); } }
	static long deviceAllocated(long ctx) { try { return (long) DEVICE_ALLOCATED.invokeExact(ctx); } catch (Throwable t) { throw rethrow(t); } }
	static int profileCount(long enc) { try { return (int) PROFILE_COUNT.invokeExact(enc); } catch (Throwable t) { throw rethrow(t); } }
	static void blitBuffer(long enc, long src, long srcOff, long dst, long dstOff, long size) { try { BLIT_BUFFER.invokeExact(enc, src, srcOff, dst, dstOff, size); } catch (Throwable t) { throw rethrow(t); } }
	static void fillBuffer(long enc, long buffer, long offset, long length, int value) { try { BLIT_FILL.invokeExact(enc, buffer, offset, length, value); } catch (Throwable t) { throw rethrow(t); } }
	static void blitBufferToTexture(long enc, long src, long off, int bytesPerRow, int bytesPerImage, long dst, int slice, int mip, int x, int y, int w, int h) { try { BLIT_BUFFER_TO_TEXTURE.invokeExact(enc, src, off, bytesPerRow, bytesPerImage, dst, slice, mip, x, y, w, h); } catch (Throwable t) { throw rethrow(t); } }
	static void blitTextureToBuffer(long enc, long src, int mip, int x, int y, int w, int h, long dst, long off, int bytesPerRow) { try { BLIT_TEXTURE_TO_BUFFER.invokeExact(enc, src, mip, x, y, w, h, dst, off, bytesPerRow); } catch (Throwable t) { throw rethrow(t); } }
	static void blitTextureToTexture(long enc, long src, long dst, int mip, int dx, int dy, int sx, int sy, int w, int h) { try { BLIT_TEXTURE_TO_TEXTURE.invokeExact(enc, src, dst, mip, dx, dy, sx, sy, w, h); } catch (Throwable t) { throw rethrow(t); } }
	/** 1: the chain's fill is recorded on the blit encoder; 0: the texture's format can't be reduced, nothing recorded. */
	static int blitGenerateMipmaps(long enc, long texture) { try { return (int) BLIT_GENERATE_MIPMAPS.invokeExact(enc, texture); } catch (Throwable t) { throw rethrow(t); } }
	/** 0: opened a new render encoder; 1: continued the open one (same attachments); 2: continued it after clearing depth in place. */
	static int renderBegin(long enc, int count, long colors, long clears, long depth, int clearDepth, float depthValue, int w, int h) { try { return (int) RENDER_BEGIN.invokeExact(enc, count, colors, clears, depth, clearDepth, depthValue, w, h); } catch (Throwable t) { throw rethrow(t); } }
	static void discard(long enc, long texture) { try { DISCARD.invokeExact(enc, texture); } catch (Throwable t) { throw rethrow(t); } }
	static void pipeline(long enc, long pso, long depth, int cull, int wireframe, float biasConstant, float biasSlope, int primitive) { try { R_PIPELINE.invokeExact(enc, pso, depth, cull, wireframe, biasConstant, biasSlope, primitive); } catch (Throwable t) { throw rethrow(t); } }
	static void buffer(long enc, int index, long buffer, long offset) { try { R_BUFFER.invokeExact(enc, index, buffer, offset); } catch (Throwable t) { throw rethrow(t); } }
	static void vertexBuffer(long enc, int index, long buffer, long offset) { try { R_VERTEX_BUFFER.invokeExact(enc, index, buffer, offset); } catch (Throwable t) { throw rethrow(t); } }
	static void bytes(long enc, int index, long bytes, int length) { try { R_BYTES.invokeExact(enc, index, bytes, length); } catch (Throwable t) { throw rethrow(t); } }
	static void texture(long enc, int index, long texture, long sampler) { try { R_TEXTURE.invokeExact(enc, index, texture, sampler); } catch (Throwable t) { throw rethrow(t); } }
	static void scissor(long enc, int x, int y, int w, int h) { try { R_SCISSOR.invokeExact(enc, x, y, w, h); } catch (Throwable t) { throw rethrow(t); } }
	static void index(long enc, long buffer, int intIndices) { try { R_INDEX.invokeExact(enc, buffer, intIndices); } catch (Throwable t) { throw rethrow(t); } }
	static void useResource(long enc, long buffer) { try { R_USE.invokeExact(enc, buffer); } catch (Throwable t) { throw rethrow(t); } }
	static long gpuAddress(long buffer) { try { return (long) GPU_ADDRESS.invokeExact(buffer); } catch (Throwable t) { throw rethrow(t); } }
	static void preBlitBuffer(long enc, long src, long srcOffset, long dst, long dstOffset, long size) { try { PRE_BLIT_BUFFER.invokeExact(enc, src, srcOffset, dst, dstOffset, size); } catch (Throwable t) { throw rethrow(t); } }
	static void preFill(long enc, long buffer, long offset, long length, int value) { try { PRE_FILL.invokeExact(enc, buffer, offset, length, value); } catch (Throwable t) { throw rethrow(t); } }
	static void renderResume(long enc, long compute) { try { RENDER_RESUME.invokeExact(enc, compute); } catch (Throwable t) { throw rethrow(t); } }
	static void draw(long enc, int count, int instances, int first, int firstInstance) { try { R_DRAW.invokeExact(enc, count, instances, first, firstInstance); } catch (Throwable t) { throw rethrow(t); } }
	static void drawIndexed(long enc, int count, int instances, int firstIndex, int baseVertex, int firstInstance) { try { R_DRAW_INDEXED.invokeExact(enc, count, instances, firstIndex, baseVertex, firstInstance); } catch (Throwable t) { throw rethrow(t); } }
	static void multiDrawIndexed(long enc, long params, int drawCount, int instances, int firstInstance) { try { R_MULTI_DRAW_INDEXED.invokeExact(enc, params, drawCount, instances, firstInstance); } catch (Throwable t) { throw rethrow(t); } }
	static void multiDrawIndexedSeparate(long enc, long offsets, long counts, long baseVertices, int drawCount) { try { R_MULTI_DRAW_INDEXED_SEPARATE.invokeExact(enc, offsets, counts, baseVertices, drawCount); } catch (Throwable t) { throw rethrow(t); } }
	static void multiDraw(long enc, long params, int drawCount, int instances, int firstInstance) { try { R_MULTI_DRAW.invokeExact(enc, params, drawCount, instances, firstInstance); } catch (Throwable t) { throw rethrow(t); } }
	static void multiDrawSeparate(long enc, long firsts, long counts, int drawCount) { try { R_MULTI_DRAW_SEPARATE.invokeExact(enc, firsts, counts, drawCount); } catch (Throwable t) { throw rethrow(t); } }
	static void drawIndexedIndirect(long enc, long buffer, long offset, int drawCount) { try { R_DRAW_INDEXED_INDIRECT.invokeExact(enc, buffer, offset, drawCount); } catch (Throwable t) { throw rethrow(t); } }
	static void drawIndirect(long enc, long buffer, long offset, int drawCount) { try { R_DRAW_INDIRECT.invokeExact(enc, buffer, offset, drawCount); } catch (Throwable t) { throw rethrow(t); } }
	static void debugPush(long enc, long label) { try { R_DEBUG_PUSH.invokeExact(enc, label); } catch (Throwable t) { throw rethrow(t); } }
	static void debugPop(long enc) { try { R_DEBUG_POP.invokeExact(enc); } catch (Throwable t) { throw rethrow(t); } }
	static void clearRect(long enc, long color, long depth, float r, float g, float b, float a, float depthValue, int x, int y, int w, int h, int mip) { try { CLEAR_RECT.invokeExact(enc, color, depth, r, g, b, a, depthValue, x, y, w, h, mip); } catch (Throwable t) { throw rethrow(t); } }
	static void layerConfigure(long ctx, long layer, int w, int h, int vsync) { try { LAYER_CONFIGURE.invokeExact(ctx, layer, w, h, vsync); } catch (Throwable t) { throw rethrow(t); } }
	static long layerNext(long layer) { try { return (long) LAYER_NEXT.invokeExact(layer); } catch (Throwable t) { throw rethrow(t); } }
	static void present(long enc, long drawable, long texture) { try { PRESENT.invokeExact(enc, drawable, texture); } catch (Throwable t) { throw rethrow(t); } }
	static int presentQueued(long enc, long drawable, long texture) { try { return (int) PRESENT_QUEUED.invokeExact(enc, drawable, texture); } catch (Throwable t) { throw rethrow(t); } }
	static long presentSkipped() { try { return (long) PRESENT_SKIPPED.invokeExact(); } catch (Throwable t) { throw rethrow(t); } }
	static int presentQueuedAcquire(long enc, long layer, long texture, long cadence) { try { return (int) PRESENT_QUEUED_ACQUIRE.invokeExact(enc, layer, texture, cadence); } catch (Throwable t) { throw rethrow(t); } }
	static void paceAdapt(int on) { try { PACE_ADAPT.invokeExact(on); } catch (Throwable t) { throw rethrow(t); } }
	static double paceExtraMs() { try { return (double) PACE_EXTRA_MS.invokeExact(); } catch (Throwable t) { throw rethrow(t); } }
	static long presentDropped() { try { return (long) PRESENT_DROPPED.invokeExact(); } catch (Throwable t) { throw rethrow(t); } }
	static void sleepPrecise(long ns) { try { SLEEP_PRECISE.invokeExact(ns); } catch (Throwable t) { throw rethrow(t); } }
	static boolean pace(double marginSeconds) { try { return (int) PACE.invokeExact(marginSeconds) != 0; } catch (Throwable t) { throw rethrow(t); } }
	static double vsyncWait(double offsetSeconds) { try { return (double) VSYNC_WAIT.invokeExact(offsetSeconds); } catch (Throwable t) { throw rethrow(t); } }
	static void vsyncTest(int mode, double fromSeconds, double lengthSeconds) { try { VSYNC_TEST.invokeExact(mode, fromSeconds, lengthSeconds); } catch (Throwable t) { throw rethrow(t); } }
	static void vsyncView(long view) { try { VSYNC_VIEW.invokeExact(view); } catch (Throwable t) { throw rethrow(t); } }
	static int layerColorspaceReport(long view, long layer, long out, int cap) { try { return (int) LAYER_COLORSPACE_REPORT.invokeExact(view, layer, out, cap); } catch (Throwable t) { throw rethrow(t); } }
	static void layerColorspace(long layer, int colorspace) { try { LAYER_COLORSPACE.invokeExact(layer, colorspace); } catch (Throwable t) { throw rethrow(t); } }
	static void layerHud(long layer, int on) { try { LAYER_HUD.invokeExact(layer, on); } catch (Throwable t) { throw rethrow(t); } }
	static double vsyncWaitNext(double marginSeconds) { try { return (double) VSYNC_WAIT_NEXT.invokeExact(marginSeconds); } catch (Throwable t) { throw rethrow(t); } }
	static long memResident() { try { return (long) MEM_RESIDENT.invokeExact(); } catch (Throwable t) { throw rethrow(t); } }
	static long memFootprint() { try { return (long) MEM_FOOTPRINT.invokeExact(); } catch (Throwable t) { throw rethrow(t); } }
	static long memMetal(long ctx) { try { return (long) MEM_METAL.invokeExact(ctx); } catch (Throwable t) { throw rethrow(t); } }
	static long cbErrors() { try { return (long) CB_ERRORS.invokeExact(); } catch (Throwable t) { throw rethrow(t); } }
	static long cbLastError() { try { return (long) CB_LAST_ERROR.invokeExact(); } catch (Throwable t) { throw rethrow(t); } }
	static void trackOn() { try { TRACK_ON.invokeExact(); } catch (Throwable t) { throw rethrow(t); } }
	static long trackCount(int kind) { try { return (long) TRACK_COUNT.invokeExact(kind); } catch (Throwable t) { throw rethrow(t); } }
	static long trackBytes(int kind) { try { return (long) TRACK_BYTES.invokeExact(kind); } catch (Throwable t) { throw rethrow(t); } }
	static long textureNewShaped(long ctx, int format, int type, int w, int h, int d, int usage) { try { return (long) TEXTURE_NEW_SHAPED.invokeExact(ctx, format, type, w, h, d, usage); } catch (Throwable t) { throw rethrow(t); } }
	static long textureViewFormat(long texture, int format) { try { return (long) TEXTURE_VIEW_FORMAT.invokeExact(texture, format); } catch (Throwable t) { throw rethrow(t); } }
	static long bufferNewPrivate(long ctx, long size) { try { return (long) BUFFER_NEW_PRIVATE.invokeExact(ctx, size); } catch (Throwable t) { throw rethrow(t); } }
	static long computePipelineNew(long ctx, long source, long entry, long err, int errCap, long maxThreads) { try { return (long) COMPUTE_PIPELINE_NEW.invokeExact(ctx, source, entry, err, errCap, maxThreads); } catch (Throwable t) { throw rethrow(t); } }
	static void computePipeline(long enc, long pso) { try { C_PIPELINE.invokeExact(enc, pso); } catch (Throwable t) { throw rethrow(t); } }
	static void computeBuffer(long enc, int index, long buffer, long offset) { try { C_BUFFER.invokeExact(enc, index, buffer, offset); } catch (Throwable t) { throw rethrow(t); } }
	static void computeTexture(long enc, int index, long texture) { try { C_TEXTURE.invokeExact(enc, index, texture); } catch (Throwable t) { throw rethrow(t); } }
	static void computeSampler(long enc, int index, long sampler) { try { C_SAMPLER.invokeExact(enc, index, sampler); } catch (Throwable t) { throw rethrow(t); } }
	static void dispatch(long enc, int gx, int gy, int gz, int lx, int ly, int lz) { try { C_DISPATCH.invokeExact(enc, gx, gy, gz, lx, ly, lz); } catch (Throwable t) { throw rethrow(t); } }
	static void clearStorage(long enc, long texture, int kind) { try { CLEAR_STORAGE.invokeExact(enc, texture, kind); } catch (Throwable t) { throw rethrow(t); } }
	static void blitTextureBox(long enc, long src, long dst, int sx, int sy, int sz, int dx, int dy, int dz, int w, int h, int d) { try { BLIT_TEXTURE_BOX.invokeExact(enc, src, dst, sx, sy, sz, dx, dy, dz, w, h, d); } catch (Throwable t) { throw rethrow(t); } }
	static void textureSlots(long enc, int index, long texture, long sampler, int vslot, int fslot) { try { R_TEXTURE_SLOTS.invokeExact(enc, index, texture, sampler, vslot, fslot); } catch (Throwable t) { throw rethrow(t); } }
	static void endEncoders(long enc) { try { END_ENCODERS.invokeExact(enc); } catch (Throwable t) { throw rethrow(t); } }

	private static RuntimeException rethrow(Throwable t) {
		if (t instanceof RuntimeException r) return r;
		if (t instanceof Error e) throw e;
		return new IllegalStateException(t);
	}
}
