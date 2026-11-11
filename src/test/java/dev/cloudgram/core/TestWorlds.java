package dev.cloudgram.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Creates deterministic throw-away worlds for tests. */
final class TestWorlds {
	private TestWorlds() {
	}

	static byte[] content(String relativePath, int size) {
		byte[] data = new byte[size];
		int seed = relativePath.hashCode();
		for (int i = 0; i < size; i++) {
			data[i] = (byte) (seed * 31 + i * 7 + (i >>> 3));
		}
		return data;
	}

	static Path write(Path root, String relativePath, int size) throws IOException {
		Path file = root.resolve(relativePath);
		Files.createDirectories(file.getParent());
		Files.write(file, content(relativePath, size));
		return file;
	}

	static Path writeText(Path root, String relativePath, String text) throws IOException {
		Path file = root.resolve(relativePath);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text, StandardCharsets.UTF_8);
		return file;
	}
}
