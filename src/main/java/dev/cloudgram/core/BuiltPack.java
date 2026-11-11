package dev.cloudgram.core;

import java.nio.file.Path;
import java.util.List;

/** A pack that was written to disk, with its content hash and part descriptors. */
public record BuiltPack(String id, Path file, long size, String sha256, List<PackPart> parts) {
	public BuiltPack {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("pack id is required");
		}
		if (file == null) {
			throw new IllegalArgumentException("file is required");
		}
		if (size < 0) {
			throw new IllegalArgumentException("size must not be negative: " + size);
		}
		if (!Hex.isHex(sha256, 64)) {
			throw new IllegalArgumentException("sha256 must be 64 lower-case hex characters");
		}
		parts = List.copyOf(parts);
	}

	public String fileName() {
		return file.getFileName().toString();
	}
}
