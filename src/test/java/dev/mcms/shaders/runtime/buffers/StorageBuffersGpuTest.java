// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.buffers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.metal.MetalBridge;
import dev.mcms.shaders.metal.HeadlessGpu;
import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.PropertiesText;
import dev.mcms.shaders.pack.ShaderProperties;
import dev.mcms.shaders.pack.buffers.StorageBufferTable;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The storage buffers on the device: the initial file, sizes that follow the view, the device's largest buffer as the only
 * size limit, Complementary's 810549248-byte buffer, and a resize that keeps the fixed buffers and rebuilds the others.
 */
@Tag("gpu")
final class StorageBuffersGpuTest {

	/** Complementary's COLORED_LIGHTING=512 size (profile-complementary's shaders.properties:219). */
	private static final long COMPLEMENTARY_BYTES = 810_549_248L;

	@TempDir
	Path dir;

	private StorageBufferTable table(String properties) throws Exception {
		Files.createDirectories(dir.resolve("pack/shaders/data"));
		Files.write(dir.resolve("pack/shaders/data/init.bin"), new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
		try (PackSource pack = PackSource.open(dir.resolve("pack"))) {
			return StorageBufferTable.of(ShaderProperties.from(PropertiesText.parse(properties)), pack);
		}
	}

	/** The bytes a buffer holds, read from its CPU-visible contents. */
	private static byte[] contents(long handle, long size) {
		MemorySegment m = MemorySegment.ofAddress(MetalBridge.bufferContents(handle)).reinterpret(size);
		return m.toArray(ValueLayout.JAVA_BYTE);
	}

	@Test
	void aFixedBufferWithAFileStartsWithTheFileAndTheRestZero() throws Exception {
		StorageBufferTable t = table("bufferObject.0 = 16 data/init.bin\nbufferObject.1 = 8\n");
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			StorageBuffers set = StorageBuffers.create(gpu.context(), t, 64, 64);
			assertNotNull(set);
			assertNotEquals(0L, set.handle(0));
			assertEquals(16, set.size(0));
			byte[] zeroPadded = new byte[16];
			System.arraycopy(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, 0, zeroPadded, 0, 8);
			assertArrayEquals(zeroPadded, contents(set.handle(0), 16), "the file, then zeros");
			assertArrayEquals(new byte[8], contents(set.handle(1), 8), "no file: zeros from the device's allocation");
			assertEquals(0L, set.handle(5), "an undeclared index has no buffer");
			set.close();
		}
	}

