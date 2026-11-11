package dev.cloudgram.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotServiceTest {
	private static final Instant FIXED_TIME = Instant.parse("2026-11-11T23:00:00Z");

	@TempDir
	Path tempDir;

	@Test
	void writesPacksManifestAndIdentity() throws IOException {
		Path world = createWorld();
		SnapshotService service = new SnapshotService(tempDir.resolve("vault"), PackOptions.defaults());

		SnapshotResult result = service.capture(world, request(), FIXED_TIME, "a1b2c3");

		assertEquals("20261111T230000Z-a1b2c3", result.manifest().snapshot().id());
		assertEquals(result.identity(), WorldIdentity.loadOrCreate(world));

		Path snapshotDir = result.directory();
		assertTrue(snapshotDir.startsWith(tempDir.resolve("vault").toAbsolutePath().normalize()));
		assertTrue(Files.isRegularFile(snapshotDir.resolve("manifest.json")));
		assertFalse(Files.exists(snapshotDir.resolve("manifest.json.tmp")));

		for (BuiltPack pack : result.packs()) {
			assertTrue(Files.isRegularFile(pack.file()), "pack must exist: " + pack.file());
			assertEquals(pack.sha256(), sha256(Files.readAllBytes(pack.file())));
			assertEquals(pack.size(), Files.size(pack.file()));
			assertTrue(pack.file().startsWith(snapshotDir.resolve("packs")));
		}

		SnapshotManifest manifest = SnapshotManifest.fromJson(
				Files.readString(snapshotDir.resolve("manifest.json"), StandardCharsets.UTF_8));
		assertEquals(result.manifest(), manifest);
		assertEquals(result.identity().id(), manifest.world().id());
		assertEquals("Survival", manifest.world().name());
		assertEquals("26.3", manifest.world().mcVersion());
		assertEquals(4567, manifest.world().dataVersion());
		assertEquals("TEST-PC", manifest.snapshot().device());
		assertEquals("0.1.0", manifest.snapshot().mod());
		assertEquals(FIXED_TIME.getEpochSecond(), manifest.snapshot().createdAt());
		assertNull(manifest.prev());
	}

	@Test
	void countsFilesAndBytesWithoutExcludedOnes() throws IOException {
		Path world = createWorld();
		SnapshotService service = new SnapshotService(tempDir.resolve("vault"), PackOptions.defaults());

		SnapshotResult result = service.capture(world, request(), FIXED_TIME, "a1b2c3");
		SnapshotManifest.Stats stats = result.manifest().stats();

		// region, entities, level.dat, level.dat_old and the generated .cloudgram/world.json
		assertEquals(5, stats.files());
		assertTrue(stats.rawBytes() >= 400 + 200 + 128 + 128);
		assertEquals(result.totalPackBytes(), stats.packBytes());
		assertEquals(result.packs().stream().mapToLong(BuiltPack::size).sum(), stats.packBytes());
	}

	@Test
	void metaPackCarriesTheWorldIdentity() throws IOException {
		Path world = createWorld();
		SnapshotService service = new SnapshotService(tempDir.resolve("vault"), PackOptions.defaults());

		SnapshotResult result = service.capture(world, request(), FIXED_TIME, "a1b2c3");
		BuiltPack meta = result.packs().stream()
				.filter(pack -> pack.id().equals("meta"))
				.findFirst()
				.orElseThrow();

		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(meta.file().toFile())) {
			assertTrue(zip.getEntry(".cloudgram/world.json") != null, "identity must travel with the snapshot");
			assertTrue(zip.getEntry("level.dat") != null);
			assertTrue(zip.getEntry("level.dat_old") != null);
			assertTrue(zip.getEntry("session.lock") == null);
		}
	}

	@Test
	void keepsIdentityStableAcrossSnapshots() throws IOException {
		Path world = createWorld();
		SnapshotService service = new SnapshotService(tempDir.resolve("vault"), PackOptions.defaults());

		SnapshotResult first = service.capture(world, request(), FIXED_TIME, "aaaaaa");
		SnapshotResult second = service.capture(world, request(), FIXED_TIME, "bbbbbb");

		assertEquals(first.identity().id(), second.identity().id());
		assertNotEquals(first.directory(), second.directory());
		assertTrue(Files.isRegularFile(first.directory().resolve("manifest.json")));
		assertTrue(Files.isRegularFile(second.directory().resolve("manifest.json")));
		assertEquals(first.directory().getParent(), second.directory().getParent());
		assertEquals(first.directory().getParent().getFileName().toString(),
				first.identity().id().replace("-", "").substring(0, 8));
	}

	@Test
	void packHashesAreStableAcrossSnapshots() throws IOException {
		Path world = createWorld();
		SnapshotService service = new SnapshotService(tempDir.resolve("vault"), PackOptions.defaults());

		SnapshotResult first = service.capture(world, request(), FIXED_TIME, "aaaaaa");
		SnapshotResult second = service.capture(world, request(), FIXED_TIME, "bbbbbb");

		// Same world bytes -> same pack hashes, which is what deduplication will rely on.
		assertEquals(hashById(first), hashById(second));
	}

	@Test
	void removesStagingDirectoryWhenCaptureFails() throws IOException {
		Path world = createWorld();
		WorldIdentity identity = WorldIdentity.loadOrCreate(world);
		String snapshotId = SnapshotManifest.newSnapshotId(FIXED_TIME, "a1b2c3");
		Path snapshotDir = tempDir.resolve("vault")
				.resolve(identity.id().replace("-", "").substring(0, 8))
				.resolve(snapshotId);
		Files.createDirectories(snapshotDir);
		// A regular file where the packs directory must go makes pack staging fail.
		Files.writeString(snapshotDir.resolve("packs"), "not a directory", StandardCharsets.UTF_8);

		SnapshotService service = new SnapshotService(tempDir.resolve("vault"), PackOptions.defaults());

		assertThrows(IOException.class, () -> service.capture(world, request(), FIXED_TIME, "a1b2c3"));
		assertFalse(Files.exists(snapshotDir), "failed snapshot must not be left behind");
	}

	@Test
	void rejectsWorldWithoutLevelData() throws IOException {
		Path world = tempDir.resolve("empty-world");
		Files.createDirectories(world);
		SnapshotService service = new SnapshotService(tempDir.resolve("vault"), PackOptions.defaults());

		assertThrows(IOException.class, () -> service.capture(world, request(), FIXED_TIME, "a1b2c3"));
		assertFalse(Files.exists(WorldIdentity.identityFile(world)),
				"a directory that is not a world must not receive an identity");
	}

	@Test
	void rejectsMissingWorld() {
		SnapshotService service = new SnapshotService(tempDir.resolve("vault"), PackOptions.defaults());
		assertThrows(IOException.class, () -> service.capture(tempDir.resolve("nope"), request()));
	}

	@Test
	void safePackIdsAvoidPlatformSpecificCharacters() {
		assertEquals("cell_n1_2", ObjectNames.safePackId("cell:-1:2"));
		assertEquals("cell_0_0", ObjectNames.safePackId("cell:0:0"));
		assertEquals("meta_2", ObjectNames.safePackId("meta~2"));
		assertEquals("meta", ObjectNames.safePackId("meta"));
		assertThrows(IllegalArgumentException.class, () -> ObjectNames.safePackId(" "));
	}

	@Test
	void requestRejectsMissingFields() {
		assertThrows(IllegalArgumentException.class,
				() -> new SnapshotRequest(" ", "26.3", 1, "pc", "0.1.0"));
		assertThrows(IllegalArgumentException.class,
				() -> new SnapshotRequest("World", " ", 1, "pc", "0.1.0"));
		assertThrows(IllegalArgumentException.class,
				() -> new SnapshotRequest("World", "26.3", -1, "pc", "0.1.0"));
		assertThrows(IllegalArgumentException.class,
				() -> new SnapshotRequest("World", "26.3", 1, " ", "0.1.0"));
		assertThrows(IllegalArgumentException.class,
				() -> new SnapshotRequest("World", "26.3", 1, "pc", ""));
	}

	private Path createWorld() throws IOException {
		Path world = tempDir.resolve("saves/Survival");
		TestWorlds.write(world, "region/r.0.0.mca", 400);
		TestWorlds.write(world, "entities/r.0.0.mca", 200);
		TestWorlds.write(world, "level.dat", 128);
		TestWorlds.write(world, "level.dat_old", 128);
		TestWorlds.write(world, "session.lock", 8);
		TestWorlds.write(world, "logs/latest.log", 8);
		return world;
	}

	private static SnapshotRequest request() {
		return new SnapshotRequest("Survival", "26.3", 4567, "TEST-PC", "0.1.0");
	}

	private static Map<String, String> hashById(SnapshotResult result) {
		return result.packs().stream()
				.collect(Collectors.toMap(BuiltPack::id, BuiltPack::sha256));
	}

	private static String sha256(byte[] bytes) {
		return Hex.encode(PackSplitter.sha256().digest(bytes));
	}
}
