package dev.cloudgram.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackWriterTest {
	@TempDir
	Path tempDir;

	@Test
	void producesByteIdenticalOutputAcrossRuns() throws IOException {
		Path world = createWorld();
		PackGroup group = onlyPackWithId(new PackPlanner(PackOptions.defaults()).plan(world), "cell:0:0");
		PackWriter writer = new PackWriter(PackOptions.defaults());

		BuiltPack first = writer.write(world, group, tempDir.resolve("run1/pack.cgp"));
		BuiltPack second = writer.write(world, group, tempDir.resolve("run2/pack.cgp"));

		assertEquals(first.sha256(), second.sha256());
		assertArrayEquals(Files.readAllBytes(first.file()), Files.readAllBytes(second.file()));
		assertEquals(64, first.sha256().length());
	}

	@Test
	void entryOrderDoesNotAffectTheHash() throws IOException {
		Path world = createWorld();
		PackGroup group = onlyPackWithId(new PackPlanner(PackOptions.defaults()).plan(world), "cell:0:0");
		List<ScannedFile> reversed = new ArrayList<>(group.files());
		java.util.Collections.reverse(reversed);
		PackWriter writer = new PackWriter(PackOptions.defaults());

		BuiltPack normal = writer.write(world, group, tempDir.resolve("normal.cgp"));
		BuiltPack shuffled = writer.write(world, new PackGroup(group.id(), reversed), tempDir.resolve("shuffled.cgp"));

		assertEquals(normal.sha256(), shuffled.sha256());
	}

	@Test
	void writesStoredEntriesWithFixedTimestamp() throws IOException {
		Path world = createWorld();
		PackGroup group = onlyPackWithId(new PackPlanner(PackOptions.defaults()).plan(world), "cell:0:0");
		BuiltPack pack = new PackWriter(PackOptions.defaults()).write(world, group, tempDir.resolve("pack.cgp"));

		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (InputStream in = Files.newInputStream(pack.file());
			 ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8)) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				assertFalse(entry.isDirectory(), "no directory entries expected");
				assertEquals(ZipEntry.STORED, entry.getMethod(), "entries must be stored");
				assertEquals(LocalDateTime.of(1980, 1, 1, 0, 0), entry.getTimeLocal());
				ByteArrayOutputStream content = new ByteArrayOutputStream();
				zip.transferTo(content);
				entries.put(entry.getName(), content.toByteArray());
			}
		}

		assertEquals(group.files().stream().map(ScannedFile::path).toList(), List.copyOf(entries.keySet()));
		for (ScannedFile file : group.files()) {
			assertArrayEquals(TestWorlds.content(file.path(), (int) file.size()), entries.get(file.path()));
		}
	}

	@Test
	void centralDirectoryIsReadableByRandomAccess() throws IOException {
		Path world = createWorld();
		PackGroup group = onlyPackWithId(new PackPlanner(PackOptions.defaults()).plan(world), "cell:0:0");
		BuiltPack pack = new PackWriter(PackOptions.defaults()).write(world, group, tempDir.resolve("pack.cgp"));

		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(pack.file().toFile())) {
			assertEquals(group.files().size(), zip.size());
			for (ScannedFile file : group.files()) {
				ZipEntry entry = zip.getEntry(file.path());
				assertEquals(file.size(), entry.getSize());
				assertArrayEquals(TestWorlds.content(file.path(), (int) file.size()),
						zip.getInputStream(entry).readAllBytes());
			}
		}
	}

	@Test
	void splitsPacksLargerThanThePartLimit() throws IOException {
		Path world = tempDir.resolve("big-world");
		TestWorlds.write(world, "data/big.dat", 2_500_000);

		PackOptions options = new PackOptions(
				PackOptions.DEFAULT_TARGET_PACK_BYTES,
				1 << 20,
				PackOptions.DEFAULT_MAX_ENTRIES_PER_PACK,
				Exclusions.defaults());
		PackGroup group = onlyPackWithId(new PackPlanner(options).plan(world), "meta");
		BuiltPack pack = new PackWriter(options).write(world, group, tempDir.resolve("big.cgp"));

		assertEquals(3, pack.parts().size());
		assertTrue(pack.parts().stream().allMatch(part -> part.size() <= 1 << 20));
		assertEquals(pack.size(), pack.parts().stream().mapToLong(PackPart::size).sum());
	}

	@Test
	void differentContentProducesDifferentHash() throws IOException {
		Path worldA = createWorld();
		Path worldB = createWorld();
		TestWorlds.write(worldB, "region/r.0.0.mca", 64);
		PackWriter writer = new PackWriter(PackOptions.defaults());

		PackGroup groupA = onlyPackWithId(new PackPlanner(PackOptions.defaults()).plan(worldA), "cell:0:0");
		PackGroup groupB = onlyPackWithId(new PackPlanner(PackOptions.defaults()).plan(worldB), "cell:0:0");

		assertNotEquals(
				writer.write(worldA, groupA, tempDir.resolve("a.cgp")).sha256(),
				writer.write(worldB, groupB, tempDir.resolve("b.cgp")).sha256());
	}

	@Test
	void partsCoverTheWholePackInOrder() throws IOException {
		Path world = createWorld();
		PackGroup group = onlyPackWithId(new PackPlanner(PackOptions.defaults()).plan(world), "cell:0:0");
		PackOptions options = new PackOptions(
				PackOptions.DEFAULT_TARGET_PACK_BYTES,
				64,
				PackOptions.DEFAULT_MAX_ENTRIES_PER_PACK,
				Exclusions.defaults());
		BuiltPack pack = new PackWriter(options).write(world, group, tempDir.resolve("pack.cgp"));

		assertTrue(pack.parts().size() > 1, "small part limit must produce several parts");
		long expectedOffset = 0;
		ByteArrayOutputStream joined = new ByteArrayOutputStream();
		for (PackPart part : pack.parts()) {
			assertEquals(expectedOffset, part.offset());
			byte[] slice = readRange(pack.file(), part.offset(), part.size());
			assertEquals(part.sha256(), sha256(slice));
			joined.write(slice);
			expectedOffset += part.size();
		}
		assertEquals(pack.size(), expectedOffset);
		assertArrayEquals(Files.readAllBytes(pack.file()), joined.toByteArray());
	}

	@Test
	void removesPartialFileWhenSourceDisappears() throws IOException {
		Path world = createWorld();
		PackGroup group = onlyPackWithId(new PackPlanner(PackOptions.defaults()).plan(world), "cell:0:0");
		Path target = tempDir.resolve("doomed.cgp");

		Files.delete(world.resolve("region/r.0.0.mca"));

		assertThrows(IOException.class,
				() -> new PackWriter(PackOptions.defaults()).write(world, group, target));
		assertFalse(Files.exists(target), "a failed pack must not be left behind");
	}

	private Path createWorld() throws IOException {
		Path world = tempDir.resolve("world-" + System.nanoTime());
		TestWorlds.write(world, "region/r.0.0.mca", 300);
		TestWorlds.write(world, "entities/r.0.0.mca", 120);
		TestWorlds.write(world, "poi/r.0.0.mca", 40);
		TestWorlds.write(world, "level.dat", 64);
		return world;
	}

	private static PackGroup onlyPackWithId(List<PackGroup> packs, String id) {
		return packs.stream().filter(pack -> pack.id().equals(id)).findFirst().orElseThrow();
	}

	private static byte[] readRange(Path file, long offset, long size) throws IOException {
		ByteBuffer buffer = ByteBuffer.allocate((int) size);
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
			while (buffer.hasRemaining()) {
				int read = channel.read(buffer, offset + buffer.position());
				if (read < 0) {
					throw new IOException("unexpected end of file");
				}
			}
		}
		return buffer.array();
	}

	private static String sha256(byte[] bytes) {
		return Hex.encode(PackSplitter.sha256().digest(bytes));
	}
}
