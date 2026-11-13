package dev.cloudgram.core.restore;

import dev.cloudgram.core.Hex;
import dev.cloudgram.core.ObjectNames;
import dev.cloudgram.core.SnapshotManifest;
import dev.cloudgram.core.WorldIdentity;
import dev.cloudgram.core.catalog.Catalog;
import dev.cloudgram.core.catalog.CatalogService;
import dev.cloudgram.core.catalog.SnapshotIndex;
import dev.cloudgram.core.crypto.Aad;
import dev.cloudgram.core.crypto.CryptoException;
import dev.cloudgram.core.crypto.PayloadCipher;
import dev.cloudgram.core.telegram.RemoteFile;
import dev.cloudgram.core.telegram.TelegramException;
import dev.cloudgram.core.telegram.TelegramTransport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Downloads, decrypts, verifies and extracts one snapshot as a new save.
 *
 * <p>Restore never overwrites an existing world: it builds the world in a temporary directory next
 * to the other saves and only moves it into place after every pack hash and file count matches the
 * manifest. A failure leaves the {@code saves} directory untouched.
 */
public final class RestoreService {
	private static final long MAX_RETRY_DELAY_MILLIS = 30_000;

	private final TelegramTransport transport;
	private final PayloadCipher cipher;
	private final RestoreOptions options;
	private final Sleeper sleeper;

	public RestoreService(TelegramTransport transport, PayloadCipher cipher) {
		this(transport, cipher, RestoreOptions.defaults(), Thread::sleep);
	}

	public RestoreService(
			TelegramTransport transport, PayloadCipher cipher, RestoreOptions options, Sleeper sleeper) {
		if (transport == null) {
			throw new IllegalArgumentException("transport is required");
		}
		if (cipher == null) {
			throw new IllegalArgumentException("cipher is required");
		}
		if (options == null) {
			throw new IllegalArgumentException("options is required");
		}
		if (sleeper == null) {
			throw new IllegalArgumentException("sleeper is required");
		}
		this.transport = transport;
		this.cipher = cipher;
		this.options = options;
		this.sleeper = sleeper;
	}

	/** Reads the pinned catalog, which is how snapshots are discovered on a fresh device. */
	public Catalog fetchCatalog(String token, long chatId)
			throws TelegramException, CryptoException, IOException {
		return new CatalogService(transport, cipher).fetch(token, chatId).catalog();
	}

