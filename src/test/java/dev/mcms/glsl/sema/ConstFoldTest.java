// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.sema;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mcms.glsl.ast.BinaryOp;
import dev.mcms.glsl.types.ScalarKind;
import dev.mcms.glsl.types.ScalarType;
import dev.mcms.glsl.types.VectorType;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConstFoldTest {

	@Test
	void signedAdditionWrapsAt32Bits() {
		ConstValue sum = ConstFold.binary(BinaryOp.ADD, ConstValue.ofInt(Integer.MAX_VALUE), ConstValue.ofInt(1),
				ScalarType.INT);
		assertEquals(Integer.MIN_VALUE, sum.intValue());
	}

	@Test
	void unsignedSubtractionWrapsAt32Bits() {
		ConstValue diff = ConstFold.binary(BinaryOp.SUB, ConstValue.ofUint(0), ConstValue.ofUint(1),
				ScalarType.UINT);
		assertEquals(0xFFFFFFFFL, diff.uintValue());
	}

	@Test
	void signedDivisionTruncatesTowardZero() {
		ConstValue q = ConstFold.binary(BinaryOp.DIV, ConstValue.ofInt(-7), ConstValue.ofInt(2), ScalarType.INT);
		assertEquals(-3, q.intValue());
	}

	@Test
	void shiftIntoTheSignBitGivesANegativeInt() {
		ConstValue s = ConstFold.binary(BinaryOp.SHL, ConstValue.ofInt(1), ConstValue.ofInt(31), ScalarType.INT);
		assertEquals(Integer.MIN_VALUE, s.intValue());
	}

	@Test
	void bitwiseAndOfANegativeIntKeepsTheBitPattern() {
		ConstValue a = ConstFold.binary(BinaryOp.BIT_AND, ConstValue.ofInt(-1), ConstValue.ofInt(0xFF),
				ScalarType.INT);
		assertEquals(255, a.intValue());
	}

	@Test
	void floatToIntConversionTruncatesTowardZero() {
		assertEquals(-1, ConstValue.ofFloat(-1.7).convert(ScalarKind.INT).intValue());
		assertEquals(1, ConstValue.ofFloat(1.9).convert(ScalarKind.INT).intValue());
	}

	@Test
	void vectorArithmeticIsComponentwise() {
		ConstValue a = ConstValue.vector(VectorType.VEC2, ConstValue.ofFloat(1.0), ConstValue.ofFloat(2.0));
		ConstValue b = ConstValue.vector(VectorType.VEC2, ConstValue.ofFloat(3.0), ConstValue.ofFloat(4.0));
		ConstValue sum = ConstFold.binary(BinaryOp.ADD, a, b, VectorType.VEC2);
		assertEquals(6.0, sum.parts()[1].floatValue());
		assertEquals(4.0, sum.parts()[0].floatValue());
	}

	@Test
	void builtinMaxFoldsToTheLargerArgument() {
		ConstValue m = ConstFold.builtin("max", List.of(ConstValue.ofFloat(1.0), ConstValue.ofFloat(2.0)),
				ScalarType.FLOAT);
		assertEquals(2.0, m.floatValue());
	}

	@Test
	void builtinAbsOfAnIntStaysAnInt() {
		ConstValue a = ConstFold.builtin("abs", List.of(ConstValue.ofInt(-3)), ScalarType.INT);
		assertEquals(3, a.intValue());
		assertEquals(ScalarKind.INT, a.kind());
	}
}
