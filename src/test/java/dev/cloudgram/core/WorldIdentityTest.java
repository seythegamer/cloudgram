package dev.cloudgram.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldIdentityTest {
	@TempDir
	Path tempDir;

	@Test
	void createsAndPersistsIdentityOnFirstUse() throws IOException {
		Path world = tempDir.resolve("world");
		Files.createDirectories(world);

		WorldIdentity created = WorldIdentity.loadOrCreate(world);

		assertEquals(WorldIdentity.CURRENT_FORMAT, created.format());
		assertEquals(WorldIdentity.ORIGIN_LOCAL, created.origin());
		assertTrue(Files.isRegularFile(WorldIdentity.identityFile(world)));
		assertFalse(Files.exists(world.resolve(".cloudgram/world.json.tmp")), "temp file must be moved");
	}

	@Test
	void reusesIdentityAcrossLoads() throws IOException {
		Path world = tempDir.resolve("world");
		Files.createDirectories(world);

		WorldIdentity first = WorldIdentity.loadOrCreate(world);
		WorldIdentity second = WorldIdentity.loadOrCreate(world);

		assertEquals(first, second);
		assertEquals(first.id(), WorldIdentity.read(world).orElseThrow().id());
	}

	@Test
	void generatesDistinctIdentitiesForDistinctWorlds() throws IOException {
		Path a = tempDir.resolve("a");
		Path b = tempDir.resolve("b");
		Files.createDirectories(a);
		Files.createDirectories(b);

		assertNotEquals(WorldIdentity.loadOrCreate(a).id(), WorldIdentity.loadOrCreate(b).id());
	}

	@Test
	void returnsEmptyWhenIdentityIsMissing() throws IOException {
		assertEquals(Optional.empty(), WorldIdentity.read(tempDir.resolve("nothing-here")));
	}

	@Test
	void rejectsMalformedIdentityInsteadOfReplacingIt() throws IOException {
		Path world = tempDir.resolve("world");
		Files.createDirectories(world);
		writeRawIdentity(world, "{ this is not json");

		assertThrows(IOException.class, () -> WorldIdentity.read(world));
	}

	@Test
	void rejectsUnknownFormat() throws IOException {
		Path world = tempDir.resolve("world");
		Files.createDirectories(world);
		writeRawIdentity(world,
				"{\"format\":\"cloudgram/world/999\",\"id\":\"0f3c9a2e-7b1d-4f5a-9c3e-2a1b4c5d6e7f\",\"createdAt\":1,\"origin\":\"local\"}");

		assertThrows(IOException.class, () -> WorldIdentity.read(world));
	}

	@Test
	void rejectsNonUuidIdentity() throws IOException {
		Path world = tempDir.resolve("world");
		Files.createDirectories(world);
		writeRawIdentity(world,
				"{\"format\":\"cloudgram/world/1\",\"id\":\"not-a-uuid\",\"createdAt\":1,\"origin\":\"local\"}");

		assertThrows(IOException.class, () -> WorldIdentity.read(world));
	}

	@Test
	void writesPrettyJsonWithTrailingNewline() throws IOException {
		Path world = tempDir.resolve("world");
		Files.createDirectories(world);
		WorldIdentity.create(Instant.parse("2026-11-11T23:00:00Z")).write(world);

		String json = Files.readString(WorldIdentity.identityFile(world), StandardCharsets.UTF_8);

		assertTrue(json.startsWith("{\n"));
		assertTrue(json.endsWith("}\n"));
		assertTrue(json.contains("\"createdAt\": 1794438000"));
	}

	private static void writeRawIdentity(Path world, String json) throws IOException {
		Path file = WorldIdentity.identityFile(world);
		Files.createDirectories(file.getParent());
		Files.writeString(file, json, StandardCharsets.UTF_8);
	}
}
