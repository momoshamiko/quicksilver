// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.mcms.shaders.runtime.gbuffers.GbufferPhase;
import dev.mcms.shaders.runtime.gbuffers.GbufferPlans;
import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.sema.Stage;
import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.ShaderNative;
import dev.mcms.shaders.pack.DimensionPrograms;
import dev.mcms.shaders.pack.textures.CustomTextureTable;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.ProgramCatalog.Phase;
import dev.mcms.shaders.pack.ResolvedProgram;
import dev.mcms.shaders.pack.StageFile;
import dev.mcms.shaders.pack.buffers.StorageBufferTable;
import dev.mcms.shaders.runtime.compute.ComputeKernel;
import dev.mcms.shaders.runtime.shadow.ShadowTargets;
import dev.mcms.shaders.standardroad.Fallback;
import dev.mcms.shaders.standardroad.StandardRoad;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jspecify.annotations.Nullable;

/**
 * One dimension's program set built by the background loader (plan.md E3): every running program of the prepare,
 * deferred, composite and final phases translated, compiled to a Metal pipeline (the standard road as ADR 0006
 * section 4's fallback when Metal rejects the translator's MSL), in frame order. Only libraries and pipeline states
 * are made here (the thread rule): no encoder, no texture.
 *
 * <p>The loader never throws: a program that fails is absent with its reason, and the load's counts report it. E3's
 * interim frame order (prepare before the world copy, deferred and composite after it, final last, writing the main
 * colour) lives in {@link EngineFrame}; this class orders by phase and program number only.
 *
 * @param passes     the running programs in frame order
 * @param fromPack   programs that ran their own files
 * @param byFallback programs served by a fallback program's files
 * @param absent     programs with no pipeline (translation or compile failed, or no stages)
 * @param onRoad     of the running programs, those served through the standard road (Metal rejected ours; each is a
 *                   translator bug to fix, ADR 0006 section 4)
 * @param plans      the set's gbuffers plans, built on the loader with the passes and released with them (null for a
 *                   count-only set, which the engine cannot install)
 */
