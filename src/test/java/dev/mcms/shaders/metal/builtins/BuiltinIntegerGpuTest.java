// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.metal.builtins;

import dev.mcms.shaders.metal.builtins.BuiltinKernel.Case;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.mcms.shaders.metal.builtins.BuiltinKernel.fn;
import static dev.mcms.shaders.metal.builtins.BuiltinKernel.types;
import static dev.mcms.shaders.metal.builtins.BuiltinKernel.withOuts;

/**
 * GPU value tests of the integer, bit and packing mappings (W2-C, ADR 0006). Integer and bit results compare exactly
 * with Java references written from GLSL 4.60 8.8 (findMSB of a negative value is the most significant zero bit;
 * bitfieldExtract sign-extends signed fields; carries and borrows wrap at 32 bits). Floating-point packing compares
 * with a tolerance of one 16-bit or 8-bit step; the references avoid the exact ties GLSL leaves to the implementation.
 * Half-precision values are chosen to be exact in binary16.
 */
@Tag("gpu")
final class BuiltinIntegerGpuTest {

	/** GLSL bitfieldExtract: field [offset, offset+bits), sign-extended for signed types, zero for bits == 0. */
	static int extract(int v, int offset, int bits, boolean signed) {
		if (bits == 0) return 0;
		if (bits == 32) return v;
		int field = (v >>> offset) & ((1 << bits) - 1);
		if (signed && ((field >> (bits - 1)) & 1) != 0) field |= -1 << bits;
		return field;
	}

	/** GLSL bitfieldInsert: bits [offset, offset+bits) of base replaced by the low bits of insert. */
	static int insert(int base, int ins, int offset, int bits) {
		if (bits == 0) return base;
		int mask = bits == 32 ? -1 : ((1 << bits) - 1) << offset;
		return (base & ~mask) | ((ins << offset) & mask);
	}

	static int findLsb(int v) {
		return v == 0 ? -1 : Integer.numberOfTrailingZeros(v);
	}

	static int findMsbUnsigned(int v) {
		return v == 0 ? -1 : 31 - Integer.numberOfLeadingZeros(v);
	}

	static int findMsbSigned(int v) {
		return v >= 0 ? findMsbUnsigned(v) : findMsbUnsigned(~v);
	}

	@Test
	void findLsbAndFindMsbIncludeZeroAndNegativeCases() {
		assertNoFailures(List.of(
			fn("findLSB", "int", types("uint"), new Object[]{0x00F0}, a -> new Object[]{findLsb(0x00F0)}, 0f),
			fn("findLSB", "int", types("uint"), new Object[]{0}, a -> new Object[]{-1}, 0f),
			fn("findLSB", "ivec4", types("uvec4"), new Object[]{new int[]{0, 1, 0x80000000, 0xF0}},
				a -> new Object[]{new int[]{-1, 0, 31, 4}}, 0f),
			fn("findLSB", "ivec3", types("ivec3"), new Object[]{new int[]{-8, 0, Integer.MIN_VALUE}},
				a -> new Object[]{new int[]{3, -1, 31}}, 0f),
			fn("findMSB", "ivec3", types("uvec3"), new Object[]{new int[]{0, 1, 0x80000000}},
				a -> new Object[]{new int[]{-1, 0, 31}}, 0f),
			fn("findMSB", "int", types("uint"), new Object[]{0x7FFFFFFF}, a -> new Object[]{30}, 0f),
			fn("findMSB", "ivec4", types("ivec4"), new Object[]{new int[]{0, -1, -8, Integer.MIN_VALUE}},
				a -> new Object[]{new int[]{-1, -1, 2, 30}}, 0f),
			fn("findMSB", "ivec2", types("ivec2"), new Object[]{new int[]{-2, 1}},
				a -> new Object[]{new int[]{0, 0}}, 0f),
			fn("findMSB", "int", types("int"), new Object[]{Integer.MAX_VALUE}, a -> new Object[]{30}, 0f)));
	}

	@Test
	void bitCountCountsOneBits() {
		assertNoFailures(List.of(
			fn("bitCount", "int", types("uint"), new Object[]{0xF0F0F0F1}, a -> new Object[]{17}, 0f),
			fn("bitCount", "ivec2", types("ivec2"), new Object[]{new int[]{-1, 0}}, a -> new Object[]{new int[]{32, 0}}, 0f),
			fn("bitCount", "ivec3", types("uvec3"), new Object[]{new int[]{0x80000000, 7, -2}},
				a -> new Object[]{new int[]{1, 3, 31}}, 0f)));
	}

