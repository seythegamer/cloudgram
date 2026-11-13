package dev.cloudgram.core.upload;

import dev.cloudgram.core.PackOptions;
import dev.cloudgram.core.SnapshotManifest;
import dev.cloudgram.core.SnapshotRequest;
import dev.cloudgram.core.SnapshotResult;
import dev.cloudgram.core.SnapshotService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingUploadsTest {
	private static final String WORLD_ID = "0f3c9a2e-7b1d-4f5a-9c3e-2a1b4c5d6e7f";

	@TempDir
	Path tempDir;

	@Test
	void findsACompleteSnapshotThatWasNeverUploaded() throws Exception {
		SnapshotResult captured = capture("Survival");

		List<SnapshotResult> pending = PendingUploads.find(vault());

		assertEquals(1, pending.size());
		SnapshotResult found = pending.getFirst();
		assertEquals(captured.manifest().snapshot().id(), found.manifest().snapshot().id());
		assertEquals(captured.directory(), found.directory());
		assertEquals(captured.identity().id(), found.identity().id());
		assertEquals(captured.packs().size(), found.packs().size());
		assertTrue(found.packs().stream().allMatch(pack -> Files.isRegularFile(pack.file())));
	}

	@Test
	void skipsSnapshotsThatAlreadyHaveAnUploadRecord() throws Exception {
		SnapshotResult captured = capture("Survival");
		Files.writeString(captured.directory().resolve(UploadRecord.FILE_NAME), "{}");

		assertEquals(List.of(), PendingUploads.find(vault()));
	}

	@Test
	void skipsSnapshotsWhosePacksAreMissingOrIncomplete() throws Exception {
		SnapshotResult captured = capture("Survival");
		Files.delete(captured.packs().getFirst().file());

		assertEquals(List.of(), PendingUploads.find(vault()));
	}

	@Test
	void skipsASnapshotWhoseManifestIsStillBeingWritten() throws Exception {
		SnapshotResult captured = capture("Survival");
		Files.delete(captured.directory().resolve("manifest.json"));

		assertEquals(List.of(), PendingUploads.find(vault()));
	}

	@Test
	void ignoresDamagedDirectoriesInsteadOfFailingTheWholeScan() throws Exception {
		Path broken = vault().resolve("deadbeef").resolve("20260101T000000Z-000000");
		Files.createDirectories(broken.resolve("packs"));
		Files.writeString(broken.resolve("manifest.json"), "{not json");

		assertEquals(List.of(), PendingUploads.find(vault()));
	}

	@Test
	void returnsPendingSnapshotsOldestFirst() throws Exception {
		writeSnapshot("20261110T120000Z-aaaaaa", 1_800_000_000L, "first");
		writeSnapshot("20261111T120000Z-bbbbbb", 1_800_100_000L, "second");

		List<SnapshotResult> pending = PendingUploads.find(vault());

		assertEquals(2, pending.size());
		assertEquals("20261110T120000Z-aaaaaa", pending.get(0).manifest().snapshot().id());
		assertEquals("20261111T120000Z-bbbbbb", pending.get(1).manifest().snapshot().id());
	}

	@Test
	void anAbsentVaultHasNothingToResume() throws Exception {
		assertEquals(List.of(), PendingUploads.find(tempDir.resolve("missing")));
	}

	private Path vault() {
		return tempDir.resolve("vault");
	}

	private SnapshotResult capture(String worldName) throws IOException {
		Path world = tempDir.resolve("worlds").resolve(worldName);
		write(world.resolve("region/r.0.0.mca"), 400);
		write(world.resolve("level.dat"), 128);
		return new SnapshotService(vault(), PackOptions.defaults())
				.capture(world, new SnapshotRequest(worldName, "26.3", 4567, "TEST-PC", "0.1.0"));
	}

	/** A hand-built vault snapshot, so timestamps can be chosen without waiting. */
	private void writeSnapshot(String snapshotId, long createdAt, String content) throws Exception {
		Path snapshot = vault().resolve("0f3c9a2e").resolve(snapshotId);
		Path packFile = snapshot.resolve("packs").resolve("meta.cgp");
		Files.createDirectories(packFile.getParent());
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		Files.write(packFile, bytes);
		String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));

		SnapshotManifest manifest = new SnapshotManifest(
				SnapshotManifest.CURRENT_FORMAT,
				new SnapshotManifest.World(WORLD_ID, "Survival", "26.3", 4567),
				new SnapshotManifest.Snapshot(snapshotId, createdAt, "TEST-PC", "0.1.0"),
				List.of(new SnapshotManifest.PackRef("meta", sha, bytes.length,
						List.of(new SnapshotManifest.PartRef(0, 0, bytes.length, sha)))),
				new SnapshotManifest.Stats(1, bytes.length, bytes.length),
				null);
		Files.writeString(snapshot.resolve("manifest.json"), manifest.toJson());
	}

	private static void write(Path file, int size) throws IOException {
		Files.createDirectories(file.getParent());
		byte[] bytes = new byte[size];
		for (int i = 0; i < size; i++) {
			bytes[i] = (byte) (i * 31 + 7);
		}
		Files.write(file, bytes);
	}
}
