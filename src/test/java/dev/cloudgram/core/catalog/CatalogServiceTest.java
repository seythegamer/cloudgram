package dev.cloudgram.core.catalog;

import dev.cloudgram.core.Hex;
import dev.cloudgram.core.SnapshotManifest;
import dev.cloudgram.core.crypto.Aad;
import dev.cloudgram.core.crypto.CryptoException;
import dev.cloudgram.core.crypto.EncryptionKey;
import dev.cloudgram.core.crypto.PayloadCipher;
import dev.cloudgram.core.telegram.BotInfo;
import dev.cloudgram.core.telegram.ChatInfo;
import dev.cloudgram.core.telegram.IncomingMessage;
import dev.cloudgram.core.telegram.PinnedMessage;
import dev.cloudgram.core.telegram.RemoteDocument;
import dev.cloudgram.core.telegram.RemoteFile;
import dev.cloudgram.core.telegram.TelegramException;
import dev.cloudgram.core.telegram.TelegramTransport;
import dev.cloudgram.core.telegram.UploadedDocument;
import dev.cloudgram.core.upload.UploadRecord;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogServiceTest {
	private static final String TOKEN = "123456789:TEST-TOKEN-NOT-A-REAL-BOT-0000";
	private static final long CHAT_ID = 42L;
	private static final String WORLD_ID = "0f3c9a2e-7b1d-4f5a-9c3e-2a1b4c5d6e7f";

	private final FakeTransport transport = new FakeTransport();
	private final EncryptionKey key = EncryptionKey.generate();
	private final PayloadCipher cipher = new PayloadCipher(key);
	private final CatalogService service = new CatalogService(transport, cipher);

	@Test
	void noPinnedMessageMeansNoCatalog() throws Exception {
		assertEquals(Catalog.empty(), service.fetch(TOKEN, CHAT_ID).catalog());
		assertEquals(0L, service.fetch(TOKEN, CHAT_ID).messageId());
	}

	@Test
	void aPinnedTextMessageMeansNoCatalogYet() throws Exception {
		transport.pinned = new PinnedMessage(5, "Cloudgram is connected to this chat.", null);

		assertEquals(Catalog.empty(), service.fetch(TOKEN, CHAT_ID).catalog());
	}

	@Test
	void ignoresAnUnrelatedPinnedDocument() throws Exception {
		transport.pinned = new PinnedMessage(5, null, new RemoteDocument("f1", "notes.txt", 3));
		transport.files.put("f1", "abc".getBytes(StandardCharsets.UTF_8));

		assertEquals(Catalog.empty(), service.fetch(TOKEN, CHAT_ID).catalog());
	}

	@Test
	void publishEncryptsPinsAndDeletesTheOldMessage() throws Exception {
		Catalog first = Catalog.empty().withSnapshot(snapshot("CG1-2", 2), 1L);

		CatalogService.Fetched published = service.publish(TOKEN, CHAT_ID, first, 0L);

		assertTrue(published.messageId() > 0);
		assertEquals(1, transport.uploads.size());
		UploadedDocument upload = transport.uploads.getFirst();
		assertEquals(CatalogService.FILE_NAME, upload.fileName());
		assertTrue(PayloadCipher.isEncrypted(transport.files.get(upload.fileId())),
				"the catalog must never leave in plaintext");
		assertEquals(List.of(published.messageId()), transport.pins);

		Catalog second = first.withSnapshot(snapshot("CG1-9", 9), 2L);
		CatalogService.Fetched republished = service.publish(TOKEN, CHAT_ID, second, published.messageId());

		assertEquals(List.of(published.messageId()), transport.deleted,
				"the superseded catalog message is removed after the new pin");
		assertEquals(second, service.fetch(TOKEN, CHAT_ID).catalog());
		assertEquals(republished.messageId(), service.fetch(TOKEN, CHAT_ID).messageId());
	}

	@Test
	void fetchRejectsAnotherKey() throws Exception {
		Catalog catalog = Catalog.empty().withSnapshot(snapshot("CG1-2", 2), 1L);
		service.publish(TOKEN, CHAT_ID, catalog, 0L);

		CatalogService wrong = new CatalogService(
				transport, new PayloadCipher(EncryptionKey.generate()));

		assertThrows(CryptoException.class, () -> wrong.fetch(TOKEN, CHAT_ID));
	}

	@Test
	void publishSnapshotMergesIntoTheExistingCatalog() throws Exception {
		CatalogService.Fetched first = service.publishSnapshot(
				TOKEN, CHAT_ID, manifest("20261111T220000Z-aaaaaa"), record(manifest("20261111T220000Z-aaaaaa"), "CG1-10", 10L));
		assertEquals(1, first.catalog().snapshots().size());

		CatalogService.Fetched second = service.publishSnapshot(
				TOKEN, CHAT_ID, manifest("20261111T230000Z-bbbbbb"), record(manifest("20261111T230000Z-bbbbbb"), "CG1-20", 20L));

		assertEquals(2, second.catalog().snapshots().size());
		assertEquals(second.catalog(), service.fetch(TOKEN, CHAT_ID).catalog());
		assertEquals("CG1-20", second.catalog().findByRestoreCode("cg1-20").restoreCode());
	}

	@Test
	void rejectsInvalidConfiguration() {
		assertThrows(IllegalArgumentException.class,
				() -> new CatalogService(null, cipher));
		assertThrows(IllegalArgumentException.class,
				() -> new CatalogService(transport, null));
	}

	private static Catalog.Snapshot snapshot(String restoreCode, long messageId) {
		SnapshotManifest manifest = manifest("20261111T230000Z-a1b2c3");
		return new Catalog.Snapshot(
				manifest,
				restoreCode,
				messageId,
				"manifest-" + messageId,
				PayloadCipher.SCHEME,
				"335ecb5127723917",
				List.of(new Catalog.Pack("meta", List.of(new Catalog.Part(0, messageId - 1, "part-" + messageId)))));
	}

	private static SnapshotManifest manifest(String snapshotId) {
		String sha = sha("pack");
		SnapshotManifest.PartRef part = new SnapshotManifest.PartRef(0, 0, 4, sha);
		return new SnapshotManifest(
				SnapshotManifest.CURRENT_FORMAT,
				new SnapshotManifest.World(WORLD_ID, "Survival", "26.3", 4567),
				new SnapshotManifest.Snapshot(snapshotId, 1_800_000_000L, "TEST-PC", "0.1.0"),
				List.of(new SnapshotManifest.PackRef("meta", sha, 4, List.of(part))),
				new SnapshotManifest.Stats(1, 4, 4),
				null);
	}

	private static UploadRecord record(SnapshotManifest manifest, String restoreCode, long messageId) {
		return new UploadRecord(
				UploadRecord.CURRENT_FORMAT,
				WORLD_ID,
				manifest.snapshot().id(),
				CHAT_ID,
				new UploadedDocument(messageId, "manifest-" + messageId, "manifest.json", 100),
				List.of(new UploadRecord.PackUpload(
						"meta", sha("pack"),
						List.of(new UploadedDocument(messageId - 1, "part-" + messageId, "part.bin", 4)))),
				messageId + 1,
				restoreCode,
				null,
				PayloadCipher.SCHEME,
				"335ecb5127723917");
	}

	private static String sha(String value) {
		try {
			return Hex.encode(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static final class FakeTransport implements TelegramTransport {
		private final Map<String, byte[]> files = new HashMap<>();
		private final Map<Long, RemoteDocument> messages = new HashMap<>();
		private final List<UploadedDocument> uploads = new ArrayList<>();
		private final List<Long> pins = new ArrayList<>();
		private final List<Long> deleted = new ArrayList<>();
		private PinnedMessage pinned;
		private long nextId = 100;

		@Override
		public BotInfo getMe(String token) {
			throw new UnsupportedOperationException();
		}

		@Override
		public List<IncomingMessage> getUpdates(String token, long offset, int timeoutSeconds) {
			throw new UnsupportedOperationException();
		}

		@Override
		public long sendMessage(String token, long chatId, String text) {
			throw new UnsupportedOperationException();
		}

		@Override
		public UploadedDocument sendDocument(
				String token, long chatId, String fileName, byte[] content, String caption) {
			long id = nextId++;
			String fileId = "file-" + id;
			files.put(fileId, content.clone());
			RemoteDocument document = new RemoteDocument(fileId, fileName, content.length);
			messages.put(id, document);
			UploadedDocument uploaded = new UploadedDocument(id, fileId, fileName, content.length);
			uploads.add(uploaded);
			return uploaded;
		}

		@Override
		public void pinChatMessage(String token, long chatId, long messageId) {
			pins.add(messageId);
			pinned = new PinnedMessage(messageId, null, messages.get(messageId));
		}

		@Override
		public ChatInfo getChat(String token, long chatId) {
			return new ChatInfo(chatId, "private", "Test", pinned);
		}

		@Override
		public RemoteFile getFile(String token, String fileId) {
			byte[] bytes = files.get(fileId);
			if (bytes == null) {
				throw new IllegalStateException("unknown file " + fileId);
			}
			return new RemoteFile(fileId, "downloads/" + fileId, bytes.length);
		}

		@Override
		public byte[] downloadFile(String token, String filePath) {
			byte[] bytes = files.get(filePath.substring("downloads/".length()));
			if (bytes == null) {
				throw new IllegalStateException("unknown path " + filePath);
			}
			return bytes.clone();
		}

		@Override
		public void deleteMessage(String token, long chatId, long messageId) {
			deleted.add(messageId);
		}
	}
}
