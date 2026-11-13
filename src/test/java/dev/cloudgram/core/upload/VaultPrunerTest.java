package dev.cloudgram.core.upload;

import dev.cloudgram.core.Hex;
import dev.cloudgram.core.SnapshotManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultPrunerTest {
	private static final String WORLD_ID = "0f3c9a2e-7b1d-4f5a-9c3e-2a1b4c5d6e7f";

	@TempDir
	Path tempDir;

	@Test
	void keepsTheNewestThreeUploadedSnapshots() throws Exception {
		Path world = worldDir();
		for (int day = 1; day <= 5; day++) {
			writeUploaded(world, snapshotId(day), 1_800_000_000L + day * 3600L, 10);
		}

		VaultPruner.Result result = VaultPruner.prune(world, 3);

		assertEquals(2, result.deletedCount());
		assertEquals(List.of(), result.failed());
		assertTrue(Files.isDirectory(world.resolve(snapshotId(5))));
		assertTrue(Files.isDirectory(world.resolve(snapshotId(4))));
		assertTrue(Files.isDirectory(world.resolve(snapshotId(3))));
		assertFalse(Files.exists(world.resolve(snapshotId(2))));
		assertFalse(Files.exists(world.resolve(snapshotId(1))));
	}

	@Test
	void ordersByTheManifestTimestampNotTheFolderName() throws Exception {
		Path world = worldDir();
		// Folder names disagree with the manifests on purpose: zz is newest, aa is oldest.
		writeUploaded(world, "zz-oldest", 1_800_000_000L, 10);
		writeUploaded(world, "mm-middle", 1_800_000_100L, 10);
		writeUploaded(world, "aa-newest", 1_800_000_200L, 10);

		VaultPruner.prune(world, 2);

		assertTrue(Files.isDirectory(world.resolve("aa-newest")));
		assertTrue(Files.isDirectory(world.resolve("mm-middle")));
		assertFalse(Files.exists(world.resolve("zz-oldest")));
	}

	@Test
	void neverDeletesASnapshotThatWasPackedButNotUploaded() throws Exception {
		Path world = worldDir();
		writeUploaded(world, snapshotId(1), 1_800_000_100L, 10);
		writeUploaded(world, snapshotId(2), 1_800_000_200L, 10);
		// Oldest of all, but no upload record: this is a backup waiting to be resumed.
		writeSnapshot(world, snapshotId(3), 1_799_999_000L, 10, false);
		writeUploaded(world, snapshotId(4), 1_800_000_400L, 10);

		VaultPruner.Result result = VaultPruner.prune(world, 2);

		assertEquals(1, result.deletedCount());
		assertTrue(Files.isDirectory(world.resolve(snapshotId(3))),
				"a pending upload must survive pruning no matter how old it is");
		assertFalse(Files.exists(world.resolve(snapshotId(1))));
	}

	@Test
	void neverDeletesASnapshotWithoutAManifest() throws Exception {
		Path world = worldDir();
		writeUploaded(world, snapshotId(1), 1_800_000_100L, 10);
		writeUploaded(world, snapshotId(2), 1_800_000_200L, 10);
		writeUploaded(world, snapshotId(3), 1_800_000_300L, 10);
		// Still being packed: the manifest is the completion marker.
		Path halfWritten = world.resolve(snapshotId(4));
		Files.createDirectories(halfWritten.resolve("packs"));
		Files.write(halfWritten.resolve("packs").resolve("meta.cgp"), new byte[]{1, 2, 3});

		VaultPruner.prune(world, 1);

		assertTrue(Files.isDirectory(halfWritten));
		assertTrue(Files.isDirectory(world.resolve(snapshotId(3))));
		assertFalse(Files.exists(world.resolve(snapshotId(1))));
	}

	@Test
	void aCorruptManifestIsNeverDeleted() throws Exception {
		Path world = worldDir();
		writeUploaded(world, snapshotId(1), 1_800_000_100L, 10);
		writeUploaded(world, snapshotId(2), 1_800_000_200L, 10);
		Path corrupt = world.resolve(snapshotId(3));
		Files.createDirectories(corrupt);
		Files.writeString(corrupt.resolve("manifest.json"), "{not json");
		Files.writeString(corrupt.resolve(UploadRecord.FILE_NAME), "{}");

		VaultPruner.Result result = VaultPruner.prune(world, 1);

		assertEquals(1, result.deletedCount());
		assertTrue(Files.isDirectory(corrupt), "unreadable manifests stay on disk for inspection");
	}

	@Test
	void fewerSnapshotsThanTheLimitDeleteNothing() throws Exception {
		Path world = worldDir();
		writeUploaded(world, snapshotId(1), 1_800_000_100L, 10);
		writeUploaded(world, snapshotId(2), 1_800_000_200L, 10);

		VaultPruner.Result result = VaultPruner.prune(world, 3);

		assertEquals(List.of(), result.deleted());
		assertEquals(0L, result.bytesFreed());
		assertTrue(Files.isDirectory(world.resolve(snapshotId(1))));
	}

	@Test
	void reportsTheBytesItFreed() throws Exception {
		Path world = worldDir();
		writeUploaded(world, snapshotId(1), 1_800_000_100L, 1000);
		writeUploaded(world, snapshotId(2), 1_800_000_200L, 1000);

		VaultPruner.Result result = VaultPruner.prune(world, 1);

		assertEquals(1, result.deletedCount());
		assertTrue(result.bytesFreed() >= 1000L, "the deleted pack is counted");
	}

	@Test
	void anAbsentWorldDirectoryIsNotAnError() throws Exception {
		assertEquals(VaultPruner.Result.empty(),
				VaultPruner.prune(tempDir.resolve("missing"), 3));
	}

	@Test
	void prunesByWorldId() throws Exception {
		Path world = worldDir();
		writeUploaded(world, snapshotId(1), 1_800_000_100L, 10);
		writeUploaded(world, snapshotId(2), 1_800_000_200L, 10);

		VaultPruner.Result result = VaultPruner.pruneWorld(
				tempDir.resolve("vault"), WORLD_ID, 1);

		assertEquals(1, result.deletedCount());
		assertTrue(Files.isDirectory(world.resolve(snapshotId(2))));
	}

	@Test
	void rejectsALimitThatWouldDeleteEverything() {
		assertThrows(IllegalArgumentException.class, () -> VaultPruner.prune(worldDir(), 0));
		assertThrows(IllegalArgumentException.class, () -> VaultPruner.prune(null, 3));
	}

	private Path worldDir() throws IOException {
		Path world = tempDir.resolve("vault").resolve("0f3c9a2e");
		Files.createDirectories(world);
		return world;
	}

	private static String snapshotId(int day) {
		return String.format("202611%02dT120000Z-aaaaaa", day);
	}

	private void writeUploaded(Path world, String snapshotId, long createdAt, int packBytes)
			throws IOException {
		writeSnapshot(world, snapshotId, createdAt, packBytes, true);
	}

	/** A hand-built vault snapshot, so timestamps can be chosen without waiting. */
	private void writeSnapshot(
			Path world, String snapshotId, long createdAt, int packBytes, boolean uploaded)
			throws IOException {
		Path snapshot = world.resolve(snapshotId);
		Path packFile = snapshot.resolve("packs").resolve("meta.cgp");
		Files.createDirectories(packFile.getParent());
		byte[] bytes = new byte[packBytes];
		for (int i = 0; i < packBytes; i++) {
			bytes[i] = (byte) (i * 31 + 7);
		}
		Files.write(packFile, bytes);
		String sha = sha(bytes);

		SnapshotManifest manifest = new SnapshotManifest(
				SnapshotManifest.CURRENT_FORMAT,
				new SnapshotManifest.World(WORLD_ID, "Survival", "26.3", 4567),
				new SnapshotManifest.Snapshot(snapshotId, createdAt, "TEST-PC", "0.1.0"),
				List.of(new SnapshotManifest.PackRef("meta", sha, bytes.length,
						List.of(new SnapshotManifest.PartRef(0, 0, bytes.length, sha)))),
				new SnapshotManifest.Stats(1, bytes.length, bytes.length),
				null);
		Files.writeString(snapshot.resolve("manifest.json"), manifest.toJson());
		if (uploaded) {
			Files.writeString(snapshot.resolve(UploadRecord.FILE_NAME), "{}");
		}
	}

	private static String sha(byte[] bytes) {
		try {
			return Hex.encode(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