public record DimensionSet(DimensionPrograms dimension, List<CompiledProgram> passes, int fromPack, int byFallback,
		int absent, int onRoad, long translateNanos, long metalNanos, List<String> notes,
		@Nullable GbufferPlans plans) {

	public DimensionSet {
		passes = List.copyOf(passes);
		notes = List.copyOf(notes);
	}

	/** A count-only set with no plans (the store tests keep such sets; the engine installs only sets with plans). */
	public DimensionSet(DimensionPrograms dimension, List<CompiledProgram> passes, int fromPack, int byFallback,
			int absent, int onRoad, long translateNanos, long metalNanos, List<String> notes) {
		this(dimension, passes, fromPack, byFallback, absent, onRoad, translateNanos, metalNanos, notes, null);
	}

	/** The phases E3 runs (plan.md E3; shadow, compute and gbuffers come with E4 to E6). */
	private static final List<Phase> RUN_PHASES = List.of(Phase.SETUP, Phase.BEGIN, Phase.PREPARE, Phase.DEFERRED,
		Phase.COMPOSITE, Phase.FINAL);

	private static final Logger LOG = LoggerFactory.getLogger("Quicksilver");
	/** The one WARN for an {@code indirect.pass} key (Iris: compute dispatch counts read from an SSBO; not supported, E6). */
	private static final AtomicBoolean INDIRECT_PASS_WARNED = new AtomicBoolean();
	/**
	 * {@code -Dmcms.shaders.standardRoad=true} (profiling only; E9's translator item, plan.md E9): every full-screen program
	 * takes the standard road's stages, as if Metal had rejected the translator's MSL, so a run can set the road's frame and
	 * load time beside the translator's at the same pack and options. The gbuffers have no road (ADR 0006 section 4).
	 */
	private static final boolean FORCE_STANDARD_ROAD = Boolean.getBoolean("mcms.shaders.standardRoad");

	/** The set as a foreground build that no request cancels (the frame's own set and the tests); see the overload below. */
	public static DimensionSet load(long ctx, String packName, PackModel model, DimensionPrograms dimension,
			dev.mcms.shaders.runtime.uniforms.CustomUniforms custom, int mainFormat, Map<Integer, String> formats,
			List<RenderPipeline> gamePipelines, GbufferPlans.GameCopier copier) {
		return load(ctx, packName, model, dimension, custom, mainFormat, formats, gamePipelines, copier,
			GbufferPlans.Options.foreground(() -> false));
	}

	/**
	 * Builds the set and its gbuffers plans. {@code ctx} is the Metal device context (libraries and pipelines only),
	 * {@code mainFormat} the main colour target's raw MTLPixelFormat (the final program's pipeline writes it), {@code
	 * formats} the union's format words by target index, {@code custom} the pack's custom uniforms, {@code gamePipelines}
	 * the game's pipelines the plans map, {@code copier} the copier {@link GbufferPlans#build} takes (the engine's deferred
	 * one: the render-thread copies come at install), and {@code options} the plans build's pool and cancel check.
	 *
	 * @throws GbufferPlans.LayoutException a phase layout is above the attachment limit (the passes are released)
	 * @throws GbufferPlans.BuildCancelled the options' cancel check ended the build (the passes are released)
	 */
	public static DimensionSet load(long ctx, String packName, PackModel model, DimensionPrograms dimension,
			dev.mcms.shaders.runtime.uniforms.CustomUniforms custom, int mainFormat, Map<Integer, String> formats,
			List<RenderPipeline> gamePipelines, GbufferPlans.GameCopier copier, GbufferPlans.Options options) {
		BooleanSupplier cancelled = options.cancelled();
		List<CompiledProgram> passes = new ArrayList<>();
		int fromPack = 0, byFallback = 0, absent = 0, onRoad = 0;
		long translateNs = 0, metalNs = 0;
		List<String> notes = new ArrayList<>();
		ShadowTargets shadow = ShadowTargets.of(dimension);
		Map<Integer, String> shadowFormats = dimension.union().shadowFormats();
		if (Boolean.TRUE.equals(model.properties().booleans().get("indirect.pass"))
				&& INDIRECT_PASS_WARNED.compareAndSet(false, true)) {
			LOG.warn("[mcms-shaders] indirect.pass is not supported: compute passes dispatch their fixed or render-relative"
				+ " work groups instead (no corpus pack uses it)");
		}
		// The compute kernels of the program in hand: released if the load stops before they become a pass.
		List<ComputeKernel> inHand = List.of();
		GbufferPlans plans;
		try {
			List<ResolvedProgram> order = runOrder(dimension);
			for (ResolvedProgram original : order) {
				inHand = List.of();
				GbufferPlans.checkCancelled(cancelled);  // a stop between programs: nothing of this program is made yet
				// The targets the passes of its stage built so far write: a stage texture on such a target's sampler gives way
				// to the target (E7, mcms-texover).
				Set<String> written = writtenBefore(passes, original.name());
				boolean comp = original.definition().phase() == Phase.SHADOWCOMP;
				// E5 review item 15: a shadowcomp program runs its fragment stage; its default write targets are the shadow
				// buffers the set holds.
				ResolvedProgram program = comp ? shadowCompFragment(original, shadow) : original;
				List<StageFile> kernelFiles = ProgramRequests.kernelFiles(original);
				boolean renders = ProgramRequests.hasRenderStages(program);
				if (!renders && kernelFiles.isEmpty()) {
					continue;  // a shadowcomp with no stage at all: nothing runs and it is not counted (E5 review item 7)
				}
				ResolvedProgram base = renders ? program : original;
				if (program.source() == ResolvedProgram.Source.DIRECT) {
					fromPack++;
				} else {
					byFallback++;
				}
				if (renders && (original.definition().phase() == Phase.SETUP || original.definition().phase() == Phase.BEGIN)) {
					// The frame runs a setup or begin program's kernels only (EngineFrame.frameHead): a render stage would never
					// run, so the program is refused and named (Iris: setup and begin require compute stages).
					absent++;
					notes.add("program " + program.name() + " absent: setup and begin programs run compute stages only");
					continue;
				}
				if (!base.errors().isEmpty() || base.directives().isEmpty() || base.stages().isEmpty()) {
					absent++;
					notes.add("program " + program.name() + " absent: "
						+ (base.errors().isEmpty() ? "no stages" : base.errors().get(0)));
					continue;
				}
				List<ComputeKernel> kernels;
				long k0 = System.nanoTime();
				try {
					kernels = compileKernels(ctx, model, custom, formats, dimension.union().mipmaps(), shadow, original,
						kernelFiles, written, cancelled);
				} catch (IllegalStateException refused) {
					absent++;
					notes.add("program " + program.name() + " absent: " + refused.getMessage());
					continue;
				}
				inHand = kernels;
				metalNs += System.nanoTime() - k0;
				if (!renders) {
					passes.add(CompiledProgram.computeOnly(program.name(), program.definition().phase(), kernels));
					continue;
				}
				ProgramRequest request = ProgramRequests.fitTextureIndices(comp
					? ProgramRequests.fullScreen(program, model, custom, shadowFormats, Map.of(), shadow, written)
					: ProgramRequests.fullScreen(program, model, custom, formats, dimension.union().mipmaps(), shadow,
						written));
				if (request == null) {
					releaseKernels(kernels);
					absent++;
					notes.add("program " + program.name() + " absent: a stage file the translator does not take");
					continue;
				}
				GbufferPlans.checkCancelled(cancelled);  // before the translation (the kernels in hand are released by a stop)
				long t0 = System.nanoTime();
				ProgramTranslation translation = Translator.translate(request);
				translateNs += System.nanoTime() - t0;
				if (!translation.ok()) {
					releaseKernels(kernels);
					absent++;
					notes.add("program " + program.name() + " absent: " + firstError(translation));
					continue;
				}
				String storage = storageRefusal(translation.reflection().buffers(), model.buffers());
				if (storage != null) {
					releaseKernels(kernels);
					absent++;
					notes.add("program " + program.name() + " absent: " + storage);
					continue;
				}
				GbufferPlans.checkCancelled(cancelled);  // before the Metal compile
				long m0 = System.nanoTime();
				CompiledProgram compiled = compile(ctx, packName, program.name(), program.definition().phase(), request,
					translation, mainFormat, comp ? shadowFormats : formats, model.properties().blends(), cancelled);
				metalNs += System.nanoTime() - m0;
				if (compiled.pipeline() == 0) {
					releaseKernels(kernels);
					absent++;
					notes.add("program " + program.name() + " absent: " + compiled.reason());
					continue;  // never added: a pipeline-0 pass must not reach the frame (the E3 review: binding 0 crashes)
				}
				if (compiled.road()) {
					onRoad++;
				}
				passes.add(compiled.withKernels(kernels));
			}
			inHand = List.of();  // every program's kernels are in a pass or released
			plans = GbufferPlans.build(ctx, model, dimension, custom, formats, gamePipelines, copier, options);
		} catch (RuntimeException e) {
			// The set never exists: the passes made so far and the kernels in hand are released (a stop or a failure alike).
			releasePasses(passes);
			releaseKernels(inHand);
			throw e;
		}
		return new DimensionSet(dimension, passes, fromPack, byFallback, absent, onRoad, translateNs, metalNs, notes,
			plans);
	}

	/** Releases every pass of a set that never exists: its pipelines and its compute kernels. */
	private static void releasePasses(List<CompiledProgram> passes) {
		for (CompiledProgram pass : passes) {
			if (pass.pipeline() != 0) {
				MetalBridge.release(pass.pipeline());  // the set never exists: its pipelines would leak otherwise
			}
			releaseKernels(pass.kernels());
		}
	}

	/**
	 * The dimension's running programs in E3's frame order (plan.md E3): the phases E3 runs, in phase order, the
	 * catalog's order inside a phase. Gbuffers, shadow and compute programs stay out until E4 to E6.
	 */
	public static List<ResolvedProgram> inRunOrder(DimensionPrograms dimension) {
		List<ResolvedProgram> out = new ArrayList<>();
		for (Phase phase : RUN_PHASES) {
			for (ResolvedProgram program : dimension.programs()) {
				if (program.definition().phase() == phase && program.runs()) {
					out.add(program);
				}
			}
		}
		return out;
	}

	/**
	 * The programs the load builds: {@link #inRunOrder}, then the running shadowcomp programs (E5 review item 15). The
	 * shadowcomp passes are split from the frame's passes by {@link EngineFrame}.
	 */
	static List<ResolvedProgram> runOrder(DimensionPrograms dimension) {
		List<ResolvedProgram> out = new ArrayList<>(inRunOrder(dimension));
		for (ResolvedProgram program : dimension.programs()) {
			if (program.definition().phase() == Phase.SHADOWCOMP && program.runs()) {
				out.add(program);
			}
		}
		return out;
	}

	/**
	 * The targets that this set's passes of a program's full-screen stage write before it: the frame binds those targets,
	 * not a stage's 2D custom textures, to the program's samplers ({@link CustomTextureTable#targetReplaces}), as the load
	 * built its request.
	 */
	public Set<String> writtenBefore(String program) {
		return writtenBefore(this.passes, program);
	}

	/**
	 * The targets that the passes of a program's full-screen stage write before it, among {@code passes} in run order, by the
	 * sampler names they serve (colortexN; shadowcolorN for shadowcomp passes). Only passes that built a pipeline count (an
	 * absent program writes nothing; compute-only passes and setup or begin programs draw no target). Empty for gbuffers and
	 * shadow programs, which flip no target.
	 */
	static Set<String> writtenBefore(List<CompiledProgram> passes, String program) {
		String stage = CustomTextureTable.stageOf(program);
		Set<String> out = new HashSet<>();
		if (stage == null || stage.equals("gbuffers")) {
			return out;
		}
		for (CompiledProgram pass : passes) {
			if (pass.name().equals(program)) {
				break;
			}
			if (!pass.runs() || !stage.equals(CustomTextureTable.stageOf(pass.name()))) {
				continue;
			}
			String prefix = pass.phase() == Phase.SHADOWCOMP ? "shadowcolor" : "colortex";
			for (ProgramRequest.ColorTarget output : pass.outputs()) {
				out.add(prefix + output.target());
			}
		}
		return out;
	}

	/**
	 * A shadowcomp program's fragment part (E5 review item 15): its vertex and fragment stages, with its default write
	 * targets clamped to the shadow colour buffers of the set. Its compute stage is E6's: it is dropped, and one WARN for
	 * the process names the program.
	 */
	static ResolvedProgram shadowCompFragment(ResolvedProgram program, ShadowTargets shadow) {
		List<StageFile> fragment = new ArrayList<>();
		for (StageFile file : program.stages()) {
			if (file.stage() != 'c') {
				fragment.add(file);
			}
		}
		ResolvedProgram stripped = new ResolvedProgram(program.name(), program.definition(), program.source(),
			program.servedBy(), program.disabledBy(), fragment, program.errors(), program.directives());
		return ShadowTargets.clampWrites(stripped, shadow.colorCount());
	}

	private static CompiledProgram compile(long ctx, String packName, String name, Phase phase, ProgramRequest request,
			ProgramTranslation translation, int mainFormat, Map<Integer, String> formats,
			Map<String, dev.mcms.shaders.pack.ShaderProperties.BlendState> modelBlends, BooleanSupplier cancelled) {
		List<String> unknown = translation.reflection().uniforms().stream()
			.filter(u -> u.servedBy().equals(ProgramTranslation.Reflection.UniformUse.UNKNOWN))
			.map(ProgramTranslation.Reflection.UniformUse::name).toList();
		int[] attachments = attachmentPlan(request.outputs(), translation.reflection().outputs());
		if (attachments.length == 0) {
			return new CompiledProgram(name, phase, 0, false, List.of(), List.of(), unknown, translation.reflection(),
				"program declares no colour outputs");
		}
		List<ProgramRequest.ColorTarget> used = new ArrayList<>();
		for (int attachment : attachments) {
			used.add(request.outputs().get(attachment));
		}
		List<Boolean> blendOn = new ArrayList<>();
		for (int c = 0; c < used.size(); c++) {
			ProgramRequest.ColorTarget output = used.get(c);
			var state = phase == Phase.SHADOWCOMP ? blendForShadow(modelBlends, name, output.target())
				: blendFor(modelBlends, name, output.target());
			int pixelFormat = c == 0 && phase == Phase.FINAL ? mainFormat
				: TargetFormats.of(formats.get(output.target()), phase == Phase.SHADOWCOMP ? -1 : output.target()).format();
			blendOn.add(!TargetFormats.isInteger(pixelFormat) && state != null && !state.off());
		}
		GbufferPlans.checkCancelled(cancelled);  // a stop before the program's Metal library
		ShaderNative.Compiled lib = FORCE_STANDARD_ROAD ? null
			: ShaderNative.libraryNew(ctx, translation.msl(), ShaderNative.MSL_3_1, ShaderNative.MATH_PACKS, true);
		if (lib == null || !lib.ok()) {
			// ADR 0006 section 4: Metal's rejection goes to the standard road once; a refusal makes the program absent.
			GbufferPlans.checkCancelled(cancelled);  // a stop before the standard road's translation
			long r0 = System.nanoTime();
			Fallback.Result result = Fallback.standardRoad(packName, request,
				lib == null ? "the standard road is forced (-Dmcms.shaders.standardRoad)" : lib.error());
			if (!result.ok()) {
			return new CompiledProgram(name, phase, 0, false, used, blendOn, unknown, translation.reflection(),
				"Metal rejected the MSL and the standard road refused: " + result.reason());
			}
			long pipeline = roadPipeline(ctx, result.program(), request, attachments, mainFormat, phase == Phase.FINAL,
				formats, name, modelBlends, phase == Phase.SHADOWCOMP, cancelled);
			if (lib == null) {
				LOG.info("[mcms-shaders] pack {} program {} on the forced standard road in {} ms", packName, name,
					(System.nanoTime() - r0) / 1_000_000L);
			}
			return pipeline == 0
				? new CompiledProgram(name, phase, 0, true, used, blendOn, unknown, translation.reflection(),
					"the standard road served no pipeline")
				: new CompiledProgram(name, phase, pipeline, true, used, blendOn, unknown, translation.reflection(), null);
		}
		long pipeline;
		try {
			pipeline = newPipeline(ctx, lib.library(), lib.library(), translation.vertexEntry(),
				translation.fragmentEntry(), request, attachments, mainFormat, phase == Phase.FINAL, formats, name,
				modelBlends, phase == Phase.SHADOWCOMP);
		} finally {
			MetalBridge.release(lib.library());
		}
		return pipeline == 0
			? new CompiledProgram(name, phase, 0, false, used, blendOn, unknown, translation.reflection(),
				"Metal rejected the pipeline of the translated program")
			: new CompiledProgram(name, phase, pipeline, false, used, blendOn, unknown, translation.reflection(), null);
	}

	/**
	 * The colour attachments a program's fragment stage really writes, ascending: the reflection's attachment of
	 * each fragment output, so a {@code DRAWBUFFERS:012} stage that writes locations 0 and 2 keeps target 2 on
	 * attachment 2 and leaves attachment 1 (and its target) untouched. A stage that reports no output writes
	 * attachment 0 ({@code gl_FragColor}'s documented target), or none when {@code outputs} is empty.
	 */
	static int[] attachmentPlan(List<ProgramRequest.ColorTarget> outputs,
			List<ProgramTranslation.Reflection.OutputUse> written) {
		if (outputs.isEmpty()) {
			return new int[0];
		}
		TreeSet<Integer> attachments = new TreeSet<>();
		for (ProgramTranslation.Reflection.OutputUse output : written) {
			if (output.attachment() >= 0 && output.attachment() < outputs.size()) {
				attachments.add(output.attachment());
			}
		}
		if (attachments.isEmpty()) {
			return new int[] {0};
		}
		int[] plan = new int[attachments.size()];
		int i = 0;
		for (int attachment : attachments) {
			plan[i++] = attachment;
		}
		return plan;
	}

	private static long roadPipeline(long ctx, StandardRoad.Program program, ProgramRequest request, int[] attachments,
			int mainFormat, boolean finalPass, Map<Integer, String> formats, String name,
			Map<String, dev.mcms.shaders.pack.ShaderProperties.BlendState> modelBlends, boolean shadow,
			BooleanSupplier cancelled) {
		StandardRoad.StageMsl vertex = program.stage(Stage.VERTEX);
		StandardRoad.StageMsl fragment = program.stage(Stage.FRAGMENT);
		if (vertex == null || fragment == null) {
			return 0;
		}
		long vlib = 0;
		long flib = 0;
		try {
			GbufferPlans.checkCancelled(cancelled);
			ShaderNative.Compiled v = ShaderNative.libraryNew(ctx, vertex.msl(), ShaderNative.MSL_3_1,
				ShaderNative.MATH_PACKS, true);
			if (!v.ok()) {
				return 0;
			}
			vlib = v.library();
			GbufferPlans.checkCancelled(cancelled);
			ShaderNative.Compiled f = ShaderNative.libraryNew(ctx, fragment.msl(), ShaderNative.MSL_3_1,
				ShaderNative.MATH_PACKS, true);
			if (!f.ok()) {
				return 0;
			}
			flib = f.library();
			return newPipeline(ctx, vlib, flib, vertex.entry(), fragment.entry(), request,
				attachments, mainFormat, finalPass, formats, name, modelBlends, shadow);
		} finally {
			if (vlib != 0) {
				MetalBridge.release(vlib);
			}
			if (flib != 0) {
				MetalBridge.release(flib);
			}
		}
	}

	private static long newPipeline(long ctx, long vertexLibrary, long fragmentLibrary, String vertexEntry,
			String fragmentEntry, ProgramRequest request, int[] attachments, int mainFormat, boolean finalPass,
			Map<Integer, String> formats, String name,
			Map<String, dev.mcms.shaders.pack.ShaderProperties.BlendState> modelBlends, boolean shadow) {
		// The int descriptor mc_pipeline_new parses (mcmetal.m): no vertex buffers or attributes (the quad comes from
		// the vertex id), one colour attachment per fragment output at its own attachment index (the gaps stay
		// unused, pixel format 0), no depth, triangles. The final program's first attachment writes the main colour
		// target. Blending is the documented default off unless the pack's blend.<program>[.<buffer>] names factors
		// (OptiFine shaders.properties: off, or src dst srcA dstA).
		if (attachments.length == 0) {
			return 0;
		}
		try {
			int slots = attachments[attachments.length - 1] + 1;
			int[] descriptor = new int[3 + slots * 9 + 2];
			int i = 0;
			descriptor[i++] = 0;
			descriptor[i++] = 0;
			descriptor[i++] = slots;
			int next = 0;
			for (int c = 0; c < slots; c++) {
				if (next >= attachments.length || attachments[next] != c) {
					i += 9;              // an unwritten slot: no pixel format, everything else stays zero
					continue;
				}
				next++;
				int target = request.outputs().get(c).target();
				// A shadowcomp write names a shadow buffer: its format is the shadow word, so the colour rule is off (-1).
				int pixelFormat = c == 0 && finalPass ? mainFormat
					: TargetFormats.of(formats.get(target), shadow ? -1 : target).format();
				descriptor[i++] = pixelFormat;
				descriptor[i++] = 15;  // write all components
				// OpenGL 4.6 section 17.3.6 ignores blending on integer attachments; Metal aborts pipeline validation if enabled.
				dev.mcms.shaders.pack.ShaderProperties.BlendState blend = TargetFormats.isInteger(pixelFormat) ? null
					: (shadow ? blendForShadow(modelBlends, name, target) : blendFor(modelBlends, name, target));
				if (blend != null && !blend.off()) {
					descriptor[i++] = 1;
					descriptor[i++] = blendFactor(blend.src());
					descriptor[i++] = blendFactor(blend.dst());
					descriptor[i++] = 0;  // add
					descriptor[i++] = blendFactor(blend.srcAlpha());
					descriptor[i++] = blendFactor(blend.dstAlpha());
					descriptor[i++] = 0;  // add
				} else {
					descriptor[i++] = 0;  // blending off
					i += 6;
				}
			}
			descriptor[i++] = 0;      // no depth
			descriptor[i++] = 3;      // MTLPrimitiveTopologyClassTriangle
			return MetalBridge.pipelineNew(ctx, vertexLibrary, vertexEntry, fragmentLibrary, fragmentEntry, descriptor);
		} catch (RuntimeException e) {
			return 0;
		}
	}

	/**
	 * The blend state of one program's write of one target: {@code blend.<program>.<buffer>} first (the pack names
	 * the buffer: colortex4, the alias gaux1), then the program-wide {@code blend.<program>}; null when neither is
	 * set (blending stays off, the documented default).
	 */
	public static dev.mcms.shaders.pack.ShaderProperties.BlendState blendFor(
			Map<String, dev.mcms.shaders.pack.ShaderProperties.BlendState> blends, String program, int target) {
		for (String name : dev.mcms.shaders.pack.ProgramDirectives.targetNames(target)) {
			dev.mcms.shaders.pack.ShaderProperties.BlendState state = blends.get(program + "." + name);
			if (state != null) {
				return state;
			}
		}
		return blends.get(program);
	}

	/**
	 * The blend state of one shadowcomp write of shadow colour buffer {@code index}: {@code blend.<program>.shadowcolor<n>},
	 * then the program-wide {@code blend.<program>}; null when neither is set.
	 */
	private static dev.mcms.shaders.pack.ShaderProperties.BlendState blendForShadow(
			Map<String, dev.mcms.shaders.pack.ShaderProperties.BlendState> blends, String program, int index) {
		dev.mcms.shaders.pack.ShaderProperties.BlendState state = blends.get(program + ".shadowcolor" + index);
		return state != null ? state : blends.get(program);
	}

	/**
	 * The MTLBlendFactor of an OptiFine factor name. The alpha factors come before the destination-colour ones in
	 * the enum (sourceAlpha 4, oneMinusSourceAlpha 5, destinationColor 6, measured 2026-11-13), not after them as
	 * GL's factor list reads — swapped values pass validation and silently blend with the wrong factor.
	 */
	public static int blendFactor(String name) {
		return switch (name) {
			case "ZERO" -> 0;
			case "ONE" -> 1;
			case "SRC_COLOR" -> 2;
			case "ONE_MINUS_SRC_COLOR" -> 3;
			case "SRC_ALPHA" -> 4;
			case "ONE_MINUS_SRC_ALPHA" -> 5;
			case "DST_COLOR" -> 6;
			case "ONE_MINUS_DST_COLOR" -> 7;
			case "DST_ALPHA" -> 8;
			case "ONE_MINUS_DST_ALPHA" -> 9;
			case "SRC_ALPHA_SATURATE" -> 10;
			default -> throw new IllegalArgumentException("not a documented blend factor: " + name);
		};
	}

	/**
	 * The compute kernels of one program in dispatch order (E6). Throws IllegalStateException with the first refusal; a stop
	 * ({@link GbufferPlans.BuildCancelled}) releases the kernels made so far and throws.
	 */
	private static List<ComputeKernel> compileKernels(long ctx, PackModel model, dev.mcms.shaders.runtime.uniforms.CustomUniforms custom,
			Map<Integer, String> formats, Map<Integer, Boolean> mipmaps, ShadowTargets shadow, ResolvedProgram program,
			List<StageFile> files, Set<String> writtenBefore, BooleanSupplier cancelled) {
		List<ComputeKernel> out = new ArrayList<>(files.size());
		try {
			for (StageFile file : files) {
				out.add(compileKernel(ctx, model, custom, formats, mipmaps, shadow, program, file, writtenBefore, cancelled));
			}
		} catch (RuntimeException failure) {
			releaseKernels(out);
			throw failure;
		}
		return out;
	}

	private static ComputeKernel compileKernel(long ctx, PackModel model,
			dev.mcms.shaders.runtime.uniforms.CustomUniforms custom, Map<Integer, String> formats,
			Map<Integer, Boolean> mipmaps, ShadowTargets shadow, ResolvedProgram program, StageFile file,
			Set<String> writtenBefore, BooleanSupplier cancelled) {
		ProgramRequest request = ProgramRequests.fitTextureIndices(
			ProgramRequests.compute(program, file, model, custom, formats, mipmaps, shadow, writtenBefore));
		if (request == null) {
			throw new IllegalStateException("compute stage " + file.path() + " has no request: the translator does not take it, or it is refused");
		}
		GbufferPlans.checkCancelled(cancelled);  // a stop before the kernel's translation
		ProgramTranslation translation = Translator.translate(request);
		if (!translation.ok()) {
			throw new IllegalStateException(firstError(translation));
		}
		String storage = storageRefusal(translation.reflection().buffers(), model.buffers());
		if (storage != null) {
			throw new IllegalStateException(storage);
		}
		long pipeline;
		GbufferPlans.checkCancelled(cancelled);  // a stop before the kernel's Metal pipeline
		// The pack math mode (ShaderNative.MATH_PACKS), as the render programs: a pack shares its library code between stages,
		// so an expression must keep its NaN in a kernel as in a fragment stage.
		ShaderNative.Compiled library = ShaderNative.libraryNew(ctx, translation.msl(), ShaderNative.MSL_3_1,
			ShaderNative.MATH_PACKS, true);
		if (!library.ok()) {
			throw new IllegalStateException("Metal rejected the compute kernel " + file.path() + ": " + library.error());
		}
		try {
			ShaderNative.ComputePipeline made = ShaderNative.computePipelineNew(ctx, library.library(),
				translation.computeEntry());
			if (!made.ok()) {
				throw new IllegalStateException("Metal rejected the compute kernel " + file.path() + ": " + made.error());
			}
			pipeline = made.pipeline();
		} finally {
			MetalBridge.release(library.library());
		}
		ProgramTranslation.Reflection reflection = translation.reflection();
		boolean spills = reflection.threadgroupBytes() > ComputeKernel.THREADGROUP_LIMIT;
		return new ComputeKernel(file.path(), pipeline, reflection.workGroupSize(), ComputeKernel.WorkGroups.of(file),
			reflection.textures(), reflection.images(), reflection.buffers(), spills ? reflection.threadgroupBytes() : 0);
	}

	/**
	 * The reason a program cannot run because one of its storage blocks has no declared buffer (E6, the refusal rule): a buffer
	 * block binds the pack's {@code bufferObject.N} (N is its {@code layout(binding = N)}, or 0 with no binding), and a program
	 * whose block has no declaration would read whatever Metal holds at that index. Null when every storage block of the program
	 * names a declared buffer. This is the install-time check (render, compute and the gbuffers plans). A declared buffer can still
	 * have no bytes at a view size; the frame then skips the program that binds it (EngineFrame.unboundStorage, GbufferDelegate).
	 */
	public static @Nullable String storageRefusal(
			List<ProgramTranslation.Reflection.ResourceUse> buffers, StorageBufferTable table) {
		for (ProgramTranslation.Reflection.ResourceUse use : buffers) {
			if (use.storage() && table.at(use.bufferObject()) == null) {
				return "storage block " + use.name() + " binds " + use.bufferObject() + " and the pack declares no bufferObject."
					+ use.bufferObject();
			}
		}
		return null;
	}

	/** Releases the pipelines of kernels that will not be installed. */
	static void releaseKernels(List<ComputeKernel> kernels) {
		for (ComputeKernel kernel : kernels) {
			MetalBridge.release(kernel.pipeline());
		}
	}

	private static String firstError(ProgramTranslation translation) {
		Optional<String> first = translation.diagnostics().stream()
			.filter(d -> d.severity() == ProgramTranslation.Diagnostic.Severity.ERROR).map(Object::toString).findFirst();
		return first.orElse("translation failed");
	}

	/** The colortex indexes this set's programs write or sample (which targets must exist). */
	public Set<Integer> usedTargets() {
		Set<Integer> used = new TreeSet<>();
		for (CompiledProgram pass : this.passes) {
			// A shadowcomp write names a shadow buffer (shadowcolor<n>), not a colour buffer: it adds no colortex target.
			for (ProgramRequest.ColorTarget output : pass.outputs()) {
				if (pass.phase() != Phase.SHADOWCOMP) {
					used.add(output.target());
				}
			}
			for (ProgramTranslation.Reflection.ResourceUse texture : pass.reflection().textures()) {
				int target = ProgramRequests.targetIndex(texture.name());
				if (target >= 0) {
					used.add(target);
				}
			}
			// Kernels and passes both name the colour targets as images (colorimg<N>) and as samplers: a target only one of
			// them uses must still exist, or its handle is zero when the frame binds it.
			for (ProgramTranslation.Reflection.ResourceUse image : pass.reflection().images()) {
				addColourTarget(used, image.name());
			}
			for (ComputeKernel kernel : pass.kernels()) {
				for (ProgramTranslation.Reflection.ResourceUse texture : kernel.textures()) {
					int target = ProgramRequests.targetIndex(texture.name());
					if (target >= 0) {
						used.add(target);
					}
				}
				for (ProgramTranslation.Reflection.ResourceUse image : kernel.images()) {
					addColourTarget(used, image.name());
				}
			}
		}
		if (this.plans != null) {
			// The gbuffers phases write and sample targets the full-screen passes may never name (Complementary's opaque
			// programs write colortex13 and no pass uses it): every layout and read target must exist, or a phase's
			// attachment and its load copy would hold a zero handle (the game's EXC_BAD_ACCESS in the clear hook).
			for (GbufferPhase phase : GbufferPhase.values()) {
				for (int target : this.plans.targets(phase)) {
					used.add(target);
				}
				for (int target : this.plans.readTargets(phase)) {
					used.add(target);
				}
			}
		}
		return used;
	}

	/** Adds the colour target an image name stands for ({@code colorimg<N>}), when it names one. */
	private static void addColourTarget(Set<Integer> used, String imageName) {
		int target = ComputeKernel.colourTargetOf(imageName);
		if (target >= 0) {
			used.add(target);
		}
	}

	private static final java.util.regex.Pattern COMMENT = java.util.regex.Pattern.compile("/\\*.*?\\*/|//[^\\n]*",
		java.util.regex.Pattern.DOTALL);
	private static final java.util.regex.Pattern COLORIMG_USE = java.util.regex.Pattern.compile("\\bcolorimg(1[0-5]|[0-9])\\b");

	/**
	 * The colortex indices bound as storage images ({@code colorimgN}) by any pass, compute kernel, or gbuffers stage of
	 * this set. Only these targets request {@code MTLTextureUsageShaderWrite}; all other colour targets omit it so Apple
	 * Silicon keeps lossless framebuffer compression enabled.
	 */
	public Set<Integer> imageTargets() {
		Set<Integer> images = new TreeSet<>();
		for (CompiledProgram pass : this.passes) {
			for (ProgramTranslation.Reflection.ResourceUse image : pass.reflection().images()) {
				addColourTarget(images, image.name());
			}
			for (ComputeKernel kernel : pass.kernels()) {
				for (ProgramTranslation.Reflection.ResourceUse image : kernel.images()) {
					addColourTarget(images, image.name());
				}
			}
		}
		for (ResolvedProgram program : this.dimension.programs()) {
			if (!program.runs()) {
				continue;
			}
			for (StageFile file : program.stages()) {
				var m = COLORIMG_USE.matcher(COMMENT.matcher(file.text()).replaceAll(" "));
				while (m.find()) {
					images.add(Integer.parseInt(m.group(1)));
				}
			}
		}
		return images;
	}

	/**
	 * The colortex indexes some program of the set writes and no program reads (E9, the memoryless item): no full-screen
	 * pass, compute kernel or gbuffers program samples them or binds them as an image, in this frame or the next, so what
	 * is written there is never seen. At the bench's options Complementary's {@code composite} writes only colortex7, which
	 * no program reads (its reader sits behind an option that is off). colortex0 is never dead: the window shows it when
	 * no final program runs. Empty for a set without built plans (a count-only set, a test's plans): its gbuffers reads are
	 * unknown.
	 */
	public Set<Integer> deadTargets() {
		if (this.plans == null || this.plans.stats() == null) {
			return Set.of();
		}
		Set<Integer> written = new TreeSet<>();
		Set<Integer> read = new HashSet<>();
		for (GbufferPhase phase : GbufferPhase.values()) {
			for (int target : this.plans.targets(phase)) {
				written.add(target);
			}
			for (int target : this.plans.sampledTargets(phase)) {
				read.add(target);
			}
		}
		for (CompiledProgram pass : this.passes) {
			if (pass.phase() != Phase.SHADOWCOMP) {
				for (ProgramRequest.ColorTarget output : pass.outputs()) {
					written.add(output.target());
				}
			}
			readTargets(pass.reflection().textures(), pass.reflection().images(), read);
			for (ComputeKernel kernel : pass.kernels()) {
				readTargets(kernel.textures(), kernel.images(), read);
			}
		}
		written.removeAll(read);
		written.remove(0);
		return written;
	}

	/** Adds the colour targets that samplers and images name (an image counts as a read: a kernel may read it). */
	private static void readTargets(List<ProgramTranslation.Reflection.ResourceUse> textures,
			List<ProgramTranslation.Reflection.ResourceUse> images, Set<Integer> read) {
		for (ProgramTranslation.Reflection.ResourceUse texture : textures) {
			int target = ProgramRequests.targetIndex(texture.name());
			if (target >= 0) {
				read.add(target);
			}
		}
		for (ProgramTranslation.Reflection.ResourceUse image : images) {
			addColourTarget(read, image.name());
		}
	}

	/** The {@code noiseTextureResolution} constant of the first running program that sets it (the documented default 256). */
	public int noiseResolution() {
		for (CompiledProgram pass : this.passes) {
			var directives = this.dimension.programs().stream()
				.filter(p -> p.name().equals(pass.name())).findFirst().flatMap(ResolvedProgram::directives);
			if (directives.isPresent()) {
				var value = directives.get().constants().get("noiseTextureResolution");
				if (value != null) {
					return Math.max(16, Math.min(2048, value.asInt()));
				}
			}
		}
		return 256;
	}
}
