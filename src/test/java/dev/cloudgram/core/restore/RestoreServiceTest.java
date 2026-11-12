package dev.cloudgram.core.restore;

import dev.cloudgram.core.Exclusions;
import dev.cloudgram.core.PackOptions;
import dev.cloudgram.core.PackPart;
import dev.cloudgram.core.SnapshotManifest;
import dev.cloudgram.core.SnapshotRequest;
import dev.cloudgram.core.SnapshotResult;
import dev.cloudgram.core.SnapshotService;
import dev.cloudgram.core.WorldIdentity;
import dev.cloudgram.core.catalog.Catalog;
import dev.cloudgram.core.crypto.Aad;
import dev.cloudgram.core.crypto.EncryptionKey;
import dev.cloudgram.core.crypto.PayloadCipher;
import dev.cloudgram.core.telegram.BotInfo;
import dev.cloudgram.core.telegram.IncomingMessage;
import dev.cloudgram.core.telegram.RemoteFile;
import dev.cloudgram.core.telegram.TelegramTransport;
import dev.cloudgram.core.telegram.UploadedDocument;
import dev.cloudgram.core.upload.PartReader;
import dev.cloudgram.core.upload.UploadRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestoreServiceTest {
	private static final String TOKEN = "123456789:TEST-TOKEN-NOT-A-REAL-BOT-0000";
	private static final String CHAT_ID = "42";

	@TempDir
	Path tempDir;

	@Test
	void restoresAnEncryptedSnapshotAsANewWorld() throws Exception {
		Path world = createWorld("Survival");
		SnapshotResult snapshot = capture(world);
		EncryptionKey key = EncryptionKey.generate();
		PayloadCipher cipher = new PayloadCipher(key);
		Fixture fixture = upload(snapshot, cipher, true);

		RestoreService service = service(fixture.transport, cipher);
		Path saves = tempDir.resolve("saves");
		RestoreResult result = service.restore(TOKEN, fixture.catalog, saves, null);

		assertEquals("Survival", result.worldName());
		assertEquals(snapshot.manifest().stats().files(), result.files());
		assertTrue(Files.isDirectory(result.directory()));
		assertSamePackedFiles(world, result.directory());
		assertEquals(snapshot.identity().id(),
				WorldIdentity.read(result.directory()).orElseThrow().id());
	}

	@Test
	void restoresPlaintextSnapshotsWithoutUsingTheCipher() throws Exception {
		Path world = createWorld("Old world");
		SnapshotResult snapshot = capture(world);
		PayloadCipher cipher = new PayloadCipher(EncryptionKey.generate());
		Fixture fixture = upload(snapshot, cipher, false);

		RestoreResult result = service(fixture.transport, cipher)
				.restore(TOKEN, fixture.catalog, tempDir.resolve("saves"), null);

		assertSamePackedFiles(world, result.directory());
	}

	@Test
	void neverOverwritesAnExistingSave() throws Exception {
		Path world = createWorld("Survival");
		SnapshotResult snapshot = capture(world);
		PayloadCipher cipher = new PayloadCipher(EncryptionKey.generate());
		Fixture fixture = upload(snapshot, cipher, true);
		RestoreService service = service(fixture.transport, cipher);
		Path saves = tempDir.resolve("saves");

		RestoreResult first = service.restore(TOKEN, fixture.catalog, saves, null);
		RestoreResult second = service.restore(TOKEN, fixture.catalog, saves, null);

		assertEquals("Survival", first.worldName());
		assertEquals("Survival (Cloudgram 2)", second.worldName());
		assertEquals(first.files(), second.files());
	}

	@Test
	void aWrongKeyFailsAndLeavesSavesUntouched() throws Exception {
		Path world = createWorld("Survival");
		SnapshotResult snapshot = capture(world);
		PayloadCipher cipher = new PayloadCipher(EncryptionKey.generate());
		Fixture fixture = upload(snapshot, cipher, true);
		PayloadCipher wrong = new PayloadCipher(EncryptionKey.generate());
		Path saves = tempDir.resolve("saves");

		RestoreException error = assertThrows(RestoreException.class,
				() -> service(fixture.transport, wrong).restore(TOKEN, fixture.catalog, saves, null));

		assertTrue(error.getMessage().contains("Restore failed"));
		assertFalse(Files.exists(saves.resolve("Survival")));
		assertDirectoryEmpty(saves);
	}

	@Test
	void aTamperedPartFailsItsHashCheck() throws Exception {
		Path world = createWorld("Survival");
		SnapshotResult snapshot = capture(world);
		PayloadCipher cipher = new PayloadCipher(EncryptionKey.generate());
		Fixture fixture = upload(snapshot, cipher, true);
		String firstPart = fixture.catalog.packs().getFirst().parts().getFirst().fileId();
		byte[] bytes = fixture.transport.files.get(firstPart);
		bytes[bytes.length - 1] ^= 0x01;
		Path saves = tempDir.resolve("saves");

		assertThrows(RestoreException.class,
				() -> service(fixture.transport, cipher).restore(TOKEN, fixture.catalog, saves, null));

		assertDirectoryEmpty(saves);
	}

	@Test
	void rejectsACatalogThatDoesNotMatchTheManifest() throws Exception {
		Path world = createWorld("Survival");
		SnapshotResult snapshot = capture(world);
		PayloadCipher cipher = new PayloadCipher(EncryptionKey.generate());
		Fixture fixture = upload(snapshot, cipher, true);
		Catalog.Snapshot broken = new Catalog.Snapshot(
				fixture.catalog.manifest(),
				fixture.catalog.restoreCode(),
				fixture.catalog.manifestMessageId(),
				fixture.catalog.manifestFileId(),
				fixture.catalog.encryption(),
				fixture.catalog.keyFingerprint(),
				List.of());

		assertThrows(RestoreException.class,
				() -> service(fixture.transport, cipher)
						.restore(TOKEN, broken, tempDir.resolve("saves"), null));
	}

	private RestoreService service(TelegramTransport transport, PayloadCipher cipher) {
		return new RestoreService(
				transport, cipher, new RestoreOptions(2, 0, 0), millis -> {
					throw new IllegalStateException("unexpected sleep");
				});
	}

	private SnapshotResult capture(Path world) throws IOException {
		PackOptions options = new PackOptions(8 * 1024, 4 * 1024, 10_000, Exclusions.defaults());
		SnapshotService service = new SnapshotService(tempDir.resolve("vault"), options);
		return service.capture(world, new SnapshotRequest(
				world.getFileName().toString(), "26.3", 4567, "TEST-PC", "0.1.0"));
	}

	private Fixture upload(SnapshotResult snapshot, PayloadCipher cipher, boolean encrypted)
			throws Exception {
		String worldId = snapshot.identity().id();
		String snapshotId = snapshot.manifest().snapshot().id();
		FakeTransport transport = new FakeTransport();
		long nextId = 100;

		List<UploadRecord.PackUpload> packs = new ArrayList<>();
		for (var pack : snapshot.packs()) {
			List<UploadedDocument> parts = new ArrayList<>();
			for (PackPart part : pack.parts()) {
				byte[] plain = PartReader.read(pack.file(), part);
				byte[] stored = encrypted
						? cipher.encrypt(plain, Aad.part(worldId, snapshotId, pack.id(), part.index()))
						: plain;
				String fileId = "part-" + pack.id() + "-" + part.index();
				transport.files.put(fileId, stored);
				long messageId = nextId++;
				parts.add(new UploadedDocument(messageId, fileId, fileId + ".bin", stored.length));
			}
			packs.add(new UploadRecord.PackUpload(pack.id(), pack.sha256(), parts));
		}

		byte[] manifestPlain = Files.readAllBytes(snapshot.directory().resolve("manifest.json"));
		byte[] manifestStored = encrypted
				? cipher.encrypt(manifestPlain, Aad.manifest(worldId, snapshotId))
				: manifestPlain;
		long manifestId = nextId++;
		String manifestFileId = "manifest-" + manifestId;
		transport.files.put(manifestFileId, manifestStored);
		UploadedDocument manifestDocument =
				new UploadedDocument(manifestId, manifestFileId, "manifest.json", manifestStored.length);

		UploadRecord record = new UploadRecord(
				UploadRecord.CURRENT_FORMAT,
				worldId,
				snapshotId,
				42L,
				manifestDocument,
				packs,
				nextId,
				"CG1-" + manifestId,
				null,
				encrypted ? PayloadCipher.SCHEME : null,
				encrypted ? cipher.keyFingerprint() : null);
		return new Fixture(transport, Catalog.Snapshot.from(snapshot.manifest(), record));
	}

	private static void assertSamePackedFiles(Path original, Path restored) throws IOException {
		try (Stream<Path> files = Files.walk(original)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				String relative = original.relativize(file).toString().replace('\\', '/');
				if (relative.equals("session.lock") || relative.startsWith("logs/")) {
					continue;
				}
				Path copy = restored.resolve(relative);
				assertTrue(Files.isRegularFile(copy), "missing restored file " + relative);
				assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(copy), relative);
			}
		}
		assertFalse(Files.exists(restored.resolve("session.lock")));
		assertFalse(Files.exists(restored.resolve("logs")));
	}

	private static void assertDirectoryEmpty(Path directory) throws IOException {
		if (!Files.exists(directory)) {
			return;
		}
		try (Stream<Path> entries = Files.list(directory)) {
			assertEquals(List.of(), entries.toList(), "a failed restore must not leave a save behind");
		}
	}

	private Path createWorld(String name) throws IOException {
		Path world = tempDir.resolve("worlds").resolve(name);
		write(world.resolve("region/r.0.0.mca"), 400);
		write(world.resolve("entities/r.0.0.mca"), 200);
		write(world.resolve("data/raids.dat"), 64);
		write(world.resolve("level.dat"), 128);
		write(world.resolve("level.dat_old"), 128);
		write(world.resolve("session.lock"), 8);
		write(world.resolve("logs/latest.log"), 8);
		return world;
	}

	private static void write(Path file, int size) throws IOException {
		Files.createDirectories(file.getParent());
		byte[] bytes = new byte[size];
		for (int i = 0; i < size; i++) {
			bytes[i] = (byte) (file.hashCode() * 31 + i * 7);
		}
		Files.write(file, bytes);
	}

	private record Fixture(FakeTransport transport, Catalog.Snapshot catalog) {
	}

	private static final class FakeTransport implements TelegramTransport {
		private final Map<String, byte[]> files = new HashMap<>();

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
			throw new UnsupportedOperationException();
		}

		@Override
		public void pinChatMessage(String token, long chatId, long messageId) {
			throw new UnsupportedOperationException();
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
	}
}
