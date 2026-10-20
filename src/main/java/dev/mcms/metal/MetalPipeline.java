/*
 * Derived from mcopt v0.3.0-alpha.2 (https://github.com/noahdunnagan/mcopt), metal/src/main/java/mcopt/metal/MetalPipeline.java.
 * Copyright Noah Dunnagan, Apache License 2.0. Changed by MC Metal Shaders, 2026-10-11: package renamed to dev.mcms.metal; system
 * properties -Dmcopt.metal.* renamed to -Dmcms.metal.*; the MSL disk cache (-Dmcopt.startup.mslCache) is now -Dmcms.metal.mslCache,
 * on by default, under <game dir>/mcms-metal/msl-cache, versioned by TRANSLATION_VERSION; cut the builtin-MSL pulled-twin terrain
 * pipelines (BUILTIN_MSL, SODIUM_TERRAIN, the pulled field) and depthCompare (only MetalOccPick read it); translated MSL sources are
 * allocated off LWJGL's MemoryStack (Complementary's programs overflow its 64 KB); depth-reference reads get a constexpr comparison
 * sampler (compareSamplers). 2026-10-12 (M4): each stage's samplers are numbered without gaps and a texture a stage only fetches from
 * gets a constexpr sampler (samplerSlots, fetchSamplers), so stages that declare more than 16 samplers build; TRANSLATION_VERSION 3.
 * After M5 (2026-10-14): a vertex function writes point_size only in a pipeline that draws points (SPIRV-Cross's
 * enable_point_size_builtin, as MoltenVK sets it): Metal refuses a line or triangle pipeline whose vertex function writes it
 * ("Vertex shader writes point size but inputPrimitiveTopology is MTLPrimitiveTopologyClassLine", malilib's debug lines).
 * 2026-10-16: the MSL disk cache lives under <game dir>/quicksilver/msl-cache, the mod's public name.
 * 2026-10-19 (RIL-495): fragment outputs are padded to four components (SPIRV-Cross's pad_fragment_output_components, as MoltenVK
 * sets it), so a fragment function that writes fewer components than its target's pixel format builds; TRANSLATION_VERSION 4.
 */
package dev.mcms.metal;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.SpvModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Comparator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.SpvcMslConstexprSampler;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import static org.lwjgl.util.spvc.Spvc.*;

/**
 * A pipeline is the frontend's SPIR-V run through SPIRV-Cross into Metal Shading Language, compiled by Metal.
 * Resource slots are fixed: uniform i lives in buffer/texture/sampler slot i, push constants in slot 26, vertex
 * buffers from slot 27. Vertex Y is flipped so render targets keep the same row order as OpenGL and Vulkan.
 */
final class MetalPipeline implements BackendRenderPipeline {
	// -Dmcms.metal.gpuCount[=PATH] (measurement only, mcmetal.m): pipelines are named for the per-pipeline counts
	static final String GPU_COUNT_PATH = gpuCountPath();
	static final boolean GPU_COUNT = GPU_COUNT_PATH != null;

	private static String gpuCountPath() {
		String p = System.getProperty("mcms.metal.gpuCount");
		return p == null || p.equals("false") ? null : p.isEmpty() || p.equals("true") ? "gpucount.jsonl" : p;
	}

	/** Metal bakes the depth attachment format into the pipeline, so like Vulkan there's one for passes with depth and one without. */
	final long withDepth;
	final long withoutDepth;
	final long depthState;
	final List<BindGroupLayout.UniformDescription> uniforms;
	/** Per uniform, the sampler slot its texture's sampler takes in the vertex and in the fragment stage; -1: none (samplerSlots). */
	final int[] vertexSamplers, fragmentSamplers;
	final boolean cull;
	final boolean wireframe;
	final float depthBiasConstant;
	final float depthBiasSlope;
	final int primitive;
	final String name;
	private final MetalEncoder encoder;
	private boolean closed;

