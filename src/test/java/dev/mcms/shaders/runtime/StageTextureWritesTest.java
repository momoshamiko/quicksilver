// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.glsl.msl.ProgramTranslation;
import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.ResolvedProgram;
import dev.mcms.shaders.pack.StandardMacros;
import dev.mcms.shaders.pack.textures.CustomTextureTable;
import dev.mcms.shaders.runtime.shadow.ShadowTargets;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A texture.<stage>.colortexN binding replaces the target's sampler only until a program of the stage writes the target
 * (Iris 1.11.7, measured with the mcms-texover test pack, E7): the writer still reads the binding, the later programs of
 * the stage and final read the target. Before, every composite program and final read the binding, so Bliss 2.1.2 blurred
 * its blue noise into its bloom and its exposure (its pictures were washed out).
 */
class StageTextureWritesTest {

	private static PackModel model;

	@BeforeAll
	static void makePack(@TempDir Path dir) throws Exception {
		Path shaders = Files.createDirectories(dir.resolve("shaders"));
		Files.createDirectories(shaders.resolve("tex"));
		ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), "png", shaders.resolve("tex/marker.png").toFile());
		Files.writeString(shaders.resolve("shaders.properties"), """
			texture.composite.colortex5=tex/marker.png
			texture.deferred.colortex6=tex/marker.png
			""");
		program(shaders, "deferred", "7", "colortex6");
		program(shaders, "deferred1", "6,8", "colortex6");
		program(shaders, "deferred2", "9", "colortex6");
		program(shaders, "composite", "2", "colortex5");
		program(shaders, "composite1", "5,3", "colortex5");
		program(shaders, "composite2", "4", "colortex5");
		Files.writeString(shaders.resolve("final.vsh"), vertex());
		Files.writeString(shaders.resolve("final.fsh"), """
			#version 130
			uniform sampler2D colortex5;
			uniform sampler2D colortex6;
			void main() {
				gl_FragColor = texelFetch(colortex5, ivec2(1, 1), 0) + texelFetch(colortex6, ivec2(1, 1), 0);
			}
			""");
		try (PackSource source = PackSource.open(dir)) {
			model = PackModel.load(source, StandardMacros.engine(), "en_us", new Properties());
		}
	}

	private static String vertex() {
		return """
			#version 130
			void main() {
				gl_Position = ftransform();
			}
			""";
	}

	/** A full-screen program that writes the given targets and reads one sampler. */
	private static void program(Path shaders, String name, String targets, String sampler) throws Exception {
		Files.writeString(shaders.resolve(name + ".vsh"), vertex());
		Files.writeString(shaders.resolve(name + ".fsh"), """
			#version 130
			uniform sampler2D %s;
			/* RENDERTARGETS: %s */
			void main() {
				gl_FragData[0] = texelFetch(%s, ivec2(1, 1), 0);
				gl_FragData[1] = vec4(1.0);
			}
			""".formatted(sampler, targets, sampler));
	}

	/**
	 * The passes a load would build from the pack, without a device: each running program in run order with a stand-in
	 * pipeline and one output per target it writes; {@code absent} names programs that built no pipeline.
	 */
	private static List<CompiledProgram> passes(Set<String> absent) {
		List<CompiledProgram> out = new ArrayList<>();
		for (ResolvedProgram p : DimensionSet.inRunOrder(model.rootFolder())) {
			List<ProgramRequest.ColorTarget> outputs = new ArrayList<>();
			List<Integer> writes = p.directives().orElseThrow().writes();
			for (int i = 0; i < writes.size(); i++) {
				outputs.add(new ProgramRequest.ColorTarget(i, writes.get(i), 4));
			}
			out.add(new CompiledProgram(p.name(), p.definition().phase(), absent.contains(p.name()) ? 0L : 1L, false, outputs,
				outputs.stream().map(o -> false).toList(), List.of(), ProgramTranslation.Reflection.none(), null));
		}
		return out;
	}

	private static Set<String> written(String program) {
		return DimensionSet.writtenBefore(passes(Set.of()), program);
	}

	private static ProgramRequest.SamplerState samplerOf(String program, String sampler) {
		ResolvedProgram p = model.rootFolder().programs().stream().filter(r -> r.name().equals(program)).findFirst()
			.orElseThrow();
		ProgramRequest request = ProgramRequests.fullScreen(p, model, null, model.rootFolder().union().colorFormats(), Map.of(),
			ShadowTargets.of(model.rootFolder()), written(program));
		assertNotNull(request, program + " has a request");
		return request.textures().stream().filter(t -> t.name().equals(sampler)).findFirst().orElseThrow().sampler();
	}

	@Test
	void eachProgramKnowsTheTargetsItsStageWroteBeforeIt() {
		assertEquals(Set.of(), written("deferred"));
		assertEquals(Set.of("colortex7"), written("deferred1"));
		assertEquals(Set.of("colortex6", "colortex7", "colortex8"), written("deferred2"));
		assertEquals(Set.of(), written("composite"), "the deferred programs' writes do not end a composite binding");
		assertEquals(Set.of("colortex2"), written("composite1"));
		assertEquals(Set.of("colortex2", "colortex3", "colortex5"), written("composite2"));
		assertEquals(Set.of("colortex2", "colortex3", "colortex4", "colortex5"), written("final"));
		assertEquals(Set.of(), written("gbuffers_terrain"), "gbuffers programs flip no target");
		assertEquals(Set.of("colortex2"), DimensionSet.writtenBefore(passes(Set.of("composite1")), "composite2"),
			"a program that built no pipeline writes nothing");
	}

	@Test
	void onlyAStagesTwoDimensionalBindingGivesWayToAWrittenTarget() {
		CustomTextureTable.Entry stage2d = new CustomTextureTable.Entry("composite", "colortex0", 0,
			CustomTextureTable.Source.PACK_FILE, "tex/a.png", CustomTextureTable.Dim.D2, ProgramRequest.Filter.NEAREST,
			ProgramRequest.Wrap.REPEAT, null, null);
		CustomTextureTable.Entry stage3d = new CustomTextureTable.Entry("composite", "colortex0", 0,
			CustomTextureTable.Source.PACK_FILE, "tex/a.dat", CustomTextureTable.Dim.D3, ProgramRequest.Filter.LINEAR,
			ProgramRequest.Wrap.CLAMP_TO_EDGE, null, null);
		CustomTextureTable.Entry named = new CustomTextureTable.Entry("", "colortex0", 0,
			CustomTextureTable.Source.PACK_FILE, "tex/a.png", CustomTextureTable.Dim.D2, ProgramRequest.Filter.NEAREST,
			ProgramRequest.Wrap.REPEAT, null, null);
		Set<String> written = Set.of("colortex0");
		assertTrue(CustomTextureTable.targetReplaces(stage2d, "gcolor", written), "an alias names the same target");
		assertFalse(CustomTextureTable.targetReplaces(stage3d, "colortex0", written), "a 2D target cannot serve a sampler3D");
		assertFalse(CustomTextureTable.targetReplaces(named, "colortex0", written), "a customTexture binding is no stage's");
		assertFalse(CustomTextureTable.targetReplaces(stage2d, "colortex0", Set.of("colortex1")));
	}

	@Test
	void theBindingHoldsThroughItsWriterAndThenTheTargetIsRead() {
		// The pack image is nearest and repeat; the target itself linear and clamped (ProgramRequests.targetState).
		for (String reader : List.of("composite", "composite1", "deferred", "deferred1")) {
			String sampler = reader.startsWith("composite") ? "colortex5" : "colortex6";
			assertEquals(ProgramRequest.Wrap.REPEAT, samplerOf(reader, sampler).wrap(), reader + " reads the stage's image");
		}
		for (String reader : List.of("composite2", "deferred2")) {
			String sampler = reader.startsWith("composite") ? "colortex5" : "colortex6";
			assertEquals(ProgramRequest.Wrap.CLAMP_TO_EDGE, samplerOf(reader, sampler).wrap(), reader + " reads the target");
			assertEquals(ProgramRequest.Filter.LINEAR, samplerOf(reader, sampler).filter(), reader + " reads the target");
		}
		assertEquals(ProgramRequest.Wrap.CLAMP_TO_EDGE, samplerOf("final", "colortex5").wrap(), "final reads the written target");
	}
}
