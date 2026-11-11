package dev.cloudgram.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackSplitterTest {
	@TempDir
	Path tempDir;

	@Test
	void splitsIntoContiguousPartsWithOwnHashes() throws IOException {
		Path file = tempDir.resolve("pack.bin");
		byte[] data = TestWorlds.content("pack", 10);
		Files.write(file, data);

		List<PackPart> parts = new PackSplitter(4).split(file);

		assertEquals(3, parts.size());
		assertEquals(List.of(0, 1, 2), parts.stream().map(PackPart::index).toList());
		assertEquals(List.of(0L, 4L, 8L), parts.stream().map(PackPart::offset).toList());
		assertEquals(List.of(4L, 4L, 2L), parts.stream().map(PackPart::size).toList());

		for (PackPart part : parts) {
			byte[] expected = Arrays.copyOfRange(data, (int) part.offset(), (int) (part.offset() + part.size()));
			assertEquals(sha256(expected), part.sha256());
		}

		ByteArrayOutputStream joined = new ByteArrayOutputStream();
		for (PackPart part : parts) {
			joined.write(data, (int) part.offset(), (int) part.size());
		}
		assertArrayEquals(data, joined.toByteArray());
	}

	@Test
	void keepsSmallFileInOnePart() throws IOException {
		Path file = tempDir.resolve("pack.bin");
		byte[] data = TestWorlds.content("small", 5);
		Files.write(file, data);

		List<PackPart> parts = new PackSplitter(19L * 1024 * 1024).split(file);

		assertEquals(1, parts.size());
		assertEquals(0, parts.get(0).offset());
		assertEquals(data.length, parts.get(0).size());
		assertEquals(sha256(data), parts.get(0).sha256());
	}

	@Test
	void returnsNoPartsForEmptyFile() throws IOException {
		Path file = tempDir.resolve("empty.bin");
		Files.write(file, new byte[0]);

		assertTrue(new PackSplitter(4).split(file).isEmpty());
	}

	@Test
	void rejectsNonPositivePartLimit() {
		assertThrows(IllegalArgumentException.class, () -> new PackSplitter(0));
	}

	@Test
	void failsOnMissingFile() {
		assertThrows(IOException.class, () -> new PackSplitter(4).split(tempDir.resolve("missing.bin")));
	}

	private static String sha256(byte[] bytes) {
		return Hex.encode(PackSplitter.sha256().digest(bytes));
	}
}
