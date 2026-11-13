package dev.cloudgram.core.catalog;

import dev.cloudgram.core.Json;
import dev.cloudgram.core.SnapshotManifest;

import java.io.IOException;
import java.util.List;

/**
 * Everything a restore needs about one snapshot: the full manifest and every part's file id.
 *
 * <p>This document is uploaded once per snapshot and never changes, so the mutable pinned
 * {@link Catalog} only has to keep a small summary and this document's file id. That keeps the
 * catalog far below Telegram's download limit even for a chat with hundreds of snapshots.
 *
 * <p>It is encrypted like every other object, with AAD {@link dev.cloudgram.core.crypto.Aad#index}
 * bound to the world and snapshot id.
 */
public record SnapshotIndex(String format, SnapshotManifest manifest, List<Catalog.Pack> packs) {
	public static final String CURRENT_FORMAT = "cloudgram/index/1";

	public SnapshotIndex {
		if (format == null || !CURRENT_FORMAT.equals(format)) {
			throw new IllegalArgumentException("unsupported index format: " + format);
		}
		if (manifest == null) {
			throw new IllegalArgumentException("manifest is required");
		}
		packs = packs == null ? List.of() : List.copyOf(packs);
	}

	/** A stable, unique document name so the chat history stays readable. */
	public static String fileName(String snapshotId) {
		if (snapshotId == null || snapshotId.isBlank()) {
			throw new IllegalArgumentException("snapshotId is required");
		}
		return "cloudgram_index_" + snapshotId + ".json";
	}

	public String toJson() {
		return Json.gson().toJson(this);
	}

	public static SnapshotIndex fromJson(String json) throws IOException {
		SnapshotIndex index;
		try {
			index = Json.gson().fromJson(json, SnapshotIndex.class);
		} catch (RuntimeException e) {
			throw new IOException("snapshot index is not valid JSON", e);
		}
		if (index == null) {
			throw new IOException("snapshot index is empty");
		}
		if (!CURRENT_FORMAT.equals(index.format())) {
			throw new IOException("unsupported index format: " + index.format());
		}
		return index;
	}
}
