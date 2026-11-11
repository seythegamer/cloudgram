package dev.cloudgram.core;

/** A regular file found while scanning a world, identified by its pack-relative path. */
public record ScannedFile(String path, long size) {
	public ScannedFile {
		if (path == null || path.isBlank()) {
			throw new IllegalArgumentException("path is required");
		}
		if (size < 0) {
			throw new IllegalArgumentException("size must not be negative: " + size);
		}
	}
}
