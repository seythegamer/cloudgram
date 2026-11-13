package dev.cloudgram.core.upload;

import dev.cloudgram.core.BuiltPack;
import dev.cloudgram.core.ObjectNames;
import dev.cloudgram.core.PackPart;
import dev.cloudgram.core.SnapshotManifest;
import dev.cloudgram.core.SnapshotResult;
import dev.cloudgram.core.WorldIdentity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Finds snapshots that were packed but never uploaded.
 *
 * <p>The vault is the durable half of a backup: a snapshot counts as complete once its
 * {@code manifest.json} exists, because that file is written last. The upload half runs on a
 * daemon thread, so quitting the game while it is still going abandons it. Snapshots without
 * {@code upload.json} are therefore retried on the next launch instead of being lost.
 *
 * <p>A snapshot is only offered when every pack file exists at the size the manifest recorded, so
 * an interrupted pack is never uploaded as if it were whole.
 */
public final class PendingUploads {
	private PendingUploads() {
	}

	/** Every complete, not-yet-uploaded snapshot in the vault, oldest first. */
	public static List<SnapshotResult> find(Path vaultDir) throws IOException {
		if (vaultDir == null) {
			throw new IllegalArgumentException("vaultDir is required");
		}
		if (!Files.isDirectory(vaultDir)) {
			return List.of();
		}

		List<SnapshotResult> pending = new ArrayList<>();
		List<Path> worldDirs;
		try (Stream<Path> worlds = Files.list(vaultDir)) {
			worldDirs = worlds.filter(Files::isDirectory).toList();
		}
		for (Path worldDir : worldDirs) {
			List<Path> snapshotDirs;
			try (Stream<Path> snapshots = Files.list(worldDir)) {
				snapshotDirs = snapshots.filter(Files::isDirectory).toList();
			}
			for (Path snapshotDir : snapshotDirs) {
				SnapshotResult result = read(snapshotDir);
				if (result != null) {
					pending.add(result);
				}
			}
		}

		pending.sort(Comparator.comparingLong(result -> result.manifest().snapshot().createdAt()));
		return pending;
	}

	/**
	 * Reads one vault snapshot when it is complete and still needs an upload.
	 *
	 * @return the snapshot, or {@code null} when it is incomplete, already uploaded or unreadable
	 */
	public static SnapshotResult read(Path snapshotDir) {
		if (snapshotDir == null) {
			throw new IllegalArgumentException("snapshotDir is required");
		}
		try {
			Path manifestFile = snapshotDir.resolve("manifest.json");
			if (!Files.isRegularFile(manifestFile)) {
				// Still being packed: the manifest is the completion marker.
				return null;
			}
			if (Files.exists(snapshotDir.resolve(UploadRecord.FILE_NAME))) {
				return null;
			}

			SnapshotManifest manifest = SnapshotManifest.fromJson(
					Files.readString(manifestFile, StandardCharsets.UTF_8));
			Path packsDir = snapshotDir.resolve("packs");
			List<BuiltPack> packs = new ArrayList<>(manifest.packs().size());
			for (SnapshotManifest.PackRef ref : manifest.packs()) {
				Path file = packsDir.resolve(ObjectNames.safePackId(ref.id()) + ".cgp");
				if (!Files.isRegularFile(file) || Files.size(file) != ref.size()) {
					return null;
				}
				List<PackPart> parts = new ArrayList<>(ref.parts().size());
				for (SnapshotManifest.PartRef part : ref.parts()) {
					parts.add(new PackPart(part.index(), part.offset(), part.size(), part.sha256()));
				}
				packs.add(new BuiltPack(ref.id(), file, ref.size(), ref.sha256(), parts));
			}

			// The uploader only needs the world id; the rest of the identity travels in the manifest.
			WorldIdentity identity = new WorldIdentity(
					WorldIdentity.CURRENT_FORMAT,
					manifest.world().id(),
					manifest.snapshot().createdAt(),
					WorldIdentity.ORIGIN_LOCAL);
			return new SnapshotResult(identity, manifest, snapshotDir, packs);
		} catch (IOException | RuntimeException e) {
			// A damaged directory stays on disk for inspection but must never block the queue.
			return null;
		}
	}
}
