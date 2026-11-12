package dev.cloudgram.core.upload;

import dev.cloudgram.core.PackPart;
import dev.cloudgram.core.PackSplitter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PartReaderTest {
	@TempDir
	Path tempDir;

	@Test
	void readsTheExactByteRangeOfEachPart() throws IOException {
		byte[] pack = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
		Path file = tempDir.resolve("pack.cgp");
		Files.write(file, pack);
		List<PackPart> parts = new PackSplitter(5).split(file);

		assertEquals(4, parts.size());
		byte[] joined = new byte[pack.length];
		int offset = 0;
		for (PackPart part : parts) {
			byte[] bytes = PartReader.read(file, part);
			assertEquals(part.size(), bytes.length);
			System.arraycopy(bytes, 0, joined, offset, bytes.length);
			offset += bytes.length;
		}
		assertArrayEquals(pack, joined);
	}

	@Test
	void rejectsAPartWhoseHashNoLongerMatches() throws IOException {
		Path file = tempDir.resolve("pack.cgp");
		Files.write(file, "abcdefgh".getBytes(StandardCharsets.UTF_8));
		List<PackPart> parts = new PackSplitter(4).split(file);

		Files.write(file, "XbcdXXXX".getBytes(StandardCharsets.UTF_8));

		assertThrows(IOException.class, () -> PartReader.read(file, parts.getFirst()));
	}

	@Test
	void rejectsAPartOutsideTheFile() throws IOException {
		Path file = tempDir.resolve("pack.cgp");
		Files.write(file, new byte[4]);

		PackPart beyond = new PackPart(
				0, 0, 8, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

		assertThrows(IOException.class, () -> PartReader.read(file, beyond));
	}
}