	/** Restores {@code snapshot} into a new directory below {@code savesDirectory}. */
	public RestoreResult restore(
			String token, Catalog.Snapshot snapshot, Path savesDirectory, Progress progress)
			throws RestoreException {
		if (token == null || token.isBlank()) {
			throw new IllegalArgumentException("token is required");
		}
		if (snapshot == null) {
			throw new IllegalArgumentException("snapshot is required");
		}
		if (savesDirectory == null) {
			throw new IllegalArgumentException("savesDirectory is required");
		}
		Progress status = progress == null ? Progress.NONE : progress;

		Resolved resolved = resolve(token, snapshot);
		List<SnapshotManifest.PackRef> manifestPacks = resolved.manifest().packs();
		Map<String, Catalog.Pack> objects = new HashMap<>();
		for (Catalog.Pack pack : resolved.packs()) {
			if (objects.put(pack.id(), pack) != null) {
				throw new RestoreException("The catalog lists pack " + pack.id() + " twice");
			}
		}
		if (objects.size() != manifestPacks.size()) {
			throw new RestoreException("The catalog and manifest disagree on how many packs exist");
		}

		Path work = null;
		try {
			Files.createDirectories(savesDirectory);
			work = Files.createTempDirectory(savesDirectory, ".cloudgram-restore-");
			Path worldDir = work.resolve("world");
			Path packsDir = work.resolve("packs");
			Files.createDirectories(worldDir);
			Files.createDirectories(packsDir);

			int totalParts = manifestPacks.stream().mapToInt(pack -> pack.parts().size()).sum();
			int doneParts = 0;
			int files = 0;
			long bytes = 0;

			for (SnapshotManifest.PackRef ref : manifestPacks) {
				Catalog.Pack packObjects = objects.get(ref.id());
				if (packObjects == null) {
					throw new RestoreException("The catalog is missing pack " + ref.id());
				}
				if (packObjects.parts().size() != ref.parts().size()) {
					throw new RestoreException("Pack " + ref.id() + " has "
							+ ref.parts().size() + " parts in the manifest but "
							+ packObjects.parts().size() + " in the catalog");
				}

				Path packFile = packsDir.resolve(ObjectNames.safePackId(ref.id()) + ".cgp");
				try (OutputStream out = Files.newOutputStream(
						packFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
					for (int index = 0; index < ref.parts().size(); index++) {
						SnapshotManifest.PartRef part = ref.parts().get(index);
						Catalog.Part remote = packObjects.parts().get(index);
						if (remote.index() != part.index()) {
							throw new RestoreException("Pack " + ref.id() + " part order is inconsistent");
						}
						out.write(downloadPart(token, snapshot, ref.id(), part, remote));
						doneParts++;
						status.update("Downloading " + ref.id(), doneParts, totalParts);
						pauseBetweenParts();
					}
				}

				if (Files.size(packFile) != ref.size()) {
					throw new RestoreException("Pack " + ref.id() + " has the wrong size after download");
				}
				if (!sha256(packFile).equals(ref.sha256())) {
					throw new RestoreException("Pack " + ref.id() + " failed its SHA-256 check");
				}
				PackExtractor.Result extracted = PackExtractor.extract(packFile, worldDir);
				files += extracted.files();
				bytes += extracted.bytes();
				Files.deleteIfExists(packFile);
			}

			if (resolved.manifest().stats().files() != files) {
				throw new RestoreException("Restored " + files + " files, but the manifest lists "
						+ resolved.manifest().stats().files());
			}
			ensureIdentity(worldDir, snapshot);

			Path target = SaveNames.unique(savesDirectory, snapshot.worldName());
			move(worldDir, target);
			status.update("Restored", totalParts, totalParts);
			return new RestoreResult(target, target.getFileName().toString(), files, bytes);
		} catch (IOException | CryptoException e) {
			throw new RestoreException("Restore failed: " + e.getMessage(), e);
		} finally {
			deleteRecursively(work);
		}
	}

	/**
	 * Loads the manifest and part locations for a snapshot.
	 *
	 * <p>Indexed entries point at a separate encrypted {@link SnapshotIndex}; older catalogs embed
	 * the same data directly, so both shapes restore through one code path.
	 */
	private Resolved resolve(String token, Catalog.Snapshot snapshot) throws RestoreException {
		if (!snapshot.isIndexed()) {
			return new Resolved(snapshot.manifest(), snapshot.packs());
		}

		Catalog.Index indexRef = snapshot.index();
		RemoteFile file = withRetries(
				"Could not locate the snapshot index",
				() -> transport.getFile(token, indexRef.fileId()));
		byte[] raw = withRetries(
				"Could not download the snapshot index",
				() -> transport.downloadFile(token, file.filePath()));
		if (!PayloadCipher.isEncrypted(raw)) {
			throw new RestoreException("The snapshot index is not encrypted");
		}

		byte[] plain;
		try {
			plain = cipher.decrypt(raw, Aad.index(snapshot.worldId(), snapshot.snapshotId()));
		} catch (CryptoException e) {
			throw new RestoreException(
					"The snapshot index cannot be decrypted with this recovery key", e);
		}
		SnapshotIndex index;
		try {
			index = SnapshotIndex.fromJson(new String(plain, StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new RestoreException("The snapshot index is unreadable: " + e.getMessage(), e);
		}
		if (!index.manifest().world().id().equals(snapshot.worldId())
				|| !index.manifest().snapshot().id().equals(snapshot.snapshotId())) {
			throw new RestoreException("The snapshot index belongs to another snapshot");
		}
		return new Resolved(index.manifest(), index.packs());
	}

	private byte[] downloadPart(
			String token, Catalog.Snapshot snapshot, String packId,
			SnapshotManifest.PartRef part, Catalog.Part remote) throws RestoreException, CryptoException {		RemoteFile file = withRetries(
				"Could not locate part " + part.index() + " of " + packId,
				() -> transport.getFile(token, remote.fileId()));
		byte[] raw = withRetries(
				"Could not download part " + part.index() + " of " + packId,
				() -> transport.downloadFile(token, file.filePath()));

		byte[] plain;
		if (snapshot.isEncrypted()) {
			if (!PayloadCipher.isEncrypted(raw)) {
				throw new RestoreException("Part " + part.index() + " of " + packId + " is not encrypted");
			}
			plain = cipher.decrypt(
					raw, Aad.part(snapshot.worldId(), snapshot.snapshotId(), packId, part.index()));
		} else {
			if (PayloadCipher.isEncrypted(raw)) {
				throw new RestoreException("Part " + part.index() + " of " + packId
						+ " is encrypted but the catalog says this snapshot is plaintext");
			}
			plain = raw;
		}

		if (plain.length != part.size()) {
			throw new RestoreException("Part " + part.index() + " of " + packId
					+ " has the wrong size after decryption");
		}
		if (!sha256(plain).equals(part.sha256())) {
			throw new RestoreException("Part " + part.index() + " of " + packId
					+ " failed its SHA-256 check");
		}
		return plain;
	}

	private void ensureIdentity(Path worldDir, Catalog.Snapshot snapshot) throws IOException, RestoreException {
		Optional<WorldIdentity> existing = WorldIdentity.read(worldDir);
		if (existing.isPresent()) {
			if (!existing.get().id().equals(snapshot.worldId())) {
				throw new RestoreException("The packed world identity does not match the catalog");
			}
			return;
		}
		new WorldIdentity(
				WorldIdentity.CURRENT_FORMAT,
				snapshot.worldId(),
				Instant.now().getEpochSecond(),
				WorldIdentity.ORIGIN_RESTORED)
				.write(worldDir);
	}

	private <T> T withRetries(String what, TelegramCall<T> call) throws RestoreException {
		for (int attempt = 1; ; attempt++) {
			try {
				return call.run();
			} catch (TelegramException e) {
				if (!isRetryable(e) || attempt >= options.maxAttempts()) {
					throw new RestoreException(what + ": " + e.getMessage(), e);
				}
				pauseBeforeRetry(e, attempt);
			}
		}
	}

	private static boolean isRetryable(TelegramException e) {
		return e.code() == 0 || e.code() == 429 || e.code() >= 500;
	}

	private void pauseBeforeRetry(TelegramException error, int attempt) throws RestoreException {
		if (error.isRateLimited()) {
			sleep((error.retryAfterSeconds() + 1L) * 1_000L);
			return;
		}
		long delay = options.retryBaseMillis();
		if (delay > 0) {
			delay = Math.min(MAX_RETRY_DELAY_MILLIS, delay * (1L << Math.min(20, attempt - 1)));
		}
		sleep(delay);
	}

	private void pauseBetweenParts() throws RestoreException {
		sleep(options.pauseBetweenPartsMillis());
	}

	private void sleep(long millis) throws RestoreException {
		if (millis <= 0) {
			return;
		}
		try {
			sleeper.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RestoreException("Restore interrupted", e);
		}
	}

	private static void move(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(source, target);
		}
	}

	private static String sha256(byte[] bytes) {
		return Hex.encode(digest().digest(bytes));
	}

	private static String sha256(Path file) throws IOException {
		MessageDigest digest = digest();
		try (InputStream in = Files.newInputStream(file)) {
			byte[] buffer = new byte[64 * 1024];
			for (int read; (read = in.read(buffer)) >= 0; ) {
				digest.update(buffer, 0, read);
			}
		}
		return Hex.encode(digest.digest());
	}

	private static MessageDigest digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}

	private static void deleteRecursively(Path root) {
		if (root == null || !Files.exists(root)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(root)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
					// Best effort: a leftover temp directory is safer than a failed restore.
				}
			});
		} catch (IOException ignored) {
			// Nothing else to do.
		}
	}

	@FunctionalInterface
	private interface TelegramCall<T> {
		T run() throws TelegramException;
	}

	/** Injectable sleep so tests never wait for real time. */
	@FunctionalInterface
	public interface Sleeper {
		void sleep(long millis) throws InterruptedException;
	}

	/** The manifest plus part locations, whether embedded in the catalog or in an index. */
	private record Resolved(SnapshotManifest manifest, List<Catalog.Pack> packs) {
	}

	/** Coarse progress for the restore screen. */
	@FunctionalInterface
	public interface Progress {
		Progress NONE = (stage, completed, total) -> {
		};

		void update(String stage, int completed, int total);
	}
}
