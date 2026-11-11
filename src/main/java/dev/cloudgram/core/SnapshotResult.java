package dev.cloudgram.core;

import java.nio.file.Path;
import java.util.List;

/** A snapshot that was fully written to the local vault. */
public record SnapshotResult(
		WorldIdentity identity,
		SnapshotManifest manifest,
		Path directory,
		List<BuiltPack> packs) {

	public SnapshotResult {
		packs = List.copyOf(packs);
	}

	public long totalPackBytes() {
		return packs.stream().mapToLong(BuiltPack::size).sum();
	}
}
