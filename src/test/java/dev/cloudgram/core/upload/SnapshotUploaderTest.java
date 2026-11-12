package dev.cloudgram.core.upload;

import dev.cloudgram.core.BuiltPack;
import dev.cloudgram.core.Hex;
import dev.cloudgram.core.ObjectNames;
import dev.cloudgram.core.PackPart;
import dev.cloudgram.core.PackSplitter;
import dev.cloudgram.core.SnapshotManifest;
import dev.cloudgram.core.SnapshotResult;
import dev.cloudgram.core.WorldIdentity;
import dev.cloudgram.core.crypto.Aad;
import dev.cloudgram.core.crypto.EncryptionKey;
import dev.cloudgram.core.crypto.PayloadCipher;
import dev.cloudgram.core.telegram.BotInfo;
import dev.cloudgram.core.telegram.IncomingMessage;
import dev.cloudgram.core.telegram.TelegramException;
import dev.cloudgram.core.telegram.TelegramTransport;
import dev.cloudgram.core.telegram.UploadedDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotUploaderTest {
	private static final String TOKEN = "123456789:TEST-TOKEN-NOT-A-REAL-BOT-0000";
	private static final String WORLD_ID = "0f3c9a2e-7b1d-4f5a-9c3e-2a1b4c5d6e7f";
	private static final String SNAPSHOT_ID = "20261111T230000Z-a1b2c3";
	private static final long CHAT_ID = 42L;

	@TempDir
	Path tempDir;

	private final FakeTransport transport = new FakeTransport();
	private final List<Long> sleeps = new ArrayList<>();
	private final EncryptionKey key = EncryptionKey.generate();
	private final PayloadCipher cipher = new PayloadCipher(key);
	private final SnapshotUploader uploader = new SnapshotUploader(
			transport, cipher, new UploadOptions(3, 0, 0), millis -> sleeps.add(millis));

	@Test
	void uploadsPartsThenManifestThenSummaryAndWritesTheRecord() throws Exception {
		BuiltPack cell = pack("cell:0:0", "AAAABBBBCCCC");
		BuiltPack meta = pack("meta", "meta-pack-content");
		SnapshotResult snapshot = snapshot(cell, meta);

		UploadRecord record = uploader.upload(TOKEN, CHAT_ID, snapshot);

		List<String> expectedNames = new ArrayList<>();
		for (BuiltPack pack : List.of(cell, meta)) {
			for (PackPart part : pack.parts()) {
				expectedNames.add(ObjectNames.partFileName(WORLD_ID, pack.id(), pack.sha256(), part.index()));
			}
		}
		expectedNames.add(ObjectNames.manifestFileName(WORLD_ID, SNAPSHOT_ID));

		assertEquals(expectedNames.size(), transport.documents.size());
		assertEquals(expectedNames, transport.documents.stream().map(SentDocument::fileName).toList());
		assertFalse(transport.texts.isEmpty(), "a summary message is sent after the manifest");
		assertTrue(sleeps.isEmpty(), "zero pacing means no sleeps");

		int index = 0;
		for (BuiltPack pack : List.of(cell, meta)) {
			for (PackPart part : pack.parts()) {
				byte[] encrypted = transport.documents.get(index++).content();
				assertTrue(PayloadCipher.isEncrypted(encrypted), "parts never leave in plaintext");
				assertArrayEquals(
						PartReader.read(pack.file(), part),
						cipher.decrypt(encrypted,
								Aad.part(WORLD_ID, SNAPSHOT_ID, pack.id(), part.index())));
			}
		}
		SentDocument manifest = transport.documents.get(index);
		assertTrue(manifest.fileName().endsWith("_manifest.json"));
		assertTrue(PayloadCipher.isEncrypted(manifest.content()), "the manifest is encrypted too");
		assertArrayEquals(
				Files.readAllBytes(snapshot.directory().resolve("manifest.json")),
				cipher.decrypt(manifest.content(), Aad.manifest(WORLD_ID, SNAPSHOT_ID)));

		assertEquals("CG1-" + record.manifest().messageId(), record.restoreCode());
		assertEquals(PayloadCipher.SCHEME, record.encryption());
		assertEquals(cipher.keyFingerprint(), record.keyFingerprint());
		assertTrue(record.isEncrypted());
		assertTrue(transport.texts.getFirst().contains(record.restoreCode()));
		assertTrue(transport.texts.getFirst().contains("Survival"));
		assertEquals(transport.summaryMessageId, record.summaryMessageId());
		assertEquals(CHAT_ID, transport.lastChatId);
		assertEquals(TOKEN, transport.lastToken);

		Path uploadJson = snapshot.directory().resolve(UploadRecord.FILE_NAME);
		assertTrue(Files.isRegularFile(uploadJson));
		assertEquals(record, UploadRecord.fromJson(Files.readString(uploadJson, StandardCharsets.UTF_8)));
		assertEquals(List.of("cell:0:0", "meta"), record.packs().stream().map(UploadRecord.PackUpload::id).toList());
	}

	@Test
	void retriesRateLimitsUsingRetryAfter() throws Exception {
		transport.documentFailure = new TelegramException(429, "Too Many Requests", 2);
		transport.documentFailures = 1;
		SnapshotResult snapshot = snapshot(pack("meta", "abcd"));

		UploadRecord record = uploader.upload(TOKEN, CHAT_ID, snapshot);

		assertEquals(List.of(3_000L), sleeps);
		assertEquals(3, transport.documentCalls, "failed attempt, retried part, then the manifest");
		assertEquals("CG1-" + record.manifest().messageId(), record.restoreCode());
	}

	@Test
	void retriesTransportFailuresWithBackoff() throws Exception {
		transport.documentFailure = new TelegramException("Cannot reach Telegram", new IOException("offline"));
		transport.documentFailures = 1;
		SnapshotUploader backoff = new SnapshotUploader(
				transport, cipher, new UploadOptions(3, 10, 0), millis -> sleeps.add(millis));

		backoff.upload(TOKEN, CHAT_ID, snapshot(pack("meta", "abcd")));

		assertEquals(List.of(10L), sleeps);
		assertEquals(3, transport.documentCalls, "failed attempt, retried part, then the manifest");
	}

	@Test
	void givesUpAfterTheConfiguredNumberOfAttempts() throws Exception {
		transport.documentFailure = new TelegramException(429, "Too Many Requests", 0);
		transport.documentFailures = 100;
		SnapshotUploader limited = new SnapshotUploader(
				transport, cipher, new UploadOptions(3, 0, 0), millis -> sleeps.add(millis));

		UploadException error = assertThrows(UploadException.class,
				() -> limited.upload(TOKEN, CHAT_ID, snapshot(pack("meta", "abcd"))));

		assertTrue(error.getMessage().contains("Could not upload"));
		assertEquals(3, transport.documentCalls, "maxAttempts is the total number of tries");
		assertEquals(List.of(1_000L, 1_000L), sleeps, "the last failure is not followed by a sleep");
	}

	@Test
	void aFailedSummaryDoesNotDiscardTheUpload() throws Exception {
		transport.summaryFailure = new TelegramException(400, "Bad Request: message is too long", 0);
		SnapshotResult snapshot = snapshot(pack("meta", "abcd"));

		UploadRecord record = uploader.upload(TOKEN, CHAT_ID, snapshot);

		assertEquals(0, record.summaryMessageId());
		assertNotNull(record.warning());
		assertTrue(record.warning().contains("summary"));
		assertTrue(Files.isRegularFile(snapshot.directory().resolve(UploadRecord.FILE_NAME)));
	}

	@Test
	void refusesToUploadAPartThatChangedOnDisk() throws Exception {
		BuiltPack pack = pack("meta", "abcd");
		Files.write(pack.file(), "XXXX".getBytes(StandardCharsets.UTF_8));

		UploadException error = assertThrows(UploadException.class,
				() -> uploader.upload(TOKEN, CHAT_ID, snapshot(pack)));

		assertTrue(error.getMessage().contains("part 0"));
		assertTrue(transport.documents.isEmpty(), "nothing is sent when the local copy is corrupt");
	}

	private BuiltPack pack(String id, String content) throws IOException {
		Path file = tempDir.resolve("packs").resolve(ObjectNames.safePackId(id) + ".cgp");
		Files.createDirectories(file.getParent());
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		Files.write(file, bytes);
		return new BuiltPack(
				id,
				file,
				Files.size(file),
				sha256(bytes),
				new PackSplitter(4).split(file));
	}

	private SnapshotResult snapshot(BuiltPack... packs) throws IOException {
		WorldIdentity identity = new WorldIdentity(
				WorldIdentity.CURRENT_FORMAT, WORLD_ID, 1L, WorldIdentity.ORIGIN_LOCAL);
		Path directory = tempDir.resolve("vault/0f3c9a2e").resolve(SNAPSHOT_ID);
		Files.createDirectories(directory);

		List<SnapshotManifest.PackRef> refs = new ArrayList<>();
		for (BuiltPack pack : packs) {
			refs.add(new SnapshotManifest.PackRef(
					pack.id(),
					pack.sha256(),
					pack.size(),
					pack.parts().stream()
							.map(part -> new SnapshotManifest.PartRef(
									part.index(), part.offset(), part.size(), part.sha256()))
							.toList()));
		}
		SnapshotManifest manifest = new SnapshotManifest(
				SnapshotManifest.CURRENT_FORMAT,
				new SnapshotManifest.World(WORLD_ID, "Survival", "26.3", 4567),
				new SnapshotManifest.Snapshot(SNAPSHOT_ID, 1_800_000_000L, "TEST-PC", "0.1.0"),
				refs,
				new SnapshotManifest.Stats(7, 1234, packs.length == 0 ? 0 : packs[0].size()),
				null);
		Files.writeString(directory.resolve("manifest.json"), manifest.toJson() + "\n", StandardCharsets.UTF_8);
		return new SnapshotResult(identity, manifest, directory, List.of(packs));
	}

	private static String sha256(byte[] bytes) {
		try {
			return Hex.encode(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private record SentDocument(String fileName, byte[] content, String caption) {
	}

	private static final class FakeTransport implements TelegramTransport {
		private final List<SentDocument> documents = new ArrayList<>();
		private final List<String> texts = new ArrayList<>();
		private long nextMessageId = 100;
		private long summaryMessageId;
		private int documentCalls;
		private int documentFailures;
		private TelegramException documentFailure;
		private TelegramException summaryFailure;
		private String lastToken;
		private long lastChatId;

		@Override
		public BotInfo getMe(String token) {
			throw new UnsupportedOperationException();
		}

		@Override
		public List<IncomingMessage> getUpdates(String token, long offset, int timeoutSeconds) {
			throw new UnsupportedOperationException();
		}

		@Override
		public long sendMessage(String token, long chatId, String text) throws TelegramException {
			lastToken = token;
			lastChatId = chatId;
			if (summaryFailure != null) {
				throw summaryFailure;
			}
			texts.add(text);
			summaryMessageId = nextMessageId++;
			return summaryMessageId;
		}

		@Override
		public UploadedDocument sendDocument(
				String token, long chatId, String fileName, byte[] content, String caption)
				throws TelegramException {
			lastToken = token;
			lastChatId = chatId;
			documentCalls++;
			if (documentCalls <= documentFailures) {
				throw documentFailure;
			}
			long id = nextMessageId++;
			documents.add(new SentDocument(fileName, content.clone(), caption));
			return new UploadedDocument(id, "file-" + id, fileName, content.length);
		}

		@Override
		public void pinChatMessage(String token, long chatId, long messageId) {
			throw new UnsupportedOperationException();
		}
	}
}