	@Test
	void complementaryEightHundredMegabyteBufferIsCreatedOnTheDevice() throws Exception {
		StorageBufferTable t = table("bufferObject.0 = " + COMPLEMENTARY_BYTES + "\n");
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			assertTrue(MetalBridge.maxBufferLength(gpu.context()) >= COMPLEMENTARY_BYTES, "the device holds the buffer");
			StorageBuffers set = StorageBuffers.create(gpu.context(), t, 1920, 1080);
			assertNotNull(set);
			assertNotEquals(0L, set.handle(0), "the buffer exists: nothing refuses it at this size");
			assertEquals(COMPLEMENTARY_BYTES, set.size(0));
			assertTrue(set.problems().isEmpty(), set.problems().toString());
			set.close();
		}
	}

	@Test
	void aBufferLargerThanTheDeviceHoldsIsAProblemAndNoBuffer() throws Exception {
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long tooBig = MetalBridge.maxBufferLength(gpu.context()) + 16;
			StorageBufferTable t = table("bufferObject.3 = " + tooBig + "\n");
			StorageBuffers set = StorageBuffers.create(gpu.context(), t, 64, 64);
			assertNotNull(set, "a size the device cannot hold is not a device refusal of the set");
			assertEquals(0L, set.handle(3));
			assertTrue(set.problems().get(0).contains("largest buffer"), set.problems().toString());
			set.close();
		}
	}

	@Test
	void aScreenSizedBufferFollowsTheViewAndItsCreationSizeIsExact() throws Exception {
		StorageBufferTable t = table("bufferObject.2 = 4 true 0.5 0.5\n");
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			StorageBuffers set = StorageBuffers.create(gpu.context(), t, 8, 8);
			assertNotNull(set);
			assertEquals(4 * 4 * 4, set.size(2), "int(8 * 0.5) * int(8 * 0.5) * 4 bytes");
			set.close();
			assertEquals(0L, set.handle(2), "a closed set holds no handle");
		}
	}

	@Test
	void aResizeKeepsTheFixedBufferAndRebuildsOnlyTheScreenSizedOneWhoseSizeChanged() throws Exception {
		StorageBufferTable t = table("bufferObject.0 = 16 data/init.bin\nbufferObject.2 = 4 true 1.0 1.0\n");
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			StorageBuffers set = StorageBuffers.create(gpu.context(), t, 8, 8);
			assertNotNull(set);
			long fixed = set.handle(0), relative = set.handle(2);
			assertEquals(8 * 8 * 4, set.size(2));
			// The pack writes into the fixed buffer: a resize must keep those bytes.
			MemorySegment.ofAddress(MetalBridge.bufferContents(fixed)).reinterpret(16).set(ValueLayout.JAVA_BYTE, 15, (byte) 0x5a);
			assertTrue(set.resize(gpu.context(), 8, 8), "a same-size resize succeeds");
			assertEquals(relative, set.handle(2), "a screen-sized buffer whose size did not change keeps its handle");
			assertEquals(fixed, set.handle(0), "the fixed buffer keeps its handle");
			assertEquals((byte) 0x5a, contents(set.handle(0), 16)[15], "and its contents");
			assertTrue(set.resize(gpu.context(), 16, 8), "a resize to another view succeeds");
			assertEquals(fixed, set.handle(0), "a fixed buffer is not rebuilt by a resize");
			assertEquals((byte) 0x5a, contents(set.handle(0), 16)[15], "its contents survive the resize");
			assertEquals(16 * 8 * 4, set.size(2), "at the new view size");
			set.close();
		}
	}

	@Test
	void aResizeToASizeTheDeviceCannotHoldReportsItAndDropsOnlyThatBuffer() throws Exception {
		StorageBufferTable t = table("bufferObject.0 = 16 data/init.bin\nbufferObject.2 = 4096 true 1.0 1.0\n");
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			StorageBuffers set = StorageBuffers.create(gpu.context(), t, 8, 8);
			assertNotNull(set);
			long fixed = set.handle(0);
			// 4096 bytes per pixel over a 200000 x 200000 view is far above any device's largest buffer.
			assertTrue(set.resize(gpu.context(), 200_000, 200_000), "the device did not refuse an allocation");
			assertEquals(0L, set.handle(2), "the buffer the device cannot hold is dropped");
			assertEquals(fixed, set.handle(0), "the fixed buffer stays");
			assertTrue(set.problems().get(0).contains("largest buffer"), set.problems().toString());
			set.close();
		}
	}

	/**
	 * A new buffer starts at zero even when the allocator hands out memory another buffer held: Metal does not zero a new
	 * buffer, so the test first fills and frees buffers of the same size, leaving used memory for the allocator to reuse.
	 */
	@Test
	void aNewBufferStartsAtZeroWhenItsMemoryWasUsedBefore() throws Exception {
		StorageBufferTable t = table("bufferObject.2 = 64\n");
		try (HeadlessGpu gpu = new HeadlessGpu()) {
			long ctx = gpu.context();
			for (int round = 0; round < 8; round++) {
				long dirty = MetalBridge.newBuffer(ctx, 64);
				MemorySegment.ofAddress(MetalBridge.bufferContents(dirty)).reinterpret(64).fill((byte) 0x7F);
				MetalBridge.release(dirty);
			}
			StorageBuffers set = StorageBuffers.create(ctx, t, 64, 64);
			assertNotNull(set);
			assertArrayEquals(new byte[64], contents(set.handle(2), 64), "a new buffer starts at zero");
			set.close();
		}
	}
}