	@Test
	void bitfieldExtractSignExtendsSignedFields() {
		int v = -5;  // 0xFFFFFFFB
		assertNoFailures(List.of(
			fn("bitfieldExtract", "int", types("int", "int", "int"), new Object[]{v, 0, 3},
				a -> new Object[]{extract(v, 0, 3, true)}, 0f),
			fn("bitfieldExtract", "int", types("int", "int", "int"), new Object[]{v, 1, 3},
				a -> new Object[]{extract(v, 1, 3, true)}, 0f),
			fn("bitfieldExtract", "int", types("int", "int", "int"), new Object[]{v, 5, 0},
				a -> new Object[]{0}, 0f),
			fn("bitfieldExtract", "int", types("int", "int", "int"), new Object[]{v, 0, 32},
				a -> new Object[]{v}, 0f),
			fn("bitfieldExtract", "int", types("int", "int", "int"), new Object[]{v, 31, 1},
				a -> new Object[]{-1}, 0f),
			fn("bitfieldExtract", "uint", types("uint", "int", "int"), new Object[]{v, 1, 3},
				a -> new Object[]{extract(v, 1, 3, false)}, 0f),
			fn("bitfieldExtract", "ivec2", types("ivec2", "int", "int"), new Object[]{new int[]{v, 0x7F00}, 8, 7},
				a -> new Object[]{new int[]{extract(v, 8, 7, true), extract(0x7F00, 8, 7, true)}}, 0f)));
	}

	@Test
	void bitfieldInsertReplacesOnlyTheField() {
		assertNoFailures(List.of(
			fn("bitfieldInsert", "uint", types("uint", "uint", "int", "int"), new Object[]{-1, 0, 4, 8},
				a -> new Object[]{insert(-1, 0, 4, 8)}, 0f),
			fn("bitfieldInsert", "int", types("int", "int", "int", "int"), new Object[]{0x12345678, 0x1F, 28, 4},
				a -> new Object[]{insert(0x12345678, 0x1F, 28, 4)}, 0f),
			fn("bitfieldInsert", "int", types("int", "int", "int", "int"), new Object[]{-1, 3, 0, 0},
				a -> new Object[]{-1}, 0f),
			fn("bitfieldInsert", "uvec2", types("uvec2", "uvec2", "int", "int"), new Object[]{new int[]{0, -1},
				new int[]{0xAB, 1}, 2, 3},
				a -> new Object[]{new int[]{insert(0, 0xAB, 2, 3), insert(-1, 1, 2, 3)}}, 0f)));
	}

	@Test
	void bitfieldReverseFlipsEveryBit() {
		assertNoFailures(List.of(
			fn("bitfieldReverse", "uint", types("uint"), new Object[]{1}, a -> new Object[]{0x80000000}, 0f),
			fn("bitfieldReverse", "int", types("int"), new Object[]{-2}, a -> new Object[]{Integer.reverse(-2)}, 0f),
			fn("bitfieldReverse", "uvec3", types("uvec3"), new Object[]{new int[]{0x0F, 0, -1}},
				a -> new Object[]{new int[]{0xF0000000, 0, -1}}, 0f)));
	}

	@Test
	void carryAndBorrowWrapAtThirtyTwoBits() {
		assertNoFailures(List.of(
			withOuts("uaddCarry", "uint", types("uint", "uint", "uint"), new Object[]{-1, 1, null},
				types("uint", "uint"), a -> new Object[]{0, 1}, 0f),
			withOuts("uaddCarry", "uint", types("uint", "uint", "uint"), new Object[]{3, 4, null},
				types("uint", "uint"), a -> new Object[]{7, 0}, 0f),
			withOuts("usubBorrow", "uint", types("uint", "uint", "uint"), new Object[]{3, 5, null},
				types("uint", "uint"), a -> new Object[]{-2, 1}, 0f),
			withOuts("usubBorrow", "uint", types("uint", "uint", "uint"), new Object[]{5, 3, null},
				types("uint", "uint"), a -> new Object[]{2, 0}, 0f)));
	}

