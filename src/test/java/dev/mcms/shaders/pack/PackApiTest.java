// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mcms.shaders.expr.ExprType;

/**
 * The layout API E3 needs (review item 7: frame slots of custom values, in declaration order) and the per-dimension union of
 * the directives (review item 9: formats, clears and shadow constants are pack-wide, with conflicts reported).
 */
class PackApiTest {
	@TempDir
	Path dir;

	private void file(String rel, String text) throws IOException {
		Path p = dir.resolve("shaders").resolve(rel);
		Files.createDirectories(p.getParent());
		Files.writeString(p, text);
	}

	private PackModel load() throws IOException {
		try (PackSource pack = PackSource.open(dir)) {
			return PackModel.load(pack, StandardMacros.engine(), "en_us", new Properties());
		}
	}

	@Test
	void frameLayoutIsDeclarationOrderWithCustomSlotsAfterTheStandardOnes() throws IOException {
		file("shaders.properties", "uniform.float.level=frameTime * 2.0\nvariable.float.half=level / 2.0\n");
		file("composite.fsh", "void main() {}\n");
		PackModel m = load();
		FrameLayout layout = m.frameLayout();
		List<FrameLayout.Parameter> ps = layout.parameters();
		assertEquals("frameTime", ps.get(0).name(), "the first standard uniform in the declaration list");
		assertEquals(ExprType.FLOAT, ps.get(0).type());
		for (int i = 0; i < ps.size(); i++) assertEquals(i, ps.get(i).slot(), "slots are contiguous from 0");
		int first = ps.size();
		assertEquals(first + 2, layout.slots(), "two custom values take the slots after the parameters");
		assertEquals(OptionalInt.of(first), m.customValues().get(0).outputSlot(), "level is the first custom slot");
		assertEquals(OptionalInt.of(first + 1), m.customValues().get(1).outputSlot(), "half is the second custom slot");
		assertEquals(ExprType.FLOAT, m.customValues().get(1).type().orElseThrow());
		assertTrue(m.customValues().get(1).compiled().isPresent(), "half reads level and compiles");
	}

	@Test
	void frameLayoutIsTheSameOnEveryLoad() throws IOException {
		file("shaders.properties", "uniform.vec3.up=upPosition\n");
		file("composite.fsh", "void main() {}\n");
		assertEquals(load().frameLayout(), load().frameLayout());
	}

	@Test
	void aDimensionUnionCollectsFormatsAndReportsConflicts() throws IOException {
		file("gbuffers_basic.fsh", "const int colortex3Format = RGBA16F;\nconst int shadowMapResolution = 2048;\n"
			+ "/* DRAWBUFFERS:0 */\nvoid main() {}\n");
		file("composite.fsh", "const int colortex3Format = RGBA8;\n/* DRAWBUFFERS:03 */\nvoid main() {}\n");
		file("composite1.fsh", "const int colortex0Format = RGBA16F;\n/* DRAWBUFFERS:0 */\nvoid main() {}\n");
		file("shadow.fsh", "const int shadowMapResolution = 1024;\n/* DRAWBUFFERS:0 */\nvoid main() {}\n");
		PackModel m = load();
		DimensionPrograms overworld = m.dimensions().get(0);
		TargetUnion u = overworld.union();
		assertEquals("RGBA16F", u.colorFormats().get(3), "the first program in catalog order wins");
		assertEquals("RGBA16F", u.colorFormats().get(0));
		assertTrue(u.conflicts().stream().anyMatch(c -> c.contains("colortex3Format")), u.conflicts().toString());
		assertTrue(u.conflicts().stream().anyMatch(c -> c.contains("shadowMapResolution")), u.conflicts().toString());
		assertFalse(u.conflicts().stream().anyMatch(c -> c.contains("colortex0Format")), "one value is no conflict");
		assertTrue(u.shadowConstants().containsKey("shadowMapResolution"));
		assertTrue(u.colorFormats().size() >= 2, "formats of every running program are in the union");
	}

	@Test
	void aProgramWithoutDirectivesGivesAnEmptyUnion() throws IOException {
		file("composite.fsh", "void main() {}\n");
		TargetUnion u = load().dimensions().get(0).union();
		assertTrue(u.colorFormats().isEmpty());
		assertTrue(u.conflicts().isEmpty());
		assertTrue(u.shadowConstants().isEmpty(), "no shadow constants without a directive");
	}

	@Test
	void aMipChainStaysOnWhenAnyRunningProgramDeclaresIt() throws IOException {
		file("gbuffers_basic.fsh", "const bool colortex3MipmapEnabled = false;\n/* DRAWBUFFERS:0 */\nvoid main() {}\n");
		file("composite.fsh", "const bool colortex3MipmapEnabled = true;\n/* DRAWBUFFERS:03 */\nvoid main() {}\n");
		TargetUnion u = load().dimensions().get(0).union();
		assertEquals(Boolean.TRUE, u.mipmaps().get(3), "the first program's false does not drop the chain another program declares");
		assertTrue(u.conflicts().stream().anyMatch(c -> c.contains("colortex3MipmapEnabled")), u.conflicts().toString());
	}

	@Test
	void aMipFlagOfFalseStaysFalseWhenNoRunningProgramDeclaresTrue() throws IOException {
		file("gbuffers_basic.fsh", "const bool colortex3MipmapEnabled = false;\n/* DRAWBUFFERS:0 */\nvoid main() {}\n");
		file("composite.fsh", "const bool colortex3MipmapEnabled = false;\n/* DRAWBUFFERS:03 */\nvoid main() {}\n");
		TargetUnion u = load().dimensions().get(0).union();
		assertEquals(Boolean.FALSE, u.mipmaps().get(3));
		assertTrue(u.conflicts().isEmpty(), "two programs that agree are no conflict");
	}
}
