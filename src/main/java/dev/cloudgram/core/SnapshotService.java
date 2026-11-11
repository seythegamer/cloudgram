package dev.cloudgram.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Builds a snapshot of a closed world and stages it in a local vault.
 *
 * <p>This is the whole offline half of a backup: forced save is done by the caller, then the
 * world directory is only read. Packs are written to
 * {@code <vault>/<worldId[0..8]>/<snapshotId>/packs/} and the manifest last, so an interrupted
 * run never looks like a complete snapshot.
 */
public final class SnapshotService {
	private final Path vaultDir;
	private final PackOptions options;
	private final PackPlanner planner;
	private final PackWriter writer;

	public SnapshotService(Path vaultDir, PackOptions options) {
		if (vaultDir == null) {
			throw new IllegalArgumentException("vaultDir is required");
		}
		if (options == null) {
			throw new IllegalArgumentException("options is required");
		}
		this.vaultDir = vaultDir.toAbsolutePath().normalize();
		this.options = options;
		this.planner = new PackPlanner(options);
		this.writer = new PackWriter(options);
	}

	public Path vaultDir() {
		return vaultDir;
	}

	public PackOptions options() {
		return options;
	}

	/** Captures a snapshot, using the current time and a random id suffix. */
	public SnapshotResult capture(Path worldDir, SnapshotRequest request) throws IOException {
		return capture(worldDir, request, Instant.now(), randomSuffix());
	}

	SnapshotResult capture(Path worldDir, SnapshotRequest request, Instant createdAt, String suffix)
			throws IOException {
		Path root = worldDir.toAbsolutePath().normalize();
		if (!Files.isDirectory(root)) {
			throw new IOException("world directory does not exist: " + root);
		}
		if (!Files.isRegularFile(root.resolve("level.dat"))) {
			throw new IOException("not a Minecraft world (no level.dat): " + root);
		}

		WorldIdentity identity = WorldIdentity.loadOrCreate(root);
		List<PackGroup> groups = planner.plan(root);
		if (groups.isEmpty()) {
			throw new IOException("world directory contains no packable files: " + root);
		}

		String snapshotId = SnapshotManifest.newSnapshotId(createdAt, suffix);
		Path snapshotDir = vaultDir.resolve(shortId(identity)).resolve(snapshotId);
		Path packsDir = snapshotDir.resolve("packs");

		try {
			Files.createDirectories(packsDir);

			List<SnapshotManifest.PackRef> packRefs = new ArrayList<>(groups.size());
			List<BuiltPack> builtPacks = new ArrayList<>(groups.size());
			int fileCount = 0;
			long rawBytes = 0;
			long packBytes = 0;

			for (PackGroup group : groups) {
				String fileName = ObjectNames.safePackId(group.id()) + ".cgp";
				BuiltPack pack = writer.write(root, group, packsDir.resolve(fileName));
				builtPacks.add(pack);
				packRefs.add(toRef(pack));

				fileCount += group.files().size();
				rawBytes += group.totalBytes();
				packBytes += pack.size();
			}

			SnapshotManifest manifest = new SnapshotManifest(
					SnapshotManifest.CURRENT_FORMAT,
					new SnapshotManifest.World(identity.id(), request.worldName(), request.mcVersion(), request.dataVersion()),
					new SnapshotManifest.Snapshot(snapshotId, createdAt.getEpochSecond(), request.device(), request.mod()),
					packRefs,
					new SnapshotManifest.Stats(fileCount, rawBytes, packBytes),
					null);

			writeManifest(snapshotDir, manifest);
			return new SnapshotResult(identity, manifest, snapshotDir, builtPacks);
		} catch (IOException | RuntimeException e) {
			deleteRecursively(snapshotDir);
			throw e;
		}
	}

	private static SnapshotManifest.PackRef toRef(BuiltPack pack) {
		List<SnapshotManifest.PartRef> parts = pack.parts().stream()
				.map(part -> new SnapshotManifest.PartRef(part.index(), part.offset(), part.size(), part.sha256()))
				.toList();
		return new SnapshotManifest.PackRef(pack.id(), pack.sha256(), pack.size(), parts);
	}

	private static void writeManifest(Path snapshotDir, SnapshotManifest manifest) throws IOException {
		Path file = snapshotDir.resolve("manifest.json");
		Path temp = snapshotDir.resolve("manifest.json.tmp");
		Files.writeString(temp, manifest.toJson() + "\n", StandardCharsets.UTF_8);
		try {
			Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	static String shortId(WorldIdentity identity) {
		return identity.id().replace("-", "").substring(0, 8);
	}

	private static String randomSuffix() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 6);
	}

	private static void deleteRecursively(Path directory) {
		if (!Files.exists(directory)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(directory)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
					// best effort: a leftover staging directory is harmless
				}
			});
		} catch (IOException ignored) {
			// best effort
		}
	}
}