	@Test
	void extendedMultiplyGivesHighAndLowHalves() {
		long pu = 0xFFFFFFFFL * 0xFFFFFFFFL;
		long ps = (long) -2 * 3;
		assertNoFailures(List.of(
			withOuts("umulExtended", "void", types("uint", "uint", "uint", "uint"), new Object[]{-1, -1, null, null},
				types("uint", "uint"), a -> new Object[]{(int) (pu >>> 32), (int) pu}, 0f),
			withOuts("imulExtended", "void", types("int", "int", "int", "int"), new Object[]{-2, 3, null, null},
				types("int", "int"), a -> new Object[]{(int) (ps >> 32), (int) ps}, 0f),
			withOuts("umulExtended", "void", types("uvec2", "uvec2", "uvec2", "uvec2"), new Object[]{
					new int[]{7, 0x80000000}, new int[]{9, 2}, null, null},
				types("uvec2", "uvec2"), a -> new Object[]{new int[]{0, 1}, new int[]{63, 0}}, 0f)));
	}

	// --- packing ---

	@Test
	void packUnormAndSnormClampThenRound() {
		assertNoFailures(List.of(
			fn("packUnorm2x16", "uint", types("vec2"), new Object[]{new float[]{0.25f, 1.5f}},
				a -> new Object[]{(int) Math.round(0.25f * 65535f) | (65535 << 16)}, 0f),
			fn("packUnorm2x16", "uint", types("vec2"), new Object[]{new float[]{-0.5f, 0.6f}},
				a -> new Object[]{(int) Math.round(0.6f * 65535f) << 16}, 0f),
			fn("packSnorm2x16", "uint", types("vec2"), new Object[]{new float[]{-0.6f, 0.25f}},
				a -> new Object[]{((int) Math.round(-0.6f * 32767f) & 0xFFFF) | ((int) Math.round(0.25f * 32767f) << 16)}, 0f),
			fn("packSnorm2x16", "uint", types("vec2"), new Object[]{new float[]{-2f, 1f}},
				a -> new Object[]{(-32767 & 0xFFFF) | (32767 << 16)}, 0f),
			fn("packUnorm4x8", "uint", types("vec4"), new Object[]{new float[]{0.6f, 0.35f, -0.1f, 1.2f}},
				a -> new Object[]{(int) Math.round(0.6f * 255f) | ((int) Math.round(0.35f * 255f) << 8) | (255 << 24)}, 0f),
			fn("packSnorm4x8", "uint", types("vec4"), new Object[]{new float[]{0.4f, -0.3f, 1f, -1f}},
				a -> new Object[]{((int) Math.round(0.4f * 127f) & 0xFF) | (((int) Math.round(-0.3f * 127f) & 0xFF) << 8)
					| (127 << 16) | ((-127 & 0xFF) << 24)}, 0f)));
	}

	@Test
	void unpackFunctionsAreExactMultiplesOfTheStep() {
		assertNoFailures(List.of(
			fn("unpackUnorm2x16", "vec2", types("uint"), new Object[]{0x8000FFFF},
				a -> new Object[]{new float[]{1f, 32768f / 65535f}}, 1e-6f),
			fn("unpackSnorm2x16", "vec2", types("uint"), new Object[]{0x7FFF8001},
				a -> new Object[]{new float[]{-1f, 1f}}, 1e-6f),
			fn("unpackSnorm2x16", "vec2", types("uint"), new Object[]{0x00008000},
				a -> new Object[]{new float[]{-1f, 0f}}, 1e-6f),
			fn("unpackUnorm4x8", "vec4", types("uint"), new Object[]{0x04030201},
				a -> new Object[]{new float[]{1f / 255, 2f / 255, 3f / 255, 4f / 255}}, 1e-6f),
			fn("unpackSnorm4x8", "vec4", types("uint"), new Object[]{0x807F0100},
				a -> new Object[]{new float[]{0f, 1f / 127, 1f, -1f}}, 1e-6f)));
	}

	@Test
	void halfFloatPackingUsesBinary16() {
		// exact binary16 values: 1.0 = 0x3C00, -2.5 = 0xC100, 0.5 = 0x3800, 1024 = 0x6400
		assertNoFailures(List.of(
			fn("packHalf2x16", "uint", types("vec2"), new Object[]{new float[]{1f, -2.5f}},
				a -> new Object[]{0x3C00 | (0xC100 << 16)}, 0f),
			fn("packHalf2x16", "uint", types("vec2"), new Object[]{new float[]{0.5f, 1024f}},
				a -> new Object[]{0x3800 | (0x6400 << 16)}, 0f),
			fn("unpackHalf2x16", "vec2", types("uint"), new Object[]{0xC1003C00},
				a -> new Object[]{new float[]{1f, -2.5f}}, 0f)));
	}

	private static void assertNoFailures(List<Case> cases) {
		BuiltinValuesGpuTest.assertNoFailures(cases);
	}
}
