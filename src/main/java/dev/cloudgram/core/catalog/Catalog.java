package dev.cloudgram.core.catalog;

import dev.cloudgram.core.Json;
import dev.cloudgram.core.SnapshotManifest;
import dev.cloudgram.core.telegram.UploadedDocument;
import dev.cloudgram.core.upload.UploadRecord;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The index of every snapshot uploaded to one chat, stored as a pinned encrypted document.
 *
 * <p>The Bot API has no history endpoint, so a fresh device cannot list the files in a chat. The
 * pinned catalog is the one object that can be read back ({@code getChat} returns it) and that
 * carries the file ids needed to download a snapshot's manifest and parts.
 *
 * <p>The catalog is mutable: each successful upload replaces its entry for the same world and
 * snapshot and pins a new catalog message. It always contains the full manifest, so a restore does
 * not have to download the manifest document to know the pack hashes.
 */
public record Catalog(String format, long updatedAt, List<Snapshot> snapshots) {
	public static final String CURRENT_FORMAT = "cloudgram/catalog/1";

	public Catalog {
		if (format == null || !CURRENT_FORMAT.equals(format)) {
			throw new IllegalArgumentException("unsupported catalog format: " + format);
		}
		if (updatedAt < 0) {
			throw new IllegalArgumentException("updatedAt must not be negative");
		}
		snapshots = snapshots == null ? List.of() : List.copyOf(snapshots);
	}

	public static Catalog empty() {
		return new Catalog(CURRENT_FORMAT, 0L, List.of());
	}

	public Catalog withSnapshot(Snapshot snapshot) {
		return withSnapshot(snapshot, System.currentTimeMillis());
	}

	/** Replaces the entry for the same world and snapshot, then records the update time. */
	public Catalog withSnapshot(Snapshot snapshot, long updatedAt) {
		if (snapshot == null) {
			throw new IllegalArgumentException("snapshot is required");
		}
		if (updatedAt < 0) {
			throw new IllegalArgumentException("updatedAt must not be negative");
		}
		List<Snapshot> next = new ArrayList<>(snapshots.size() + 1);
		for (Snapshot existing : snapshots) {
			if (!existing.sameSnapshot(snapshot)) {
				next.add(existing);
			}
		}
		next.add(snapshot);
		return new Catalog(CURRENT_FORMAT, updatedAt, next);
	}

	/** Looks up a snapshot by the code shown in the chat; blank codes simply find nothing. */
	public Snapshot findByRestoreCode(String restoreCode) {
		if (restoreCode == null || restoreCode.isBlank()) {
			return null;
		}
		String wanted = restoreCode.trim().toUpperCase(Locale.ROOT);
		for (Snapshot snapshot : snapshots) {
			if (snapshot.restoreCode().equalsIgnoreCase(wanted)) {
				return snapshot;
			}
		}
		return null;
	}

	public Snapshot find(String worldId, String snapshotId) {
		for (Snapshot snapshot : snapshots) {
			if (snapshot.worldId().equals(worldId) && snapshot.snapshotId().equals(snapshotId)) {
				return snapshot;
			}
		}
		return null;
	}

	public String toJson() {
		return Json.gson().toJson(this);
	}

	public static Catalog fromJson(String json) throws IOException {
		Catalog catalog;
		try {
			catalog = Json.gson().fromJson(json, Catalog.class);
		} catch (RuntimeException e) {
			throw new IOException("catalog is not valid JSON", e);
		}
		if (catalog == null) {
			throw new IOException("catalog is empty");
		}
		if (!CURRENT_FORMAT.equals(catalog.format())) {
			throw new IOException("unsupported catalog format: " + catalog.format());
		}
		return catalog;
	}

	/** One snapshot's remote objects. The manifest is embedded so restore is self-contained. */
	public record Snapshot(
			SnapshotManifest manifest,
			String restoreCode,
			long manifestMessageId,
			String manifestFileId,
			String encryption,
			String keyFingerprint,
			List<Pack> packs) {

		public Snapshot {
			if (manifest == null) {
				throw new IllegalArgumentException("manifest is required");
			}
			if (restoreCode == null || restoreCode.isBlank()) {
				throw new IllegalArgumentException("restoreCode is required");
			}
			if (manifestMessageId <= 0) {
				throw new IllegalArgumentException("manifestMessageId must be positive");
			}
			if (manifestFileId == null || manifestFileId.isBlank()) {
				throw new IllegalArgumentException("manifestFileId is required");
			}
			if (encryption != null && encryption.isBlank()) {
				throw new IllegalArgumentException("encryption must not be blank when present");
			}
			if (keyFingerprint != null && keyFingerprint.isBlank()) {
				throw new IllegalArgumentException("keyFingerprint must not be blank when present");
			}
			packs = packs == null ? List.of() : List.copyOf(packs);
		}

		public String worldId() {
			return manifest.world().id();
		}

		public String worldName() {
			return manifest.world().name();
		}

		public String snapshotId() {
			return manifest.snapshot().id();
		}

		public long createdAt() {
			return manifest.snapshot().createdAt();
		}

		public boolean isEncrypted() {
			return encryption != null;
		}

		public boolean sameSnapshot(Snapshot other) {
			return worldId().equals(other.worldId()) && snapshotId().equals(other.snapshotId());
		}

		/** Builds the catalog entry from the manifest and the uploader's record. */
		public static Snapshot from(SnapshotManifest manifest, UploadRecord record) {
			if (manifest == null) {
				throw new IllegalArgumentException("manifest is required");
			}
			if (record == null) {
				throw new IllegalArgumentException("record is required");
			}
			List<Pack> packs = new ArrayList<>(record.packs().size());
			for (UploadRecord.PackUpload uploaded : record.packs()) {
				List<Part> parts = new ArrayList<>(uploaded.parts().size());
				for (int index = 0; index < uploaded.parts().size(); index++) {
					UploadedDocument document = uploaded.parts().get(index);
					parts.add(new Part(index, document.messageId(), document.fileId()));
				}
				packs.add(new Pack(uploaded.id(), parts));
			}
			return new Snapshot(
					manifest,
					record.restoreCode(),
					record.manifest().messageId(),
					record.manifest().fileId(),
					record.encryption(),
					record.keyFingerprint(),
					packs);
		}
	}

	/** One pack and the messages that hold its parts, in manifest part order. */
	public record Pack(String id, List<Part> parts) {
		public Pack {
			if (id == null || id.isBlank()) {
				throw new IllegalArgumentException("pack id is required");
			}
			parts = parts == null ? List.of() : List.copyOf(parts);
		}
	}

	/** Where one encrypted part lives in the chat. */
	public record Part(int index, long messageId, String fileId) {
		public Part {
			if (index < 0) {
				throw new IllegalArgumentException("index must not be negative");
			}
			if (messageId <= 0) {
				throw new IllegalArgumentException("messageId must be positive");
			}
			if (fileId == null || fileId.isBlank()) {
				throw new IllegalArgumentException("fileId is required");
			}
		}
	}
}
