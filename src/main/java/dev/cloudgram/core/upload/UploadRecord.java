package dev.cloudgram.core.upload;

import dev.cloudgram.core.Json;
import dev.cloudgram.core.telegram.UploadedDocument;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * What Telegram accepted for one snapshot.
 *
 * <p>Written next to the manifest as {@code upload.json}. The manifest message id doubles as the
 * human-visible restore code ({@code CG1-<messageId>}); the per-part file ids are what a restore
 * needs to download bytes again without any chat history API.
 */
public record UploadRecord(
		String format,
		String worldId,
		String snapshotId,
		long chatId,
		UploadedDocument manifest,
		List<PackUpload> packs,
		long summaryMessageId,
		String restoreCode,
		String warning) {

	public static final String CURRENT_FORMAT = "cloudgram/upload/1";
	public static final String FILE_NAME = "upload.json";

	public UploadRecord {
		if (format == null || !CURRENT_FORMAT.equals(format)) {
			throw new IllegalArgumentException("unsupported upload format: " + format);
		}
		if (worldId == null || worldId.isBlank()) {
			throw new IllegalArgumentException("worldId is required");
		}
		if (snapshotId == null || snapshotId.isBlank()) {
			throw new IllegalArgumentException("snapshotId is required");
		}
		if (chatId <= 0) {
			throw new IllegalArgumentException("chatId must be positive");
		}
		if (manifest == null) {
			throw new IllegalArgumentException("manifest is required");
		}
		if (restoreCode == null || restoreCode.isBlank()) {
			throw new IllegalArgumentException("restoreCode is required");
		}
		if (summaryMessageId < 0) {
			throw new IllegalArgumentException("summaryMessageId must not be negative");
		}
		packs = packs == null ? List.of() : List.copyOf(packs);
	}

	public String toJson() {
		return Json.gson().toJson(this);
	}

	public static UploadRecord fromJson(String json) throws IOException {
		UploadRecord record;
		try {
			record = Json.gson().fromJson(json, UploadRecord.class);
		} catch (RuntimeException e) {
			throw new IOException("upload record is not valid JSON", e);
		}
		if (record == null) {
			throw new IOException("upload record is empty");
		}
		if (!CURRENT_FORMAT.equals(record.format())) {
			throw new IOException("unsupported upload record format: " + record.format());
		}
		return record;
	}

	/** Writes {@link #FILE_NAME} atomically into the snapshot directory. */
	public Path writeTo(Path snapshotDirectory) throws IOException {
		if (snapshotDirectory == null) {
			throw new IllegalArgumentException("snapshotDirectory is required");
		}
		Path file = snapshotDirectory.resolve(FILE_NAME);
		Path temp = snapshotDirectory.resolve(FILE_NAME + ".tmp");
		Files.writeString(temp, toJson() + "\n", StandardCharsets.UTF_8);
		try {
			Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		}
		return file;
	}

	/** One pack and the messages that hold its parts, in part-index order. */
	public record PackUpload(String id, String sha256, List<UploadedDocument> parts) {
		public PackUpload {
			if (id == null || id.isBlank()) {
				throw new IllegalArgumentException("pack id is required");
			}
			if (sha256 == null || sha256.isBlank()) {
				throw new IllegalArgumentException("pack sha256 is required");
			}
			parts = parts == null ? List.of() : List.copyOf(parts);
		}
	}
}
