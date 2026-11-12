package dev.cloudgram.core.upload;

import dev.cloudgram.core.BuiltPack;
import dev.cloudgram.core.ObjectNames;
import dev.cloudgram.core.PackPart;
import dev.cloudgram.core.SnapshotResult;
import dev.cloudgram.core.crypto.Aad;
import dev.cloudgram.core.crypto.PayloadCipher;
import dev.cloudgram.core.telegram.TelegramException;
import dev.cloudgram.core.telegram.TelegramTransport;
import dev.cloudgram.core.telegram.UploadedDocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Uploads a completed local snapshot to the bound Telegram chat.
 *
 * <p>Order matters: every pack part first, then the manifest, then a human-readable summary. A
 * snapshot "exists" once its manifest is in the chat, so an interrupted upload leaves only orphan
 * parts that are harmless. The record written to {@code upload.json} captures Telegram's file ids
 * for the restore path.
 *
 * <p>Every object is encrypted with {@link PayloadCipher} before it is handed to the transport.
 * The cipher is required: there is deliberately no constructor that would upload plaintext.
 */
public final class SnapshotUploader {
	private static final int MAX_CAPTION_LENGTH = 1_000;
	private static final long MAX_RETRY_DELAY_MILLIS = 30_000;

	private final TelegramTransport transport;
	private final PayloadCipher cipher;
	private final UploadOptions options;
	private final Sleeper sleeper;

	public SnapshotUploader(TelegramTransport transport, PayloadCipher cipher) {
		this(transport, cipher, UploadOptions.defaults(), Thread::sleep);
	}