	private MetalPipeline(MetalEncoder encoder, long withDepth, long withoutDepth, long depthState, CreateInfo info, int[] vertexSamplers, int[] fragmentSamplers) {
		this.encoder = encoder;
		this.withDepth = withDepth;
		this.withoutDepth = withoutDepth;
		this.depthState = depthState;
		this.name = info.name();
		this.uniforms = info.uniforms();
		this.cull = info.cull();
		this.wireframe = info.polygonMode() == PolygonMode.WIREFRAME;
		DepthStencilState depth = info.depthStencilState();
		this.depthBiasConstant = depth != null ? depth.depthBiasConstant() : 0;
		this.depthBiasSlope = depth != null ? depth.depthBiasScaleFactor() : 0;
		this.primitive = MetalConst.primitive(info.primitiveTopology());
		this.vertexSamplers = vertexSamplers;
		this.fragmentSamplers = fragmentSamplers;
	}

	/** Time spent creating pipelines so far, by step, for the bench's startup breakdown (read reflectively). */
	private static long compiled, translateNs, libraryNs, stateNs;

	static synchronized Map<String, Object> compileStats() {
		return Map.of("count", compiled, "translateMs", translateNs / 1e6, "libraryMs", libraryNs / 1e6, "stateMs", stateNs / 1e6, "mslCacheHits", mslHits, "mslCacheMismatches", mslMismatches);
	}

