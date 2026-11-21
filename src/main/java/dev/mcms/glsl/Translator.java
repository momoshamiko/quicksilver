// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl;

import dev.mcms.glsl.ast.Unit;
import dev.mcms.glsl.msl.ProgramEmitter;
import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.SamplerState;
import dev.mcms.glsl.msl.ProgramRequest.TextureBinding;
import dev.mcms.glsl.msl.ProgramRequest.StageSource;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.glsl.msl.ProgramTranslation.Diagnostic;
import dev.mcms.glsl.msl.builtins.BuiltinTable;
import dev.mcms.glsl.parse.Parser;
import dev.mcms.glsl.sema.SemaDiagnostic;
import dev.mcms.glsl.sema.SemaOptions;
import dev.mcms.glsl.sema.SemaResult;
import dev.mcms.glsl.sema.SemanticAnalyzer;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.VariableSymbol;
import dev.mcms.glsl.transform.EngineRewrites;
import dev.mcms.glsl.types.SamplerDim;
import dev.mcms.glsl.types.SamplerType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The translator (ADR 0006 section 2): one program's preprocessed stages in, one MSL library and its reflection out.
 * Each stage is parsed, rewritten by the engine's AST rewrites, and analyzed leniently (the pack's driver leniencies are
 * accepted with a warning). The emitter then writes the library. A stage that does not parse or type-check yields a
 * failed translation with its diagnostics mapped to the pack's files and lines; no fallback happens here.
 */
public final class Translator {

	private static final BuiltinTable TABLE = BuiltinTable.create();

	private Translator() {
	}

	/** Translates one program. Never throws for a pack's fault: every fault is a diagnostic in the result. */
	public static ProgramTranslation translate(ProgramRequest request) {
		long start = System.nanoTime();
		if (request.buffers().size() > ProgramRequest.MAX_BUFFER_BINDINGS) {
			// The engine's buffer table: pack blocks take indices 0 to MAX_BUFFER_BINDINGS - 1 (EngineSlots); a program
			// that declares more has no index left for them and is refused with this diagnostic.
			return new ProgramTranslation(request.name(), false, null, null, null, null, null, List.of(
				new Diagnostic(Diagnostic.Severity.ERROR, null, null, "buffer-blocks", request.buffers().size()
					+ " pack blocks; the engine binds at most " + ProgramRequest.MAX_BUFFER_BINDINGS)),
				System.nanoTime() - start);
		}
		List<Diagnostic> diagnostics = new ArrayList<>();
		List<ProgramEmitter.StageInput> inputs = new ArrayList<>();
		boolean failed = false;
		Set<String> rect = new HashSet<>();
		for (StageSource source : request.stages()) {
			String stage = ProgramEmitter.stageName(source.stage());
			Unit unit;
			try {
				unit = Parser.parse(source.text());
			} catch (GlslSyntaxException e) {
				diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, stage, origin(source.lineMap(), e.line()),
					"syntax", e.getMessage()));
				failed = true;
				continue;
			}
			unit = EngineRewrites.apply(unit);
			SemaResult sema = SemanticAnalyzer.analyze(unit, SemaOptions.lenient(source.stage(), source.lineMap()));
			for (SemaDiagnostic d : sema.diagnostics()) {
				diagnostics.add(new Diagnostic(d.severity() == SemaDiagnostic.Severity.ERROR
					? Diagnostic.Severity.ERROR : Diagnostic.Severity.WARNING, stage,
					d.origin() != null ? d.origin() : origin(source.lineMap(), d.pos().line()), d.code(), d.message()));
				failed |= d.severity() == SemaDiagnostic.Severity.ERROR;
			}
			inputs.add(new ProgramEmitter.StageInput(source.stage(), unit, sema, source.lineMap()));
			for (VariableSymbol v : sema.globals()) {
				if (v.type() instanceof SamplerType s && s.dim() == SamplerDim.RECT) {
					rect.add(v.name());
				}
			}
		}
		if (failed) {
			return new ProgramTranslation(request.name(), false, null, null, null, null, null, diagnostics,
				System.nanoTime() - start);
		}
		ProgramTranslation emitted = new ProgramEmitter(withRectangleSamplers(request, rect), TABLE)
			.translate(request.name(), inputs);
		List<Diagnostic> all = new ArrayList<>(diagnostics);
		all.addAll(emitted.diagnostics());
		return new ProgramTranslation(emitted.name(), emitted.ok(), emitted.msl(), emitted.vertexEntry(),
			emitted.fragmentEntry(), emitted.computeEntry(), emitted.reflection(), all,
			System.nanoTime() - start);
	}

	/**
	 * A rectangle sampler takes texel coordinates whatever the request says: the GLSL sampler type decides, so a caller
	 * cannot read normalized coordinates from a rectangle texture by leaving the pixel flag off.
	 */
	private static ProgramRequest withRectangleSamplers(ProgramRequest request, Set<String> rect) {
		List<TextureBinding> textures = new ArrayList<>();
		for (TextureBinding t : request.textures()) {
			SamplerState s = t.sampler();
			if (rect.contains(t.name()) && !s.pixel()) {
				s = new SamplerState(s.filter(), s.mip(), s.wrap(), s.compare(), true);
			}
			textures.add(new TextureBinding(t.name(), t.slot(), t.kind(), t.target(), s, t.samplerSlot(), t.implicitSampler(),
				t.lodBias()));
		}
		return new ProgramRequest(request.name(), request.family(), request.stages(), request.vertexLayout(),
			request.outputs(), textures, request.images(), request.buffers(), request.uniforms(), request.alphaTest(),
			request.drawsPoints(), request.perVertexIds(), request.overlayColor(), request.cloudsPrologue());
	}

	private static SourceLine origin(List<SourceLine> lineMap, int line) {
		if (line < 1 || line > lineMap.size()) {
			return null;
		}
		return lineMap.get(line - 1);
	}
}
