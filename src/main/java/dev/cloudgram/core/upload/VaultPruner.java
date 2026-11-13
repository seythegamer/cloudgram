package dev.cloudgram.core.upload;

import dev.cloudgram.core.ObjectNames;
import dev.cloudgram.core.SnapshotManifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Keeps the local vault small by dropping old snapshots that are already in Telegram.
 *
 * <p>The chat is append-only and stays the archive; the vault only needs to hold enough recent
 * snapshots to make an interrupted upload resumable and to let one device rebuild a lost pinned
 * catalog. Everything older is a full second copy of a world that the chat already has.
 *
 * <p>Only snapshots that carry both a readable {@code manifest.json} and an {@code upload.json} are
 * candidates, because that pair proves the bytes reached Telegram. A snapshot that is still being
 * packed, or that was packed but never uploaded, is never deleted no matter how old it is: that
 * would turn a temporary upload problem into a lost backup.
 */
public final class VaultPruner {
	/** Snapshots kept per world after a successful upload. */
	public static final int KEEP_PER_WORLD = 3;

	private VaultPruner() {
	}

	/**
	 * Prunes the world that owns {@code worldId}.
	 *
	 * @param vaultDir the root of the local vault ({@code <gameDir>/cloudgram/vault})
	 * @param worldId  the full world UUID, as recorded in the manifest
	 * @param keep     how many uploaded snapshots to keep, at least one
	 */
	public static Result pruneWorld(Path vaultDir, String worldId, int keep) throws IOException {
		if (vaultDir == null) {
			throw new IllegalArgumentException("vaultDir is required");
		}
		Path worldDir = vaultDir.resolve(ObjectNames.shortWorldId(worldId));
		return prune(worldDir, keep);
	}

	/**
	 * Keeps the newest {@code keep} uploaded snapshots in one world directory.
	 *
	 * @param worldDir a {@code vault/<worldId[0..8]>} directory
	 * @param keep     how many uploaded snapshots to keep, at least one
	 */
	public static Result prune(Path worldDir, int keep) throws IOException {
		if (worldDir == null) {
			throw new IllegalArgumentException("worldDir is required");
		}
		if (keep < 1) {
			throw new IllegalArgumentException("keep must be at least 1: " + keep);
		}
		if (!Files.isDirectory(worldDir)) {
			return Result.empty();
		}

		List<Candidate> uploaded = new ArrayList<>();
		List<Path> snapshotDirs;
		try (Stream<Path> children = Files.list(worldDir)) {
			snapshotDirs = children.filter(Files::isDirectory).toList();
		}
		for (Path snapshotDir : snapshotDirs) {
			Candidate candidate = read(snapshotDir);
			if (candidate != null) {
				uploaded.add(candidate);
			}
		}

		// Newest first; the id breaks ties because it starts with the UTC timestamp.
		uploaded.sort(Comparator.comparingLong(Candidate::createdAt)
				.thenComparing(Candidate::snapshotId)
				.reversed());

		List<Path> deleted = new ArrayList<>();
		List<Path> failed = new ArrayList<>();
		long bytesFreed = 0L;
		for (int i = keep; i < uploaded.size(); i++) {
			Path old = uploaded.get(i).directory();
			long size = sizeOf(old);
			try {
				deleteRecursively(old);
				deleted.add(old);
				bytesFreed += size;
			} catch (IOException e) {
				// A locked file is not a reason to abandon the other candidates.
				failed.add(old);
			}
		}
		return new Result(deleted, failed, bytesFreed);
	}

	/**
	 * Reads a snapshot directory when it is a safe deletion candidate.
	 *
	 * @return the candidate, or {@code null} for pending, incomplete or unreadable snapshots
	 */
	private static Candidate read(Path snapshotDir) {
		Path manifestFile = snapshotDir.resolve("manifest.json");
		Path uploadFile = snapshotDir.resolve(UploadRecord.FILE_NAME);
		if (!Files.isRegularFile(manifestFile) || !Files.isRegularFile(uploadFile)) {
			return null;
		}
		try {
			SnapshotManifest manifest = SnapshotManifest.fromJson(
					Files.readString(manifestFile, StandardCharsets.UTF_8));
			return new Candidate(
					manifest.snapshot().createdAt(), manifest.snapshot().id(), snapshotDir);
		} catch (IOException | RuntimeException e) {
			// Never delete something whose manifest we cannot understand.
			return null;
		}
	}

	private static long sizeOf(Path directory) {
		long[] total = {0L};
		try {
			Files.walkFileTree(directory, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					total[0] += attrs.size();
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException exc) {
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			return 0L;
		}
		return total[0];
	}

	private static void deleteRecursively(Path directory) throws IOException {
		Files.walkFileTree(directory, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				if (exc != null) {
					throw exc;
				}
				Files.delete(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/** What one prune run did. */
	public record Result(List<Path> deleted, List<Path> failed, long bytesFreed) {
		public Result {
			deleted = List.copyOf(deleted);
			failed = List.copyOf(failed);
			if (bytesFreed < 0) {
				throw new IllegalArgumentException("bytesFreed must not be negative: " + bytesFreed);
			}
		}

		public static Result empty() {
			return new Result(List.of(), List.of(), 0L);
		}

		public int deletedCount() {
			return deleted.size();
		}
	}

	private record Candidate(long createdAt, String snapshotId, Path directory) {
	}
}