	public SnapshotUploader(
			TelegramTransport transport, PayloadCipher cipher, UploadOptions options, Sleeper sleeper) {
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

	/** Uploads {@code snapshot}, writing {@code upload.json} next to its manifest on success. */
	public UploadRecord upload(String token, long chatId, SnapshotResult snapshot) throws UploadException {
		if (token == null || token.isBlank()) {
			throw new IllegalArgumentException("token is required");
		}
		if (chatId <= 0) {
			throw new IllegalArgumentException("chatId must be positive");
		}
		if (snapshot == null) {
			throw new IllegalArgumentException("snapshot is required");
		}

		String worldId = snapshot.identity().id();
		String snapshotId = snapshot.manifest().snapshot().id();

		List<BuiltPack> packs = new ArrayList<>(snapshot.packs());
		packs.sort(Comparator.comparing(BuiltPack::id));

		List<UploadRecord.PackUpload> uploadedPacks = new ArrayList<>(packs.size());
		for (BuiltPack pack : packs) {
			List<UploadedDocument> uploadedParts = new ArrayList<>(pack.parts().size());
			for (PackPart part : pack.parts()) {
				byte[] bytes;
				try {
					bytes = PartReader.read(pack.file(), part);
				} catch (IOException e) {
					throw new UploadException("Could not read " + pack.file() + " part " + part.index(), e);
				}
				String fileName = ObjectNames.partFileName(worldId, pack.id(), pack.sha256(), part.index());
				byte[] encrypted = cipher.encrypt(
						bytes, Aad.part(worldId, snapshotId, pack.id(), part.index()));
				UploadedDocument document = uploadDocument(
						token, chatId, fileName, encrypted, partCaption(snapshot, pack, part));
				uploadedParts.add(document);
				pauseBetweenUploads();
			}
			uploadedPacks.add(new UploadRecord.PackUpload(pack.id(), pack.sha256(), uploadedParts));
		}

		byte[] manifestBytes = readManifest(snapshot.directory());
		String manifestName = ObjectNames.manifestFileName(worldId, snapshotId);
		byte[] encryptedManifest = cipher.encrypt(manifestBytes, Aad.manifest(worldId, snapshotId));
		UploadedDocument manifest = uploadDocument(
				token, chatId, manifestName, encryptedManifest, manifestCaption(snapshot));
		pauseBetweenUploads();

		String restoreCode = ObjectNames.restoreCode(manifest.messageId());
		String warning = null;
		long summaryMessageId;
		try {
			summaryMessageId = sendText(token, chatId, summaryText(snapshot, restoreCode));
		} catch (UploadException e) {
			if (Thread.currentThread().isInterrupted()) {
				throw e;
			}
			summaryMessageId = 0;
			warning = "The backup uploaded, but its summary message failed: " + e.getMessage();
		}

		UploadRecord record = new UploadRecord(
				UploadRecord.CURRENT_FORMAT,
				worldId,
				snapshotId,
				chatId,
				manifest,
				uploadedPacks,
				summaryMessageId,
				restoreCode,
				warning,
				PayloadCipher.SCHEME,
				cipher.keyFingerprint());
		try {
			record.writeTo(snapshot.directory());
		} catch (IOException e) {
			throw new UploadException("Snapshot uploaded, but its local upload record could not be written", e);
		}
		return record;
	}

	private UploadedDocument uploadDocument(
			String token, long chatId, String fileName, byte[] bytes, String caption) throws UploadException {
		for (int attempt = 1; ; attempt++) {
			try {
				return transport.sendDocument(token, chatId, fileName, bytes, caption);
			} catch (TelegramException e) {
				if (!isRetryable(e) || attempt >= options.maxAttempts()) {
					throw new UploadException("Could not upload " + fileName + ": " + e.getMessage(), e);
				}
				pauseBeforeRetry(e, attempt);
			}
		}
	}

	private long sendText(String token, long chatId, String text) throws UploadException {
		for (int attempt = 1; ; attempt++) {
			try {
				return transport.sendMessage(token, chatId, text);
			} catch (TelegramException e) {
				if (!isRetryable(e) || attempt >= options.maxAttempts()) {
					throw new UploadException("Could not send the summary message: " + e.getMessage(), e);
				}
				pauseBeforeRetry(e, attempt);
			}
		}
	}

	private static boolean isRetryable(TelegramException e) {
		return e.code() == 0 || e.code() == 429 || e.code() >= 500;
	}

	private void pauseBeforeRetry(TelegramException error, int attempt) throws UploadException {
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

	private void pauseBetweenUploads() throws UploadException {
		sleep(options.minIntervalMillis());
	}

	private void sleep(long millis) throws UploadException {
		if (millis <= 0) {
			return;
		}
		try {
			sleeper.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new UploadException("Upload interrupted", e);
		}
	}

	private static byte[] readManifest(Path snapshotDirectory) throws UploadException {
		Path file = snapshotDirectory.resolve("manifest.json");
		try {
			if (!Files.isRegularFile(file)) {
				throw new UploadException("Snapshot has no manifest: " + file);
			}
			return Files.readAllBytes(file);
		} catch (IOException e) {
			throw new UploadException("Could not read the manifest: " + file, e);
		}
	}

	private static String partCaption(SnapshotResult snapshot, BuiltPack pack, PackPart part) {
		String text = "Cloudgram backup " + snapshot.manifest().snapshot().id()
				+ "\n" + snapshot.manifest().world().name()
				+ "\n" + pack.id() + " part " + (part.index() + 1) + "/" + pack.parts().size()
				+ " (" + part.size() + " bytes)";
		return clampCaption(text);
	}

	private static String manifestCaption(SnapshotResult snapshot) {
		String text = "Cloudgram backup " + snapshot.manifest().snapshot().id()
				+ "\n" + snapshot.manifest().world().name()
				+ "\nManifest: " + snapshot.packs().size() + " packs, "
				+ snapshot.manifest().stats().files() + " files, "
				+ String.format(Locale.ROOT, "%.1f", snapshot.totalPackBytes() / 1048576.0) + " MiB"
				+ "\nEncrypted: " + PayloadCipher.SCHEME;
		return clampCaption(text);
	}

	private static String summaryText(SnapshotResult snapshot, String restoreCode) {
		String text = "Cloudgram backup complete."
				+ "\nWorld: " + snapshot.manifest().world().name()
				+ "\nSnapshot: " + snapshot.manifest().snapshot().id()
				+ "\n" + snapshot.manifest().stats().files() + " files, "
				+ String.format(Locale.ROOT, "%.1f", snapshot.totalPackBytes() / 1048576.0) + " MiB, "
				+ snapshot.packs().size() + " packs"
				+ "\nRestore code: " + restoreCode
				+ "\nEncryption: " + PayloadCipher.SCHEME
				+ "\nDevice: " + snapshot.manifest().snapshot().device();
		return clampCaption(text);
	}

	private static String clampCaption(String text) {
		return text.length() <= MAX_CAPTION_LENGTH ? text : text.substring(0, MAX_CAPTION_LENGTH - 3) + "...";
	}

	/** Injectable sleep so retry/pacing tests do not wait for real time. */
	@FunctionalInterface
	public interface Sleeper {
		void sleep(long millis) throws InterruptedException;
	}
}
