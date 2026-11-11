package dev.cloudgram.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The document that makes a snapshot exist. It is uploaded after all of its packs, so a snapshot
 * without a manifest is simply an orphan that cleanup can collect.
 */
public record SnapshotManifest(
		String format,
		World world,
		Snapshot snapshot,
		List<PackRef> packs,
		Stats stats,
		String prev) {

	public static final String CURRENT_FORMAT = "cloudgram/snapshot/1";

	private static final DateTimeFormatter ID_TIME = DateTimeFormatter
			.ofPattern("yyyyMMdd'T'HHmmss'Z'")
			.withZone(ZoneOffset.UTC);

	public SnapshotManifest {
		packs = packs == null ? List.of() : List.copyOf(packs);
		if (world == null) {
			throw new IllegalArgumentException("world is required");
		}
		if (snapshot == null) {
			throw new IllegalArgumentException("snapshot is required");
		}
		if (stats == null) {
			throw new IllegalArgumentException("stats is required");
		}
	}

	public String toJson() {
		return Json.gson().toJson(this);
	}

	public static SnapshotManifest fromJson(String json) throws IOException {
		SnapshotManifest manifest;
		try {
			manifest = Json.gson().fromJson(json, SnapshotManifest.class);
		} catch (RuntimeException e) {
			throw new IOException("snapshot manifest is not valid JSON", e);
		}
		if (manifest == null) {
			throw new IOException("snapshot manifest is empty");
		}
		if (!CURRENT_FORMAT.equals(manifest.format())) {
			throw new IOException("unsupported snapshot manifest format: " + manifest.format());
		}
		return manifest;
	}

	public static String newSnapshotId(Instant createdAt, String suffix) {
		if (suffix == null || suffix.isBlank()) {
			throw new IllegalArgumentException("suffix is required");
		}
		return ID_TIME.format(createdAt) + "-" + suffix;
	}

	/** World metadata copied into the snapshot so a restore knows what it is opening. */
	public record World(String id, String name, String mcVersion, int dataVersion) {
	}

	/** When, where and with which mod version the snapshot was taken. */
	public record Snapshot(String id, long createdAt, String device, String mod) {
	}

	/** One uploaded pack. */
	public record PackRef(String id, String sha256, long size, List<PartRef> parts) {
		public PackRef {
			parts = parts == null ? List.of() : List.copyOf(parts);
		}
	}

	/** One byte range of a pack. */
	public record PartRef(int index, long offset, long size, String sha256) {
	}

	/** Counts for display and sanity checks on restore. */
	public record Stats(int files, long rawBytes, long packBytes) {
	}

	/** Serialises to UTF-8 bytes, as stored on Telegram. */
	public byte[] toJsonBytes() {
		return toJson().getBytes(StandardCharsets.UTF_8);
	}

	/** Parses from UTF-8 bytes, as downloaded from Telegram. */
	public static SnapshotManifest fromJsonBytes(byte[] json) throws IOException {
		return fromJson(new String(json, StandardCharsets.UTF_8));
	}
}
