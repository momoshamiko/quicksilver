// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.msl.ProgramRequest.BufferBinding;
import dev.mcms.glsl.msl.ProgramRequest.ImageBinding;
import dev.mcms.glsl.msl.ProgramTranslation.Reflection.ResourceUse;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Reflection reports the access each image and buffer needs, from the uses in the program: an image that is only
 * loaded needs read access, one that is only stored needs write access, and a buffer written by the program is
 * reported read-write or write. The image's Metal type carries the same access.
 */
class ResourceAccessTest {

	private static final String CS = """
		#version 430
		layout(local_size_x = 1) in;
		layout(rgba8) uniform image2D rd;
		layout(rgba8) uniform image2D wr;
		layout(rgba8) uniform image2D rw;
		buffer Out { uint o[]; } ob;
		buffer In { uint i[]; } ib;
		void main() {
			vec4 a = imageLoad(rd, ivec2(0));
			imageStore(wr, ivec2(0), a);
			imageStore(rw, ivec2(0), imageLoad(rw, ivec2(0)));
			ob.o[0] = ib.i[0];
		}
		""";

	private static ResourceUse find(List<ResourceUse> uses, String name) {
		return uses.stream().filter(u -> u.name().equals(name)).findFirst()
			.orElseThrow(() -> new AssertionError("no use named " + name + " in " + uses));
	}

	@Test
	void imagesAndBuffersReportTheAccessTheirUsesNeed() {
		List<SourceLine> lines = IntStream.rangeClosed(1, CS.split("\n", -1).length)
			.mapToObj(i -> new SourceLine("test", i)).toList();
		ProgramTranslation t = Translator.translate(new ProgramRequest("access", ProgramRequest.Family.COMPUTE,
			List.of(new ProgramRequest.StageSource(Stage.COMPUTE, CS, lines)), null, List.of(), List.of(),
			List.of(new ImageBinding("rd", 0), new ImageBinding("wr", 1), new ImageBinding("rw", 2)),
			List.of(new BufferBinding("Out", 3), new BufferBinding("In", 4)),
			ProgramRequest.UniformSchema.none(), null, false));
		assertTrue(t.ok(), t.diagnostics().toString());
		List<ResourceUse> images = t.reflection().images();
		assertEquals("read", find(images, "rd").access());
		assertEquals("write", find(images, "wr").access());
		assertEquals("read_write", find(images, "rw").access());
		assertTrue(find(images, "rd").mslType().contains("access::read>"), find(images, "rd").mslType());
		assertTrue(find(images, "wr").mslType().contains("access::write>"), find(images, "wr").mslType());
		assertEquals("write", find(t.reflection().buffers(), "ob").access());
		assertEquals("read", find(t.reflection().buffers(), "ib").access());
	}
}