	static @Nullable MetalPipeline compile(MetalEncoder encoder, CreateInfo info) {
		long ctx = encoder.ctx;
		long t0 = System.nanoTime(), t1 = t0, t2 = t0;
		CreateInfo.Shader vertex = shader(info, ShaderType.VERTEX), fragment = shader(info, ShaderType.FRAGMENT);
		long vlib = 0, flib = 0;
		// The translated sources can be far bigger than LWJGL's 64 KB MemoryStack (Complementary's programs overflow it),
		// so they live in heap-allocated direct memory, freed in the finally below.
		ByteBuffer vmsl = null, fmsl = null;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.nmalloc(1, 4096);
			int uniformCount = info.uniforms().size();
			if (uniformCount > MetalConst.PUSH_CONSTANTS_INDEX) {
				throw new IllegalStateException(info.name() + " binds " + uniformCount + " uniforms, past the " + MetalConst.PUSH_CONSTANTS_INDEX
					+ " argument slots this backend gives them (push constants and vertex buffers take the rest)");
			}
			int[] vslots = samplerSlots(info.name(), "vertex", SpirvResources.read(vertex.module().spv()), uniformCount);
			int[] fslots = samplerSlots(info.name(), "fragment", SpirvResources.read(fragment.module().spv()), uniformCount);
			boolean points = info.primitiveTopology() == PrimitiveTopology.POINTS;
			Translated v = translate(vertex.module(), uniformCount, vslots, points);
			Translated f = translate(fragment.module(), uniformCount, fslots, false);
			if (DUMP_DIR != null) dump(info.name(), v, f);
			if (OVERRIDE_DIR != null) {
				v = override(info.name() + ".vs.metal", v);
				f = override(info.name() + ".fs.metal", f);
			}
			if (UNCACHED) {
				v = new Translated("// " + UNCACHED_NONCE + "\n" + v.msl, v.entry);
				f = new Translated("// " + UNCACHED_NONCE + "\n" + f.msl, f.entry);
			}
			t1 = System.nanoTime();
			vmsl = MemoryUtil.memUTF8(v.msl);
			fmsl = MemoryUtil.memUTF8(f.msl);
			vlib = Native.libraryNew(ctx, MemoryUtil.memAddress(vmsl), err, 4096);
			if (vlib == 0) throw new IllegalStateException(info.name() + " vertex: " + MemoryUtil.memUTF8(err) + "\n" + v.msl);
			flib = Native.libraryNew(ctx, MemoryUtil.memAddress(fmsl), err, 4096);
			if (flib == 0) throw new IllegalStateException(info.name() + " fragment: " + MemoryUtil.memUTF8(err) + "\n" + f.msl);
			t2 = System.nanoTime();
			long vname = MemoryUtil.memAddress(stack.UTF8(v.entry)), fname = MemoryUtil.memAddress(stack.UTF8(f.entry));
			DepthStencilState depth = info.depthStencilState();
			if (GPU_COUNT) Native.gpuCountLabel(info.name());  // -Dmcms.metal.gpuCount: names the per-pipeline counts
			long withDepth = pipelineNewCounted(ctx, vlib, vname, flib, fname, describe(stack, info, true), err, 4096);
			if (withDepth == 0) throw new IllegalStateException(info.name() + ": " + MemoryUtil.memUTF8(err));
			long withoutDepth = 0;
			if (depth == null) {
				if (GPU_COUNT) Native.gpuCountLabel(info.name() + " (no depth)");
				withoutDepth = pipelineNewCounted(ctx, vlib, vname, flib, fname, describe(stack, info, false), err, 4096);
				if (withoutDepth == 0) throw new IllegalStateException(info.name() + ": " + MemoryUtil.memUTF8(err));
			}
			int compare = depth == null ? 7 : MetalConst.compare(depth.depthTest());
			long depthState = Native.depthStateNew(ctx, compare, depth != null && depth.writeDepth() ? 1 : 0);
			MetalPipeline pipeline = new MetalPipeline(encoder, withDepth, withoutDepth, depthState, info, vslots, fslots);
			return pipeline;
		} finally {
			if (vmsl != null) MemoryUtil.memFree(vmsl);
			if (fmsl != null) MemoryUtil.memFree(fmsl);
			if (vlib != 0) Native.release(vlib);
			if (flib != 0) Native.release(flib);
			long t3 = System.nanoTime();
			synchronized (MetalPipeline.class) {
				compiled++;
				translateNs += t1 - t0;
				libraryNs += t2 - t1;
				stateNs += t3 - t2;
			}
		}
	}

	/** -Dmcms.metal.dumpMsl=DIR writes every pipeline's generated Metal source there, for reading what the GPU actually runs. */
	private static final @Nullable String DUMP_DIR = System.getProperty("mcms.metal.dumpMsl");
	/** -Dmcms.metal.overrideMsl=DIR uses DIR/NAME.{vs,fs}.metal instead of the generated source when present: shader experiments without a rebuild. */
	private static final @Nullable String OVERRIDE_DIR = System.getProperty("mcms.metal.overrideMsl");

	/**
	 * -Dmcms.metal.uncachedMsl=true gives every source a per-launch prefix so Metal's on-disk shader cache misses: what the
	 * first launch after install or a macOS update pays (about +0.7 s of boot on the M4 mini).
	 */
	private static final boolean UNCACHED = Boolean.getBoolean("mcms.metal.uncachedMsl");
	private static final long UNCACHED_NONCE = System.nanoTime();

	private static String fileName(String pipelineName) {
		return pipelineName.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	private static void dump(String name, Translated v, Translated f) {
		try {
			Files.createDirectories(Path.of(DUMP_DIR));
			Files.writeString(Path.of(DUMP_DIR, fileName(name) + ".vs.metal"), v.msl);
			Files.writeString(Path.of(DUMP_DIR, fileName(name) + ".fs.metal"), f.msl);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** -Dmcms.metal.dumpMsl's directory, or null; MetalCompute dumps its kernels there too. */
	static @Nullable String dumpDir() {
		return DUMP_DIR;
	}

	static void dumpOne(String name, String msl) {
		try {
			Files.createDirectories(Path.of(DUMP_DIR));
			Files.writeString(Path.of(DUMP_DIR, fileName(name)), msl);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static Translated override(String name, Translated generated) {
		Path file = Path.of(OVERRIDE_DIR, fileName(name));
		if (!Files.exists(file)) return generated;
		try {
			System.out.println("mcms-metal: using " + file);
			return new Translated(Files.readString(file), generated.entry);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static CreateInfo.Shader shader(CreateInfo info, ShaderType type) {
		return info.shaders().stream().filter(s -> s.module().type() == type).findFirst()
			.orElseThrow(() -> new IllegalStateException(info.name() + " has no " + type + " shader"));
	}

	/** Packs the pipeline layout into the int array mc_pipeline_new reads (see mcmetal.m). */
	private static long describe(MemoryStack stack, CreateInfo info, boolean hasDepth) {
		IntBuffer d = stack.mallocInt(5 + info.vertexBuffers().size() * 3 + info.attribBindings().size() * 4 + info.colorTargetStates().size() * 9);
		List<CreateInfo.VertexBuffer> buffers = info.vertexBuffers();
		List<CreateInfo.AttribBinding> attributes = info.attribBindings();
		d.put(buffers.size());
		for (CreateInfo.VertexBuffer vb : buffers) d.put(MetalConst.VERTEX_BUFFER_BASE + vb.bufferSlot()).put(vb.stride()).put(vb.stepRate());
		d.put(attributes.size());
		for (CreateInfo.AttribBinding a : attributes) {
			d.put(a.location()).put(MetalConst.VERTEX_BUFFER_BASE + a.bufferSlot()).put(a.offset()).put(MetalConst.vertexFormat(a.format()));
		}
		d.put(info.colorTargetStates().size());
		for (@Nullable ColorTargetState c : info.colorTargetStates()) {
			if (c == null) {
				d.put(new int[9]);
				continue;
			}
			// MTLColorWriteMask is ABGR-ordered (red = 8), renderpearl's is RGBA-ordered (red = 1).
			int mask = (c.writeRed() ? 8 : 0) | (c.writeGreen() ? 4 : 0) | (c.writeBlue() ? 2 : 0) | (c.writeAlpha() ? 1 : 0);
			d.put(MetalConst.pixelFormat(c.format())).put(mask);
			if (c.blendFunction().isPresent()) {
				var b = c.blendFunction().get();
				d.put(1).put(MetalConst.blendFactor(b.color().sourceFactor())).put(MetalConst.blendFactor(b.color().destFactor())).put(MetalConst.blendOp(b.color().op()))
					.put(MetalConst.blendFactor(b.alpha().sourceFactor())).put(MetalConst.blendFactor(b.alpha().destFactor())).put(MetalConst.blendOp(b.alpha().op()));
			} else {
				d.put(new int[] {0, 1, 0, 0, 1, 0, 0});
			}
		}
		d.put(hasDepth ? MetalConst.pixelFormat(com.mojang.renderpearl.api.GpuFormat.D32_FLOAT) : 0);
		d.put(MetalConst.topologyClass(info.primitiveTopology()));
		return MemoryUtil.memAddress(d.flip());
	}

	private record Translated(String msl, String entry) {
	}

	/**
	 * -Dmcms.metal.mslCache (default on): SPIRV-Cross's output for a module is a pure function of its SPIR-V, stage and uniform count
	 * (and of this mod's and SPIRV-Cross's versions), so it is kept on disk under <game dir>/quicksilver/msl-cache/ and read back on the
	 * next launch instead of translated again (the translation is ~0.15-0.3 s of every startup on the main thread).
	 */
	private static final String MSL_MODE = System.getProperty("mcms.metal.mslCache", "true");
	/** true: use the cache; verify: use it and also translate every hit, counting any difference (mslCacheMismatches); false: off. */
	private static final boolean MSL_CACHE = MSL_MODE.equals("true") || MSL_MODE.equals("verify");
	static int mslMismatches;
	private static final Path MSL_DIR = MSL_CACHE ? (System.getProperty("mcms.metal.mslCacheDir") != null
		? Path.of(System.getProperty("mcms.metal.mslCacheDir"))
		: net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("quicksilver").resolve("msl-cache")) : null;
	/**
	 * What the translation depends on besides its inputs: this class's code (options, bindings) and the SPIRV-Cross build.
	 * Bump this whenever a change to the translation options should invalidate every cached MSL file.
	 */
	private static final int TRANSLATION_VERSION = 4;
	private static final String MSL_KEY_SALT = MSL_CACHE ? mslSalt() : null;

	private static String mslSalt() {
		try (java.io.InputStream in = MetalPipeline.class.getResourceAsStream("MetalPipeline.class")) {
			byte[] code = in.readAllBytes();
			return "mcms-msl-" + TRANSLATION_VERSION + "|" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(code))
				+ "|" + org.lwjgl.Version.getVersion();
		} catch (Exception e) {
			throw new IllegalStateException("mslCache salt", e);
		}
	}
	static int mslHits, mslMisses;

	/** points: the pipeline draws points, so its vertex function may write point_size (ignored for fragment modules). */
	private static Translated translate(SpvModule module, int uniformCount, int[] samplerSlots, boolean points) {
		if (!MSL_CACHE) return translateNow(module, uniformCount, samplerSlots, points);
		String key;
		try {
			java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
			md.update(MSL_KEY_SALT.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			md.update((byte) module.type().ordinal());
			md.update(java.nio.ByteBuffer.allocate(4).putInt(uniformCount).array());
			if (module.type() == ShaderType.VERTEX) md.update((byte) (points ? 1 : 0));
			md.update(module.spv().duplicate());
			key = java.util.HexFormat.of().formatHex(md.digest());
		} catch (java.security.NoSuchAlgorithmException e) {
			return translateNow(module, uniformCount, samplerSlots, points);
		}
		java.nio.file.Path file = MSL_DIR.resolve(key);
		try {
			String text = java.nio.file.Files.readString(file);
			int nl = text.indexOf('\n');
			if (nl > 0) {
				mslHits++;
				Translated hit = new Translated(text.substring(nl + 1), text.substring(0, nl));
				if (MSL_MODE.equals("verify") && !hit.equals(translateNow(module, uniformCount, samplerSlots, points))) mslMismatches++;
				return hit;
			}
		} catch (java.io.IOException missing) {
			// not cached yet
		}
		Translated t = translateNow(module, uniformCount, samplerSlots, points);
		mslMisses++;
		try {
			java.nio.file.Files.createDirectories(MSL_DIR);
			java.nio.file.Path tmp = java.nio.file.Files.createTempFile(MSL_DIR, key, ".tmp");
			java.nio.file.Files.writeString(tmp, t.entry + "\n" + t.msl);
			java.nio.file.Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (java.io.IOException e) {
			// a cache that can't be written is just a slower launch
		}
		return t;
	}

	private static Translated translateNow(SpvModule module, int uniformCount, int[] samplerSlots, boolean points) {
		boolean vertex = module.type() == ShaderType.VERTEX;
		int model = vertex ? Spv.SpvExecutionModelVertex : Spv.SpvExecutionModelFragment;
		IntBuffer spirv = module.spv().asIntBuffer();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer out = stack.callocPointer(1);
			check(0, spvc_context_create(out), "context");
			long context = out.get(0);
			try {
				check(context, spvc_context_parse_spirv(context, spirv, spirv.remaining(), out), "parse");
				long ir = out.get(0);
				check(context, spvc_context_create_compiler(context, SPVC_BACKEND_MSL, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, out), "compiler");
				long compiler = out.get(0);
				check(context, spvc_compiler_create_compiler_options(compiler, out), "options");
				long options = out.get(0);
				spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_VERSION, 30000);
				spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_PLATFORM, SPVC_MSL_PLATFORM_MACOS);
				spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true);
				// Metal refuses a fragment function whose colour output has fewer components than the target's pixel format ("fragment
				// shader color output does not have enough components for the pixel format"), where Vulkan leaves the missing ones
				// undefined: Photon v1.4 writes vec3 into RGBA8 and RGBA16F targets. MoltenVK pads every output to four components
				// (SPIRVToMSLConverter.cpp), and so does this backend; a target with fewer components keeps only its own.
				spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_MSL_PAD_FRAGMENT_OUTPUT_COMPONENTS, true);
				if (vertex) {
					spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true);
					// Metal refuses a line or triangle pipeline whose vertex function writes point_size; OpenGL ignores it there
					spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_MSL_ENABLE_POINT_SIZE_BUILTIN, points);
				}
				check(context, spvc_compiler_install_compiler_options(compiler, options), "install options");
				for (int i = 0; i < uniformCount; i++) bind(stack, compiler, model, 0, i, i, samplerSlots[i] >= 0 ? samplerSlots[i] : i);
				bind(stack, compiler, model, SPVC_MSL_PUSH_CONSTANT_DESC_SET, SPVC_MSL_PUSH_CONSTANT_BINDING, MetalConst.PUSH_CONSTANTS_INDEX,
					MetalConst.PUSH_CONSTANTS_INDEX);
				compareSamplers(stack, context, compiler);
				fetchSamplers(stack, context, compiler, SpirvResources.read(module.spv()), uniformCount);
				check(context, spvc_compiler_compile(compiler, out), "compile");
				String msl = MemoryUtil.memUTF8(out.get(0));
				return new Translated(msl, spvc_compiler_get_cleansed_entry_point_name(compiler, "main", model));
			} finally {
				spvc_context_destroy(context);
			}
		}
	}

	static void bind(MemoryStack stack, long compiler, int model, int set, int binding, int slot, int samplerSlot) {
		SpvcMslResourceBinding b = SpvcMslResourceBinding.calloc(stack);
		spvc_msl_resource_binding_init(b);
		b.stage(model).desc_set(set).binding(binding).msl_buffer(slot).msl_texture(slot).msl_sampler(samplerSlot);
		spvc_compiler_msl_add_resource_binding(compiler, b);
	}

	/**
	 * A depth-reference read (GLSL sampler2DShadow, which Vitrail keeps wherever a pack asks for hardware shadow filtering) becomes
	 * sample_compare on a depth2d, which needs a sampler with a compare function. renderpearl's GpuSampler cannot describe one, so
	 * every sampler bound through it compares with Metal's default "never" and every such read answers 0: the whole scene in shadow.
	 * The sampler of each depth image becomes an MSL constexpr sampler instead, the one Vitrail binds for the same reads on Vulkan
	 * (ShadowCompare.sampler): linear, clamp to edge, compare LEQUAL, base level only. The texture stays at its slot; the sampler
	 * bound at that slot is not read.
	 */
	static void compareSamplers(MemoryStack stack, long context, long compiler) {
		PointerBuffer out = stack.callocPointer(1);
		check(context, spvc_compiler_create_shader_resources(compiler, out), "resources");
		long resources = out.get(0);
		PointerBuffer list = stack.callocPointer(1), count = stack.callocPointer(1);
		check(context, spvc_resources_get_resource_list_for_type(resources, SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, list, count), "sampled images");
		for (SpvcReflectedResource image : SpvcReflectedResource.create(list.get(0), (int) count.get(0))) {
			if (!spvc_type_get_image_is_depth(spvc_compiler_get_type_handle(compiler, image.base_type_id()))) continue;
			SpvcMslConstexprSampler sampler = SpvcMslConstexprSampler.calloc(stack);
			spvc_msl_constexpr_sampler_init(sampler);
			sampler.min_filter(SPVC_MSL_SAMPLER_FILTER_LINEAR).mag_filter(SPVC_MSL_SAMPLER_FILTER_LINEAR).mip_filter(SPVC_MSL_SAMPLER_MIP_FILTER_NONE)
				.s_address(SPVC_MSL_SAMPLER_ADDRESS_CLAMP_TO_EDGE).t_address(SPVC_MSL_SAMPLER_ADDRESS_CLAMP_TO_EDGE).r_address(SPVC_MSL_SAMPLER_ADDRESS_CLAMP_TO_EDGE)
				.compare_enable(true).compare_func(SPVC_MSL_SAMPLER_COMPARE_FUNC_LESS_EQUAL);
			int set = spvc_compiler_get_decoration(compiler, image.id(), Spv.SpvDecorationDescriptorSet);
			int binding = spvc_compiler_get_decoration(compiler, image.id(), Spv.SpvDecorationBinding);
			check(context, spvc_compiler_msl_remap_constexpr_sampler_by_binding(compiler, set, binding, sampler), "comparison sampler");
		}
	}

	/** Sampler slots a Metal stage has (the runtime compiler refuses [[sampler(16)]] and up). */
	static final int MAX_SAMPLERS = 16;

	/**
	 * M4, wide sampler tables: the sampler slot of each binding of one stage, numbered without gaps in binding order, for the textures
	 * the stage samples through their sampler; -1 for every other binding. SPIRV-Cross gives every combined image sampler a sampler
	 * argument, and this backend used to number it after its texture, so a stage with a texture at binding 16 or above failed even
	 * with fewer than 16 samplers (Complementary's VERYHIGH composite1, its ULTRA composite). Depth images take the constant
	 * comparison sampler (compareSamplers) and textures the stage only fetches from a constant one (fetchSamplers), neither a slot.
	 * A stage that samples more than 16 textures cannot be built without argument buffers, which this backend does not make: it is
	 * refused here with the names, and Vitrail stops drawing the pack and says so (spec S5).
	 */
	static int[] samplerSlots(String pipeline, String stage, SpirvResources resources, int bindings) {
		int[] slots = new int[bindings];
		Arrays.fill(slots, -1);
		List<SpirvResources.Combined> sampled = resources.combined.stream()
			.filter(c -> c.set() == 0 && c.binding() >= 0 && c.binding() < bindings && !c.depth() && c.sampled())
			.sorted(Comparator.comparingInt(SpirvResources.Combined::binding)).toList();
		if (sampled.size() > MAX_SAMPLERS) {
			throw new IllegalStateException("mcms-metal: " + pipeline + ": its " + stage + " stage samples " + sampled.size()
				+ " textures through samplers, past the " + MAX_SAMPLERS + " sampler slots of a Metal stage (argument buffers would lift the"
				+ " limit; this backend has none): " + sampled.stream().map(SpirvResources.Combined::name).toList());
		}
		for (int k = 0; k < sampled.size(); k++) slots[sampled.get(k).binding()] = k;
		return slots;
	}

	/** A constant nearest, clamp-to-edge sampler for each combined image sampler of set 0 the module only fetches from (samplerSlots). */
	static void fetchSamplers(MemoryStack stack, long context, long compiler, SpirvResources resources, int bindings) {
		for (SpirvResources.Combined c : resources.combined) {
			if (c.sampled() || c.depth() || c.set() != 0 || c.binding() < 0 || c.binding() >= bindings) continue;
			SpvcMslConstexprSampler sampler = SpvcMslConstexprSampler.calloc(stack);
			spvc_msl_constexpr_sampler_init(sampler);
			sampler.min_filter(SPVC_MSL_SAMPLER_FILTER_NEAREST).mag_filter(SPVC_MSL_SAMPLER_FILTER_NEAREST).mip_filter(SPVC_MSL_SAMPLER_MIP_FILTER_NONE)
				.s_address(SPVC_MSL_SAMPLER_ADDRESS_CLAMP_TO_EDGE).t_address(SPVC_MSL_SAMPLER_ADDRESS_CLAMP_TO_EDGE).r_address(SPVC_MSL_SAMPLER_ADDRESS_CLAMP_TO_EDGE);
			check(context, spvc_compiler_msl_remap_constexpr_sampler_by_binding(compiler, c.set(), c.binding(), sampler), "fetch-only sampler");
		}
	}

	static void check(long context, int result, String step) {
		if (result != 0) {
			throw new IllegalStateException("SPIRV-Cross " + step + " failed: " + (context != 0 ? spvc_context_get_last_error_string(context) : result));
		}
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			this.encoder.releaseLater(this.withDepth);
			if (this.withoutDepth != 0) this.encoder.releaseLater(this.withoutDepth);
			this.encoder.releaseLater(this.depthState);
		}
	}

	/** Native.pipelineNew, counted per frame by the frame log (-Dmcms.metal.frameLog). */
	private static long pipelineNewCounted(long ctx, long vlib, long vname, long flib, long fname, long desc, long err, int errLength) {
		if (FrameLog.ON) FrameLog.op(FrameLog.PIPELINES, 1);
		return Native.pipelineNew(ctx, vlib, vname, flib, fname, desc, err, errLength);
	}
}
