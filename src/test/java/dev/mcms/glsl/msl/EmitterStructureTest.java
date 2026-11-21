// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.msl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.glsl.Translator;
import dev.mcms.glsl.ast.Unit;
import dev.mcms.glsl.parse.Parser;
import dev.mcms.glsl.sema.SourceLine;
import dev.mcms.glsl.sema.Stage;
import dev.mcms.glsl.transform.EngineRewrites;
import dev.mcms.glsl.types.ArrayType;
import dev.mcms.glsl.types.MatrixType;
import dev.mcms.glsl.types.ScalarType;
import dev.mcms.glsl.types.StructField;
import dev.mcms.glsl.types.VectorType;
import java.util.List;
import org.junit.jupiter.api.Test;

final class EmitterStructureTest {

	@Test
	void std430PlacesEachMemberAtItsAlignment() {
		List<StructField> fields = List.of(new StructField("a", VectorType.VEC2), new StructField("b", ScalarType.FLOAT),
			new StructField("c", VectorType.VEC3), new StructField("d", ScalarType.FLOAT));
		assertArrayEquals(new long[] {0, 8, 16, 28}, Layouts.offsets(fields, Layouts.Std.STD430));
	}

	@Test
	void std140GivesArrayElementsASixteenByteStrideAndStd430DoesNot() {
		assertEquals(16, Layouts.arrayStride(ScalarType.FLOAT, Layouts.Std.STD140));
		assertEquals(4, Layouts.arrayStride(ScalarType.FLOAT, Layouts.Std.STD430));
		assertEquals(48, Layouts.size(new ArrayType(ScalarType.FLOAT, 3), Layouts.Std.STD140));
		assertEquals(12, Layouts.size(new ArrayType(ScalarType.FLOAT, 3), Layouts.Std.STD430));
	}

	@Test
	void mat3HasThreeColumnsOfSixteenBytesInBothRules() {
		assertEquals(48, Layouts.size(MatrixType.MAT3, Layouts.Std.STD140));
		assertEquals(48, Layouts.size(MatrixType.MAT3, Layouts.Std.STD430));
	}

	@Test
	void ftransformBecomesTheModelViewProjectionTimesVertex() {
		String text = "#version 120\nvoid main() { gl_Position = ftransform(); }\n";
		Unit unit = EngineRewrites.apply(Parser.parse(text));
		String printed = unit.toString();
		assertTrue(printed.contains("gl_ModelViewProjectionMatrix"), printed);
		assertTrue(printed.contains("gl_Vertex"), printed);
		assertFalse(printed.contains("\"ftransform\""), printed);
	}

	@Test
	void computeEmissionNamesTheEntryAndPrefixesUserNames() {
		String glsl = """
			#version 430
			layout(local_size_x = 4) in;
			layout(std430, binding = 0) buffer B { float data[]; };
			void main() { data[gl_GlobalInvocationID.x] = 1.0; }
			""";
		List<SourceLine> lines = java.util.stream.IntStream.rangeClosed(1, 5)
			.mapToObj(i -> new SourceLine("test.csh", i)).toList();
		ProgramRequest request = new ProgramRequest("test", ProgramRequest.Family.COMPUTE,
			List.of(new ProgramRequest.StageSource(Stage.COMPUTE, glsl, lines)), null, List.of(), List.of(), List.of(),
			List.of(new ProgramRequest.BufferBinding("B", 0)), ProgramRequest.UniformSchema.none(), null, false);
		ProgramTranslation t = Translator.translate(request);
		assertTrue(t.ok(), t.diagnostics().toString());
		assertEquals("qs_cs", t.computeEntry());
		assertTrue(t.msl().contains("kernel void qs_cs("), t.msl());
		assertTrue(t.msl().contains("u_data"), t.msl());
		assertTrue(t.msl().contains("[[buffer(0)]]"), t.msl());
		assertEquals(4, t.reflection().workGroupSize()[0]);
	}
}
