// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.buffers;

import dev.mcms.shaders.pack.buffers.StorageBufferTable;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcms.shaders.pack.PackSource;
import dev.mcms.shaders.pack.PropertiesText;
import dev.mcms.shaders.pack.ShaderProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The pack's bufferObject declarations: fixed and screen-sized sizes, initial files, and what is refused. */
final class StorageBufferTableTest {

	@TempDir
	Path dir;

	private StorageBufferTable table(String properties) throws IOException {
		Files.createDirectories(dir.resolve("pack/shaders"));
		try (PackSource pack = PackSource.open(dir.resolve("pack"))) {
			return StorageBufferTable.of(ShaderProperties.from(PropertiesText.parse(properties)), pack);
		}
	}

	@Test
	void aFixedBufferIsItsByteSizeWhateverTheView() throws IOException {
		StorageBufferTable t = table("bufferObject.1 = 16\n");
		StorageBufferTable.Spec s = t.at(1);
		assertNotNull(s);
		assertEquals(16, s.sizeFor(1920, 1080));
		assertEquals(16, s.sizeFor(1, 1));
		assertTrue(t.problems().isEmpty());
	}

	@Test
	void aScreenSizedBufferIsBytesPerPixelTimesTheScaledView() throws IOException {
		StorageBufferTable t = table("bufferObject.2 = 8 true 0.5 0.25\n");
		StorageBufferTable.Spec s = t.at(2);
		assertNotNull(s);
		// int(1920 * 0.5) * int(1080 * 0.25) * 8 = 960 * 270 * 8
		assertEquals(960L * 270 * 8, s.sizeFor(1920, 1080));
	}

	@Test
	void theScaledSizeTruncatesEachDimensionFirst() throws IOException {
		StorageBufferTable t = table("bufferObject.0 = 4 true 0.33 1.0\n");
		// int(10 * 0.33) = 3 (not int(3.3 * 1) of the product), int(7 * 1.0) = 7: 3 * 7 * 4
		assertEquals(3L * 7 * 4, t.at(0).sizeFor(10, 7));
	}

	@Test
	void anInitialFileFillsAFixedBuffer() throws IOException {
		Files.createDirectories(dir.resolve("pack/shaders/data"));
		Files.write(dir.resolve("pack/shaders/data/init.bin"), new byte[] {1, 2, 3, 4});
		StorageBufferTable t = table("bufferObject.3 = 8 data/init.bin\n");
		assertArrayEquals(new byte[] {1, 2, 3, 4}, t.at(3).initial());
		assertEquals(8, t.at(3).bytes(), "the buffer keeps its declared size; the rest is unset");
	}

	@Test
	void anIndexOutOfRangeIsAProblem() throws IOException {
		StorageBufferTable t = table("bufferObject.9 = 16\n");
		assertNull(t.at(9));
		assertEquals(1, t.problems().size());
		assertTrue(t.problems().get(0).contains("0 to 8"));
	}

	@Test
	void aFixedSizeAboveOneHundredTwentyEightMebibytesIsDeclaredAndTheDeviceDecidesItsFate() throws IOException {
		// Complementary's COLORED_LIGHTING=512 profile declares this size (profile-complementary, shaders.properties:219). The
		// table has no cap of its own: the device's largest buffer and its allocation refuse a buffer (StorageBuffersGpuTest).
		StorageBufferTable t = table("bufferObject.0 = 810549248\n");
		assertNotNull(t.at(0));
		assertEquals(810549248L, t.at(0).bytes());
		assertTrue(t.problems().isEmpty(), t.problems().toString());
	}

	@Test
	void theSmallestAndLargestIndicesAreDeclarable() throws IOException {
		StorageBufferTable t = table("bufferObject.0 = 4\nbufferObject.8 = 4\n");
		assertNotNull(t.at(0));
		assertNotNull(t.at(8));
		assertTrue(t.problems().isEmpty(), t.problems().toString());
	}

	@Test
	void theEmptyTableHasNoBuffers() {
		assertNull(StorageBufferTable.empty().at(0));
		assertTrue(StorageBufferTable.empty().specs().isEmpty());
	}

	@Test
	void anInitialFileOnAScreenSizedBufferIsNotDeclarable() throws IOException {
		// The properties parser takes either the file form or the screen-sized form, never both: five values are refused there.
		ShaderProperties props = ShaderProperties.from(PropertiesText.parse("bufferObject.5 = 4 true 1.0 1.0 data/init.bin\n"));
		assertTrue(props.bufferObjects().isEmpty());
		assertEquals(1, props.problems().size(), props.problems().toString());
		assertNull(table("bufferObject.5 = 4 true 1.0 1.0 data/init.bin\n").at(5));
	}

	@Test
	void aFileLargerThanTheBufferIsRefused() throws IOException {
		Files.createDirectories(dir.resolve("pack/shaders/data"));
		Files.write(dir.resolve("pack/shaders/data/big.bin"), new byte[9]);
		StorageBufferTable t = table("bufferObject.6 = 8 data/big.bin\n");
		assertNull(t.at(6));
		assertTrue(t.problems().get(0).contains("larger"));
	}

	@Test
	void aMissingFileIsRefused() throws IOException {
		StorageBufferTable t = table("bufferObject.7 = 8 data/absent.bin\n");
		assertNull(t.at(7));
		assertTrue(t.problems().get(0).contains("no such file"));
	}
}
