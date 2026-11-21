// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.glsl450;

import dev.mcms.glsl.GlslSyntaxException;
import dev.mcms.glsl.ast.InterfaceBlock;
import dev.mcms.glsl.ast.Unit;
import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramRequest.StageSource;
import dev.mcms.glsl.parse.Parser;
import dev.mcms.glsl.sema.SemaDiagnostic;
import dev.mcms.glsl.sema.SemaOptions;
import dev.mcms.glsl.sema.SemaResult;
import dev.mcms.glsl.sema.SemanticAnalyzer;
import dev.mcms.glsl.sema.Stage;
import dev.mcms.glsl.sema.VariableSymbol;
import dev.mcms.glsl.transform.EngineRewrites;
import dev.mcms.glsl.types.GlslType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Prints a program request as Vulkan GLSL 4.50 (ADR 0006 section 4): the standard road's input. Each stage is parsed and
 * rewritten by the same engine rewrites the MSL translator applies, then analyzed leniently; the stages print in one
 * pass each (see {@link StagePrinter}). The vertex outputs fix the varying locations, which the fragment inputs read.
 */
public final class Glsl450Printer {

	private Glsl450Printer() {
	}

	/** One analyzed stage: its unit after the engine rewrites and its semantic result. */
	record Analysis(Stage stage, Unit unit, SemaResult sema) {
	}

	/** Prints the program. Never throws for a pack's fault: every fault is an error in the result. */
	public static Glsl450Program print(ProgramRequest request) {
		List<String> errors = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		Map<Stage, Analysis> analyses = new EnumMap<>(Stage.class);
		for (StageSource src : request.stages()) {
			Unit unit;
			try {
				unit = Parser.parse(src.text());
			} catch (GlslSyntaxException e) {
				errors.add(src.stage() + " syntax: " + e.getMessage());
				continue;
			}
			unit = EngineRewrites.apply(unit);
			SemaResult sema = SemanticAnalyzer.analyze(unit, SemaOptions.lenient(src.stage(), src.lineMap()));
			for (SemaDiagnostic d : sema.errors()) {
				errors.add(src.stage() + " " + d.code() + ": " + d.message());
			}
			if (sema.ok()) {
				analyses.put(src.stage(), new Analysis(src.stage(), unit, sema));
			}
		}
		boolean vertexFragment = analyses.containsKey(Stage.VERTEX) && analyses.containsKey(Stage.FRAGMENT);
		boolean compute = analyses.containsKey(Stage.COMPUTE);
		if (errors.isEmpty() && compute && analyses.size() > 1) {
			errors.add("a compute program has one compute stage and no others");
		} else if (errors.isEmpty() && !compute && !vertexFragment) {
			errors.add("a graphics program needs a vertex and a fragment stage");
		}
		if (!errors.isEmpty()) {
			return new Glsl450Program(request.name(), List.of(), errors, warnings);
		}
		Map<String, Integer> locations = new LinkedHashMap<>();
		Map<String, GlslType> vertexOutputs = new LinkedHashMap<>();
		Analysis vertex = analyses.get(Stage.VERTEX);
		if (vertex != null) {
			int next = 0;
			for (Map.Entry<String, GlslType> e : vertexOutputs(vertex).entrySet()) {
				locations.put(e.getKey(), next);
				next += GlslNames.locations(e.getValue());
				vertexOutputs.put(e.getKey(), e.getValue());
			}
		}
		List<Glsl450Program.StageText> stages = new ArrayList<>();
		for (Analysis a : analyses.values()) {
			stages.add(new StagePrinter(request, a.stage(), a.unit(), a.sema(), locations, vertexOutputs, errors,
				warnings).print());
		}
		return new Glsl450Program(request.name(), stages, errors, warnings);
	}

	/**
	 * The vertex outputs by varying key, in key order: named outs and the compatibility varyings. The key of a named
	 * out is its name; the MSL emitter numbers the same keys in the same order.
	 */
	private static Map<String, GlslType> vertexOutputs(Analysis vertex) {
		TreeMap<String, GlslType> outs = new TreeMap<>();
		for (VariableSymbol v : vertex.sema().globals()) {
			if (!v.builtin() && v.quals().isOutput() && !(v.declaration() instanceof InterfaceBlock)) {
				outs.put(v.name(), v.type());
			}
		}
		for (VariableSymbol v : vertex.sema().usedBuiltins().values()) {
			if (v.quals().isOutput() && GlslNames.VARYING_OUT.contains(v.name())) {
				outs.put(v.name(), v.type());
			}
		}
		return outs;
	}
}
