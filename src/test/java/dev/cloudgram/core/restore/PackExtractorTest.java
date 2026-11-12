package dev.cloudgram.core.restore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackExtractorTest {
	@TempDir
	Path tempDir;

	@Test
	void extractsStoredEntriesAndNestedDirectories() throws IOException {
		Path pack = tempDir.resolve("pack.cgp");
		byte[] region = "region-bytes".getBytes(StandardCharsets.UTF_8);
		byte[] level = "level-bytes".getBytes(StandardCharsets.UTF_8);
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
			stored(zip, "dimensions/minecraft/overworld/region/r.0.0.mca", region);
			stored(zip, "level.dat", level);
		}

		PackExtractor.Result result = PackExtractor.extract(pack, tempDir.resolve("world"));

		assertEquals(2, result.files());
		assertEquals(region.length + level.length, result.bytes());
		assertArrayEquals(region, Files.readAllBytes(
				tempDir.resolve("world/dimensions/minecraft/overworld/region/r.0.0.mca")));
		assertArrayEquals(level, Files.readAllBytes(tempDir.resolve("world/level.dat")));
	}

	@Test
	void rejectsTraversalEntries() throws IOException {
		Path pack = tempDir.resolve("evil.cgp");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
			stored(zip, "../escape.txt", "nope".getBytes(StandardCharsets.UTF_8));
		}

		IOException error = assertThrows(IOException.class,
				() -> PackExtractor.extract(pack, tempDir.resolve("world")));
		assertTrue(error.getMessage().contains("unsafe"));
	}

	@Test
	void rejectsDuplicateEntries() throws IOException {
		Path pack = tempDir.resolve("duplicates.cgp");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
			stored(zip, "level.dat", "one".getBytes(StandardCharsets.UTF_8));
			// Distinct zip names that normalise to the same world path.
			stored(zip, "./level.dat", "two".getBytes(StandardCharsets.UTF_8));
		}

		IOException error = assertThrows(IOException.class,
				() -> PackExtractor.extract(pack, tempDir.resolve("world")));
		assertTrue(error.getMessage().contains("duplicate"));
	}

	private static void stored(ZipOutputStream zip, String name, byte[] data) throws IOException {
		CRC32 crc = new CRC32();
		crc.update(data);
		ZipEntry entry = new ZipEntry(name);
		entry.setMethod(ZipEntry.STORED);
		entry.setSize(data.length);
		entry.setCrc(crc.getValue());
		zip.putNextEntry(entry);
		OutputStream out = zip;
		out.write(data);
		zip.closeEntry();
	}
}
