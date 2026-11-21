// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.preprocess;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** Include resolution, nesting limits, file numbering, and the line rewriter. */
class PreprocessorIncludeTest {
	static final Map<String, String> PACK = new LinkedHashMap<>();

	static PreprocessResult glsl(Map<String, String> files, String rootPath) {
		try {
			return glslThrows(files, rootPath);
		} catch (PreprocessException e) {
			throw new AssertionError(e);
		}
	}

	static PreprocessResult glslThrows(Map<String, String> files, String rootPath) throws PreprocessException {
		return new Preprocessor(Mode.GLSL, (from, name) -> {
			String p = PackLike.resolve(from, name);
			return p == null ? Optional.empty() : Optional.ofNullable(files.containsKey(p) ? new SourceText(p, files.get(p)) : null);
		}, Map.of(), null).process(new SourceText(rootPath, files.get(rootPath)));
	}
	/** The same lexical rules PackPaths implements, kept local so this test stays in the pure preprocess package. */
	static final class PackLike {
		static String resolve(String fromFile, String include) {
			String base = include.startsWith("/") ? include : folder(fromFile) + "/" + include;
			List<String> out = new java.util.ArrayList<>();
			for (String seg : base.split("/")) {
				if (seg.isEmpty() || seg.equals(".")) continue;
				if (seg.equals("..")) {
					if (!out.isEmpty() && !out.get(out.size() - 1).equals("..")) out.remove(out.size() - 1);
					else return null;
				} else {
					out.add(seg);
				}
			}
			return out.isEmpty() || out.get(0).equals("..") ? null : String.join("/", out);
		}

		static String folder(String f) {
			int i = f.lastIndexOf('/');
			return i < 0 ? "" : f.substring(0, i);
		}
	}

	static List<String> lines(PreprocessResult r) {
		return r.text().isEmpty() ? List.of() : List.of(r.text().split("\n", -1));
	}

	@Test
	void relativeIncludeFromSubfolder() {
		Map<String, String> files = Map.of(
				"world0/composite.fsh", "#include \"part.glsl\"\nmain();\n",
				"world0/part.glsl", "included();\n");
		PreprocessResult r = glsl(files, "world0/composite.fsh");
		assertEquals(List.of("included();", "main();"), lines(r));
		assertEquals(new LineOrigin("world0/part.glsl", 1), r.lineMap().get(0));
		assertEquals(new LineOrigin("world0/composite.fsh", 2), r.lineMap().get(1));
	}

	@Test
	void absoluteIncludeFromShadersRoot() {
		Map<String, String> files = Map.of(
				"root.fsh", "#include \"/lib/common.glsl\"\n",
				"lib/common.glsl", "common();\n");
		assertEquals(List.of("common();"), lines(glsl(files, "root.fsh")));
	}

	@Test
	void angleBracketInclude() {
		Map<String, String> files = Map.of(
				"root.fsh", "#include <lib/common.glsl>\n",
				"lib/common.glsl", "angled();\n");
		assertEquals(List.of("angled();"), lines(glsl(files, "root.fsh")));
	}

	@Test
	void parentRelativeInclude() {
		Map<String, String> files = Map.of(
				"world0/program.fsh", "#include \"../lib/shared.glsl\"\n",
				"lib/shared.glsl", "shared();\n");
		assertEquals(List.of("shared();"), lines(glsl(files, "world0/program.fsh")));
	}

	@Test
	void missingIncludeIsErrorWithFileAndLine() {
		Map<String, String> files = Map.of("root.fsh", "line one\n#include \"nowhere.glsl\"\n");
		try {
			glslThrows(files, "root.fsh");
			fail("expected failure");
		} catch (PreprocessException e) {
			Diagnostic d = e.diagnostics().get(0);
			assertEquals("root.fsh", d.path());
			assertEquals(2, d.line());
			assertTrue(d.message().contains("nowhere.glsl"));
		}
	}

	@Test
	void includeEscapingRootIsNotFound() {
		Map<String, String> files = Map.of("root.fsh", "#include \"../outside.glsl\"\n");
		try {
			glslThrows(files, "root.fsh");
			fail("expected failure");
		} catch (PreprocessException e) {
			assertTrue(e.diagnostics().get(0).message().contains("include not found"));
		}
	}

	@Test
	void nestingDepthTenWorks() {
		Map<String, String> files = new LinkedHashMap<>();
		for (int i = 1; i <= 10; i++) {
			files.put("i" + i + ".glsl", i < 10 ? "#include \"i" + (i + 1) + ".glsl\"\n" : "deep();\n");
		}
		files.put("root.fsh", "#include \"i1.glsl\"\n");
		assertEquals(List.of("deep();"), lines(glsl(files, "root.fsh")));
	}

