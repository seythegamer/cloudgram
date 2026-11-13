package dev.cloudgram.core.catalog;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CatalogMirrorTest {
	private static final String WORLD_ID = "0f3c9a2e-7b1d-4f5a-9c3e-2a1b4c5d6e7f";
	private static final long CHAT_ID = 42L;

	@TempDir
	Path tempDir;

	@Test
	void roundTripsThroughDisk() throws Exception {
		Catalog catalog = Catalog.empty().withSnapshot(snapshot("CG1-7", 7), 1L);
		Path file = tempDir.resolve("cloudgram").resolve(CatalogMirror.FILE_NAME);

		new CatalogMirror(CatalogMirror.CURRENT_FORMAT, CHAT_ID, catalog).save(file);

		CatalogMirror loaded = CatalogMirror.load(file, CHAT_ID);
		assertEquals(CHAT_ID, loaded.chatId());
		assertEquals(catalog, loaded.catalog());
	}

	@Test
	void aMirrorFromAnotherChatIsNeverReused() throws Exception {
		Path file = tempDir.resolve(CatalogMirror.FILE_NAME);
		new CatalogMirror(CatalogMirror.CURRENT_FORMAT, CHAT_ID,
				Catalog.empty().withSnapshot(snapshot("CG1-7", 7), 1L)).save(file);

		assertNull(CatalogMirror.load(file, 99L),
				"file ids only exist in the chat that produced them");
	}

	@Test
	void aMissingOrUnreadableMirrorIsNotAnError() throws Exception {
		assertNull(CatalogMirror.load(tempDir.resolve("absent.json"), CHAT_ID));

		Path broken = tempDir.resolve("broken.json");
		Files.writeString(broken, "{not json");
		assertThrows(IOException.class, () -> CatalogMirror.load(broken, CHAT_ID));
	}

	@Test
	void rejectsInvalidMirrors() {
		assertThrows(IllegalArgumentException.class,
				() -> new CatalogMirror("cloudgram/catalog-mirror/999", CHAT_ID, Catalog.empty()));
		assertThrows(IllegalArgumentException.class,
				() -> new CatalogMirror(CatalogMirror.CURRENT_FORMAT, 0L, Catalog.empty()));
		assertThrows(IllegalArgumentException.class,
				() -> new CatalogMirror(CatalogMirror.CURRENT_FORMAT, CHAT_ID, null));
	}

	private static Catalog.Snapshot snapshot(String restoreCode, long messageId) {
		return new Catalog.Snapshot(
				manifest(),
				restoreCode,
				messageId,
				"manifest-file-id",
				"aes-256-gcm/v1",
				"335ecb5127723917",
				List.of(new Catalog.Pack("meta", List.of(new Catalog.Part(0, messageId - 1, "part-file-id")))),
				null,
				null);
	}

	private static SnapshotManifest manifest() {
		String sha = sha("pack");
		return new SnapshotManifest(
				SnapshotManifest.CURRENT_FORMAT,
				new SnapshotManifest.World(WORLD_ID, "Survival", "26.3", 4567),
				new SnapshotManifest.Snapshot("20261111T230000Z-a1b2c3", 1_800_000_000L, "TEST-PC", "0.1.0"),
				List.of(new SnapshotManifest.PackRef("meta", sha, 4,
						List.of(new SnapshotManifest.PartRef(0, 0, 4, sha)))),
				new SnapshotManifest.Stats(1, 4, 4),
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
