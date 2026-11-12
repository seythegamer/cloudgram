package dev.cloudgram.core.restore;

import java.nio.file.Path;

/** A snapshot that was extracted into a new save directory. */
public record RestoreResult(Path directory, String worldName, int files, long bytes) {
	public RestoreResult {
		if (directory == null) {
			throw new IllegalArgumentException("directory is required");
		}
		if (worldName == null || worldName.isBlank()) {
			throw new IllegalArgumentException("worldName is required");
		}
		if (files < 0) {
			throw new IllegalArgumentException("files must not be negative");
		}
		if (bytes < 0) {
			throw new IllegalArgumentException("bytes must not be negative");
		}
	}
}
