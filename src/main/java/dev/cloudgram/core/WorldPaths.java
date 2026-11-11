package dev.cloudgram.core;

import java.nio.file.Path;

/**
 * Helpers for the forward-slash relative paths that are used inside packs.
 *
 * <p>Every path in this package is relative to the world directory and uses {@code /} as the
 * separator, regardless of the host operating system. That is what makes pack bytes identical
 * on Windows, Linux and macOS.
 */
public final class WorldPaths {
	public static final String SEPARATOR = "/";

	private WorldPaths() {
	}

	/** Normalises a path to {@code a/b/c} form: no leading or trailing slashes, no {@code ./}. */
	public static String normalize(String path) {
		String normalized = path.replace('\\', '/');
		while (normalized.startsWith("./")) {
			normalized = normalized.substring(2);
		}
		while (normalized.startsWith("/")) {
			normalized = normalized.substring(1);
		}
		while (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		return normalized;
	}

	/** Returns {@code file} as a normalised path relative to {@code root}. */
	public static String relative(Path root, Path file) {
		Path normalizedRoot = root.toAbsolutePath().normalize();
		Path normalizedFile = file.toAbsolutePath().normalize();
		if (!normalizedFile.startsWith(normalizedRoot)) {
			throw new IllegalArgumentException("file is outside the world directory: " + file);
		}
		return normalize(normalizedRoot.relativize(normalizedFile).toString());
	}

	/** Resolves a pack entry path against the world root, rejecting anything that escapes it. */
	public static Path resolve(Path root, String relativePath) {
		String path = normalize(relativePath);
		if (path.isEmpty()) {
			throw new IllegalArgumentException("empty relative path");
		}
		for (String segment : path.split(SEPARATOR)) {
			if (segment.equals("..")) {
				throw new IllegalArgumentException("path escapes the world directory: " + relativePath);
			}
		}
		Path normalizedRoot = root.toAbsolutePath().normalize();
		Path resolved = normalizedRoot.resolve(path).normalize();
		if (!resolved.startsWith(normalizedRoot)) {
			throw new IllegalArgumentException("path escapes the world directory: " + relativePath);
		}
		return resolved;
	}
}