	@Test
	void nestingDepthElevenFails() {
		Map<String, String> files = new LinkedHashMap<>();
		for (int i = 1; i <= 11; i++) {
			files.put("i" + i + ".glsl", i < 11 ? "#include \"i" + (i + 1) + ".glsl\"\n" : "too deep\n");
		}
		files.put("root.fsh", "#include \"i1.glsl\"\n");
		try {
			glslThrows(files, "root.fsh");
			fail("expected failure");
		} catch (PreprocessException e) {
			assertTrue(e.diagnostics().get(0).message().contains("nesting"));
		}
	}

	@Test
	void selfInclusionStopsAtDepthLimit() {
		Map<String, String> files = Map.of("loop.glsl", "#include \"loop.glsl\"\n");
		try {
			glslThrows(files, "loop.glsl");
			fail("expected failure");
		} catch (PreprocessException e) {
			assertTrue(e.diagnostics().get(0).message().contains("nesting"));
		}
	}

	@Test
	void fileNumbersAssignedInFirstInclusionOrder() {
		Map<String, String> files = Map.of(
				"root.fsh", "#include \"a.glsl\"\n#include \"b.glsl\"\n#include \"a.glsl\"\nint f = __FILE__;\n",
				"a.glsl", "int fa = __FILE__;\n",
				"b.glsl", "int fb = __FILE__;\n");
		assertEquals(
				List.of("int fa = 1;", "int fb = 2;", "int fa = 1;", "int f = 0;"),
				lines(glsl(files, "root.fsh")));
	}

	@Test
	void filesSetListsRootFirstThenIncludesInOrder() {
		Map<String, String> files = Map.of(
				"root.fsh", "#include \"b.glsl\"\n#include \"a.glsl\"\n",
				"b.glsl", "b\n",
				"a.glsl", "a\n");
		PreprocessResult r = glsl(files, "root.fsh");
		assertEquals(List.of("root.fsh", "b.glsl", "a.glsl"), List.copyOf(r.files()));
	}

	@Test
	void macrosPersistFromIncludeToIncluder() {
		Map<String, String> files = Map.of(
				"root.fsh", "#include \"defs.glsl\"\nint x = ANSWER;\n",
				"defs.glsl", "#define ANSWER 42\n");
		assertEquals(List.of("int x = 42;"), lines(glsl(files, "root.fsh")));
	}

	@Test
	void versionInIncludeDroppedWithWarning() {
		Map<String, String> files = Map.of(
				"root.fsh", "#version 120\n#include \"inc.glsl\"\n",
				"inc.glsl", "#version 400\nint a;\n");
		PreprocessResult r = glsl(files, "root.fsh");
		assertEquals("120", r.version());
		assertEquals(List.of("#version 120", "int a;"), lines(r));
		assertEquals(1, r.warnings().size());
		assertTrue(r.warnings().get(0).message().contains("#version"));
	}

	@Test
	void unterminatedConditionalInIncludeIsError() {
		Map<String, String> files = Map.of(
				"root.fsh", "#include \"inc.glsl\"\n",
				"inc.glsl", "#if 1\nno endif\n");
		try {
			glslThrows(files, "root.fsh");
			fail("expected failure");
		} catch (PreprocessException e) {
			assertEquals("inc.glsl", e.diagnostics().get(0).path());
			assertEquals(1, e.diagnostics().get(0).line());
		}
	}

	@Test
	void includeMayCloseConditionalOpenedByIncluder() {
		// Each file balances what it opens: the include's #endif closes the root's #if. Packs never rely on this.
		Map<String, String> files = Map.of(
				"root.fsh", "#if 1\n#include \"inc.glsl\"\nafter root\n",
				"inc.glsl", "#endif\ninside include\n");
		assertEquals(List.of("inside include", "after root"), lines(glsl(files, "root.fsh")));
	}

	@Test
	void lineRewriterAppliesToIncludedFilesToo() {
		Map<String, String> files = Map.of(
				"root.fsh", "// #define HIDDEN 1\n#include \"inc.glsl\"\nint x = HIDDEN;\n",
				"inc.glsl", "// #define INSIDE 2\nint y = INSIDE;\n");
		try {
			PreprocessResult r = new Preprocessor(Mode.GLSL, (from, name) -> {
				String p = PackLike.resolve(from, name);
				return p == null ? Optional.empty() : Optional.ofNullable(files.containsKey(p) ? new SourceText(p, files.get(p)) : null);
			}, Map.of(), (path, line, raw) -> raw.startsWith("// #define ") ? raw.substring(3) : raw)
					.process(new SourceText("root.fsh", files.get("root.fsh")));
			assertEquals(List.of("int y = 2;", "int x = 1;"), lines(r));
		} catch (PreprocessException e) {
			throw new AssertionError(e);
		}
	}
}
