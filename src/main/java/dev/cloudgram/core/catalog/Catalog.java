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

	/**
	 * One snapshot's remote objects.
	 *
	 * <p>Two shapes are accepted. An <em>indexed</em> entry carries a small {@link Summary} plus the
	 * file id of an encrypted {@link SnapshotIndex} document that holds the manifest and every
	 * part's file id, so the pinned catalog stays small no matter how many snapshots the chat holds.
	 * A <em>legacy</em> entry embeds the manifest and parts directly; catalogs written before the
	 * index existed remain readable.
	 */
	public record Snapshot(
			SnapshotManifest manifest,
			String restoreCode,
			long manifestMessageId,
			String manifestFileId,
			String encryption,
			String keyFingerprint,
			List<Pack> packs,
			Summary summary,
			Index index) {

		public Snapshot {
			if (manifest == null && summary == null) {
				throw new IllegalArgumentException("manifest or summary is required");
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
			return summary != null ? summary.worldId() : manifest.world().id();
		}

		public String worldName() {
			return summary != null ? summary.worldName() : manifest.world().name();
		}

		public String snapshotId() {
			return summary != null ? summary.snapshotId() : manifest.snapshot().id();
		}

		public long createdAt() {
			return summary != null ? summary.createdAt() : manifest.snapshot().createdAt();
		}

		public int files() {
			return summary != null ? summary.files() : manifest.stats().files();
		}

		public long packBytes() {
			return summary != null ? summary.packBytes() : manifest.stats().packBytes();
		}

		public boolean isEncrypted() {
			return encryption != null;
		}

		/** True when the manifest and the part file ids live in a separate index document. */
		public boolean isIndexed() {
			return index != null;
		}

		public boolean sameSnapshot(Snapshot other) {
			return worldId().equals(other.worldId()) && snapshotId().equals(other.snapshotId());
		}

		/** Builds a legacy entry that embeds the manifest and the part locations. */
		public static Snapshot from(SnapshotManifest manifest, UploadRecord record) {
			if (manifest == null) {
				throw new IllegalArgumentException("manifest is required");
			}
			if (record == null) {
				throw new IllegalArgumentException("record is required");
			}
			return new Snapshot(
					manifest,
					record.restoreCode(),
					record.manifest().messageId(),
					record.manifest().fileId(),
					record.encryption(),
					record.keyFingerprint(),
					packsFrom(record),
					null,
					null);
		}

		/** Builds an entry that points at a separately uploaded {@link SnapshotIndex}. */
		public static Snapshot indexed(
				Summary summary,
				String restoreCode,
				long manifestMessageId,
				String manifestFileId,
				String encryption,
				String keyFingerprint,
				Index index) {
			if (summary == null) {
				throw new IllegalArgumentException("summary is required");
			}
			if (index == null) {
				throw new IllegalArgumentException("index is required");
			}
			return new Snapshot(
					null, restoreCode, manifestMessageId, manifestFileId, encryption, keyFingerprint,
					List.of(), summary, index);
		}

		/** The part locations recorded by the uploader, in manifest part order. */
		public static List<Pack> packsFrom(UploadRecord record) {
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
			return packs;
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

	/**
	 * The few fields the restore list needs, kept in the catalog so it never has to download an
	 * index just to show a snapshot.
	 */
	public record Summary(
			String worldId,
			String snapshotId,
			String worldName,
			long createdAt,
			int files,
			long packBytes) {

		public Summary {
			if (worldId == null || worldId.isBlank()) {
				throw new IllegalArgumentException("worldId is required");
			}
			if (snapshotId == null || snapshotId.isBlank()) {
				throw new IllegalArgumentException("snapshotId is required");
			}
			if (worldName == null || worldName.isBlank()) {
				throw new IllegalArgumentException("worldName is required");
			}
			if (createdAt < 0) {
				throw new IllegalArgumentException("createdAt must not be negative");
			}
			if (files < 0) {
				throw new IllegalArgumentException("files must not be negative");
			}
			if (packBytes < 0) {
				throw new IllegalArgumentException("packBytes must not be negative");
			}
		}

		public static Summary from(SnapshotManifest manifest) {
			if (manifest == null) {
				throw new IllegalArgumentException("manifest is required");
			}
			return new Summary(
					manifest.world().id(),
					manifest.snapshot().id(),
					manifest.world().name(),
					manifest.snapshot().createdAt(),
					manifest.stats().files(),
					manifest.stats().packBytes());
		}
	}

	/** The encrypted {@link SnapshotIndex} document that belongs to one snapshot. */
	public record Index(long messageId, String fileId) {
		public Index {
			if (messageId <= 0) {
				throw new IllegalArgumentException("messageId must be positive");
			}
			if (fileId == null || fileId.isBlank()) {
				throw new IllegalArgumentException("fileId is required");
			}
		}
	}
}
