package dev.cloudgram.core.restore;

import dev.cloudgram.core.WorldPaths;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts one pack into a world directory.
 *
 * <p>Pack entry names come from a remote document, so they are treated as untrusted: absolute
 * paths, {@code ..} segments and duplicates are rejected before anything is written.
 */
public final class PackExtractor {
	private PackExtractor() {
	}

	public static Result extract(Path packFile, Path targetDirectory) throws IOException {
		if (packFile == null) {
			throw new IllegalArgumentException("packFile is required");
		}
		if (targetDirectory == null) {
			throw new IllegalArgumentException("targetDirectory is required");
		}
		Files.createDirectories(targetDirectory);

		int files = 0;
		long bytes = 0;
		Set<String> seen = new HashSet<>();
		try (InputStream raw = Files.newInputStream(packFile);
				ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
			for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
				if (entry.isDirectory()) {
					continue;
				}
				String name = WorldPaths.normalize(entry.getName());
				if (name.isEmpty() || !seen.add(name)) {
					throw new IOException("duplicate or empty pack entry: " + entry.getName());
				}
				Path target;
				try {
					target = WorldPaths.resolve(targetDirectory, name);
				} catch (IllegalArgumentException e) {
					throw new IOException("unsafe pack entry: " + entry.getName(), e);
				}
				Path parent = target.getParent();
				if (parent != null) {
					Files.createDirectories(parent);
				}
				try (OutputStream out = Files.newOutputStream(
						target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
					bytes += zip.transferTo(out);
				}
				files++;
			}
		}
		return new Result(files, bytes);
	}

	/** Counts for the manifest sanity check and the UI. */
	public record Result(int files, long bytes) {
		public Result {
			if (files < 0) {
				throw new IllegalArgumentException("files must not be negative");
			}
			if (bytes < 0) {
				throw new IllegalArgumentException("bytes must not be negative");
			}
		}
	}
}
