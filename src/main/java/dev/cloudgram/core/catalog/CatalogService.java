package dev.cloudgram.core.catalog;

import dev.cloudgram.core.SnapshotManifest;
import dev.cloudgram.core.crypto.Aad;
import dev.cloudgram.core.crypto.CryptoException;
import dev.cloudgram.core.crypto.PayloadCipher;
import dev.cloudgram.core.telegram.ChatInfo;
import dev.cloudgram.core.telegram.PinnedMessage;
import dev.cloudgram.core.telegram.RemoteDocument;
import dev.cloudgram.core.telegram.RemoteFile;
import dev.cloudgram.core.telegram.TelegramException;
import dev.cloudgram.core.telegram.TelegramTransport;
import dev.cloudgram.core.telegram.UploadedDocument;
import dev.cloudgram.core.upload.UploadRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Reads and writes the pinned catalog.
 *
 * <p>Publishing is deliberately separate from uploading a snapshot: the snapshot is complete once
 * its manifest is in the chat, so a catalog failure never invalidates a backup. The next upload
 * will repair the catalog.
 */
public final class CatalogService {
	public static final String FILE_NAME = "cloudgram_catalog.json";

	private static final String CAPTION = "Cloudgram snapshot catalog";
	private static final String INDEX_CAPTION = "Cloudgram snapshot index";

	private final TelegramTransport transport;
	private final PayloadCipher cipher;

	public CatalogService(TelegramTransport transport, PayloadCipher cipher) {
		if (transport == null) {
			throw new IllegalArgumentException("transport is required");
		}
		if (cipher == null) {
			throw new IllegalArgumentException("cipher is required");
		}
		this.transport = transport;
		this.cipher = cipher;
	}

	/**
	 * Loads the catalog from the chat's pinned message.
	 *
	 * <p>A pinned text message (the setup greeting) or no pinned message simply means "no catalog
	 * yet". A pinned document that is not a Cloudgram catalog is ignored so Cloudgram never
	 * overwrites an unrelated pin.
	 */
	public Fetched fetch(String token, long chatId) throws TelegramException, CryptoException, IOException {
		ChatInfo chat = transport.getChat(token, chatId);
		PinnedMessage pinned = chat.pinnedMessage();
		if (pinned == null || pinned.document() == null) {
			return new Fetched(Catalog.empty(), 0L);
		}
		RemoteDocument document = pinned.document();
		if (document.fileName() == null || !document.fileName().startsWith(FILE_NAME)) {
			return new Fetched(Catalog.empty(), 0L);
		}

		RemoteFile file = transport.getFile(token, document.fileId());
		byte[] bytes = transport.downloadFile(token, file.filePath());
		if (!PayloadCipher.isEncrypted(bytes)) {
			throw new CryptoException("The pinned Cloudgram catalog is not encrypted");
		}
		byte[] plain = cipher.decrypt(bytes, Aad.catalog(chatId));
		return new Fetched(Catalog.fromJson(new String(plain, StandardCharsets.UTF_8)), pinned.messageId());
	}

	/** Encrypts and uploads {@code catalog}, pins it, and deletes the superseded message. */
	public Fetched publish(String token, long chatId, Catalog catalog, long previousMessageId)
			throws TelegramException {
		if (catalog == null) {
			throw new IllegalArgumentException("catalog is required");
		}
		byte[] plain = catalog.toJson().getBytes(StandardCharsets.UTF_8);
		byte[] encrypted = cipher.encrypt(plain, Aad.catalog(chatId));
		UploadedDocument document = transport.sendDocument(token, chatId, FILE_NAME, encrypted, CAPTION);
		transport.pinChatMessage(token, chatId, document.messageId());

		long old = previousMessageId;
		if (old > 0 && old != document.messageId()) {
			try {
				transport.deleteMessage(token, chatId, old);
			} catch (TelegramException ignored) {
				// A stale catalog message is harmless; the pin already points at the new one.
			}
		}
		return new Fetched(catalog, document.messageId());
	}

	/** Fetches the catalog, adds or replaces this snapshot, and publishes the result. */
	public Fetched publishSnapshot(
			String token, long chatId, SnapshotManifest manifest, UploadRecord record)
			throws TelegramException, CryptoException, IOException {
		if (manifest == null) {
			throw new IllegalArgumentException("manifest is required");
		}
		if (record == null) {
			throw new IllegalArgumentException("record is required");
		}
		Fetched current = fetch(token, chatId);
		Catalog.Snapshot entry = publishIndex(token, chatId, manifest, record);
		Catalog next = current.catalog().withSnapshot(entry);
		return publish(token, chatId, next, current.messageId());
	}

	/**
	 * Uploads the immutable per-snapshot index and returns the small catalog entry pointing at it.
	 *
	 * <p>Doing this before the catalog publish means a failed index upload never produces an entry
	 * that a restore cannot follow.
	 */
	private Catalog.Snapshot publishIndex(
			String token, long chatId, SnapshotManifest manifest, UploadRecord record)
			throws TelegramException {
		String snapshotId = manifest.snapshot().id();
		SnapshotIndex index = new SnapshotIndex(
				SnapshotIndex.CURRENT_FORMAT, manifest, Catalog.Snapshot.packsFrom(record));
		byte[] plain = index.toJson().getBytes(StandardCharsets.UTF_8);
		byte[] encrypted = cipher.encrypt(plain, Aad.index(manifest.world().id(), snapshotId));
		UploadedDocument document = transport.sendDocument(
				token, chatId, SnapshotIndex.fileName(snapshotId), encrypted, INDEX_CAPTION);

		return Catalog.Snapshot.indexed(
				Catalog.Summary.from(manifest),
				record.restoreCode(),
				record.manifest().messageId(),
				record.manifest().fileId(),
				record.encryption(),
				record.keyFingerprint(),
				new Catalog.Index(document.messageId(), document.fileId()));
	}

	/** The catalog plus the message that currently holds it ({@code 0} when none). */
	public record Fetched(Catalog catalog, long messageId) {
		public Fetched {
			if (catalog == null) {
				throw new IllegalArgumentException("catalog is required");
			}
			if (messageId < 0) {
				throw new IllegalArgumentException("messageId must not be negative");
			}
		}
	}
}
