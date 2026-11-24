// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.metal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The arena reserve's decision ({@link MetalDevice#arenaReserve}): 256 MiB by default, 0 when the recommended working set is
 * known and under 8 GiB (8 GB Macs), an unknown working set keeps the default, and -Dmcms.metal.arenaReserve overrides both.
 * 11,453,251,584 bytes is what a 16 GB Apple silicon Mac reports (llama.cpp issue #4495).
 */
class MetalDeviceArenaReserveTest {
	private static final long MIB = 1L << 20;
	private static final long GIB = 1L << 30;
	private static final long RESERVE = 256 * MIB;

	@Test
	void eightGigabyteMacsGetNoReserveByDefault() {
		MetalDevice.ArenaReserve reserve = MetalDevice.arenaReserve(null, (long) (5.33 * GIB));
		assertEquals(0L, reserve.bytes());
		assertTrue(reserve.reason().contains("under 8 GiB"), reserve.reason());
	}

	@Test
	void sixteenGigabyteMacsGetTheReserve() {
		MetalDevice.ArenaReserve reserve = MetalDevice.arenaReserve(null, 11_453_251_584L);
		assertEquals(RESERVE, reserve.bytes());
		assertTrue(reserve.reason().startsWith("default:"), reserve.reason());
	}

	@Test
	void bigMacsGetTheReserve() {
		assertEquals(RESERVE, MetalDevice.arenaReserve(null, (long) (51.84 * GIB)).bytes());
	}

	@Test
	void anUnknownWorkingSetKeepsTheDefault() {
		MetalDevice.ArenaReserve reserve = MetalDevice.arenaReserve(null, 0L);
		assertEquals(RESERVE, reserve.bytes());
		assertEquals("default: working set unknown", reserve.reason());
	}

	@Test
	void exactlyEightGibibytesKeepsTheDefault() {
		assertEquals(RESERVE, MetalDevice.arenaReserve(null, 8 * GIB).bytes());
	}

	@Test
	void theSizePropertyOverridesTheWorkingSetGate() {
		assertEquals(0L, MetalDevice.arenaReserve(0L, 11_453_251_584L).bytes());
		assertEquals(64 * MIB, MetalDevice.arenaReserve(64 * MIB, (long) (5.33 * GIB)).bytes());
		assertEquals(512 * MIB, MetalDevice.arenaReserve(512 * MIB, 0L).bytes());
		assertEquals(0L, MetalDevice.arenaReserve(-5L, 0L).bytes(), "a negative property is 0");
		assertEquals("-Dmcms.metal.arenaReserve", MetalDevice.arenaReserve(0L, 0L).reason());
		assertEquals(0L, MetalDevice.arenaReserve(32 * MIB, 0L).bytes(), "a reserve under the 64 MiB arena minimum could never be taken");
	}

	@Test
	void everyBigArenaProvisionsTheReserveButOnlyAFittingOneTakesIt() {
		// 64-127 MiB: provisions ahead of need, cannot take (0.2.1 skipped the provisioning here).
		assertTrue(MetalDevice.provisionsReserve(64 * MIB, RESERVE));
		assertTrue(MetalDevice.provisionsReserve(127 * MIB, RESERVE));
		assertFalse(MetalDevice.takesReserve(64 * MIB, RESERVE));
		assertFalse(MetalDevice.takesReserve(127 * MIB, RESERVE));
		// 128-256 MiB: provisions and takes.
		assertTrue(MetalDevice.takesReserve(128 * MIB, RESERVE));
		assertTrue(MetalDevice.takesReserve(256 * MIB, RESERVE));
		// Bigger than the reserve: provisions, cannot take.
		assertTrue(MetalDevice.provisionsReserve(300 * MIB, RESERVE));
		assertFalse(MetalDevice.takesReserve(300 * MIB, RESERVE));
		// Small arenas and a reserve that is off: neither.
		assertFalse(MetalDevice.provisionsReserve(63 * MIB, RESERVE));
		assertFalse(MetalDevice.provisionsReserve(200 * MIB, 0L));
		assertFalse(MetalDevice.takesReserve(200 * MIB, 0L));
	}
}
