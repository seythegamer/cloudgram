package dev.cloudgram.core.catalog;

import dev.cloudgram.core.Hex;
import dev.cloudgram.core.SnapshotManifest;
import dev.cloudgram.core.telegram.UploadedDocument;
import dev.cloudgram.core.upload.UploadRecord;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogTest {
	private static final String WORLD_ID = "0f3c9a2e-7b1d-4f5a-9c3e-2a1b4c5d6e7f";
	private static final String SNAPSHOT_ID = "20261111T230000Z-a1b2c3";

	@Test
	void roundTripsThroughJson() throws Exception {
		Catalog catalog = Catalog.empty().withSnapshot(snapshot("CG1-2", 2), 1234L);

		Catalog parsed = Catalog.fromJson(catalog.toJson());

		assertEquals(catalog, parsed);
		assertEquals(1234L, parsed.updatedAt());
		assertEquals(1, parsed.snapshots().size());
		assertEquals("CG1-2", parsed.findByRestoreCode("cg1-2").restoreCode());
		assertSame(parsed.snapshots().getFirst(), parsed.find(WORLD_ID, SNAPSHOT_ID));
	}

	@Test
	void withSnapshotReplacesTheSameSnapshot() {
		Catalog catalog = Catalog.empty()
				.withSnapshot(snapshot("CG1-2", 2), 1L)
				.withSnapshot(snapshot("CG1-9", 9), 2L);

		assertEquals(1, catalog.snapshots().size());
		assertEquals("CG1-9", catalog.snapshots().getFirst().restoreCode());
		assertEquals(2L, catalog.updatedAt());
	}

	@Test
	void keepsSnapshotsFromDifferentWorlds() {
		Catalog catalog = Catalog.empty()
				.withSnapshot(snapshot("CG1-2", 2), 1L)
				.withSnapshot(snapshot("0f3c9a2e-1111-4f5a-9c3e-2a1b4c5d6e7f", "CG1-3", 3), 2L);

		assertEquals(2, catalog.snapshots().size());
		assertNotNull(catalog.findByRestoreCode("CG1-2"));
		assertNotNull(catalog.findByRestoreCode("CG1-3"));
	}

	@Test
	void looksUpMissingCodesAndSnapshots() {
		Catalog catalog = Catalog.empty();

		assertNull(catalog.findByRestoreCode(null));
		assertNull(catalog.findByRestoreCode("CG1-404"));
		assertNull(catalog.find(WORLD_ID, SNAPSHOT_ID));
	}

	@Test
	void buildsAnEntryFromTheUploaderRecord() throws Exception {
		SnapshotManifest manifest = manifest();
		UploadedDocument part = new UploadedDocument(10, "part-file-id", "part.bin", 100);
		UploadedDocument manifestDocument =
				new UploadedDocument(11, "manifest-file-id", "manifest.json", 50);
		UploadRecord record = new UploadRecord(
				UploadRecord.CURRENT_FORMAT,
				WORLD_ID,
				SNAPSHOT_ID,
				42L,
				manifestDocument,
				List.of(new UploadRecord.PackUpload("meta", sha("meta"), List.of(part))),
				12L,
				"CG1-11",
				null,
				"aes-256-gcm/v1",
				"335ecb5127723917");

		Catalog.Snapshot snapshot = Catalog.Snapshot.from(manifest, record);

		assertEquals("CG1-11", snapshot.restoreCode());
		assertEquals(11L, snapshot.manifestMessageId());
		assertEquals("manifest-file-id", snapshot.manifestFileId());
		assertTrue(snapshot.isEncrypted());
		assertEquals("335ecb5127723917", snapshot.keyFingerprint());
		assertEquals("meta", snapshot.packs().getFirst().id());
		assertEquals(0, snapshot.packs().getFirst().parts().getFirst().index());
		assertEquals(10L, snapshot.packs().getFirst().parts().getFirst().messageId());
		assertEquals("part-file-id", snapshot.packs().getFirst().parts().getFirst().fileId());
	}

	@Test
	void rejectsUnknownFormatsAndInvalidEntries() {
		assertThrows(IllegalArgumentException.class,
				() -> new Catalog("cloudgram/catalog/999", 0L, List.of()));
		assertThrows(IllegalArgumentException.class,
				() -> new Catalog(Catalog.CURRENT_FORMAT, -1L, List.of()));
		assertThrows(IllegalArgumentException.class,
				() -> new Catalog.Snapshot(manifest(), " ", 1L, "file", null, null, List.of()));
	}

	private static Catalog.Snapshot snapshot(String restoreCode, long manifestMessageId) {
		return new Catalog.Snapshot(
				manifest(),
				restoreCode,
				manifestMessageId,
				"manifest-file-id",
				"aes-256-gcm/v1",
				"335ecb5127723917",
				List.of(new Catalog.Pack("meta", List.of(new Catalog.Part(0, 10L, "part-file-id")))));
	}

	private static Catalog.Snapshot snapshot(String worldId, String restoreCode, long messageId) {
		return new Catalog.Snapshot(
				manifest(worldId),
				restoreCode,
				messageId,
				"manifest-file-id",
				"aes-256-gcm/v1",
				"335ecb5127723917",
				List.of());
	}

	private static SnapshotManifest manifest() {
		return manifest(WORLD_ID);
	}

	private static SnapshotManifest manifest(String worldId) {
		SnapshotManifest.PartRef part = new SnapshotManifest.PartRef(0, 0, 4, sha("meta"));
		return new SnapshotManifest(
				SnapshotManifest.CURRENT_FORMAT,
				new SnapshotManifest.World(worldId, "Survival", "26.3", 4567),
				new SnapshotManifest.Snapshot(SNAPSHOT_ID, 1_800_000_000L, "TEST-PC", "0.1.0"),
				List.of(new SnapshotManifest.PackRef("meta", sha("meta"), 4, List.of(part))),
				new SnapshotManifest.Stats(2, 10, 4),
				null);
	}

	private static String sha(String value) {
		try {
			return Hex.encode(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
