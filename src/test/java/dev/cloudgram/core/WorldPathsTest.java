package dev.cloudgram.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldPathsTest {
	@TempDir
	Path tempDir;

	@Test
	void normalisesSeparatorsAndDots() {
		assertEquals("a/b/c", WorldPaths.normalize("a\\b\\c"));
		assertEquals("a/b", WorldPaths.normalize("./a/b/"));
		assertEquals("a/b", WorldPaths.normalize("/a/b"));
		assertEquals("region/r.0.0.mca", WorldPaths.normalize("region\\r.0.0.mca"));
	}

	@Test
	void computesRelativePathsWithForwardSlashes() {
		Path root = tempDir.resolve("world");
		Path file = root.resolve("region/r.0.0.mca");

		assertEquals("region/r.0.0.mca", WorldPaths.relative(root, file));
	}

	@Test
	void rejectsFilesOutsideTheWorld() {
		assertThrows(IllegalArgumentException.class,
				() -> WorldPaths.relative(tempDir.resolve("world"), tempDir.resolve("other/file.dat")));
	}

	@Test
	void resolvesInsideTheWorld() {
		Path root = tempDir.resolve("world");

		assertEquals(root.resolve("data/a.dat").toAbsolutePath().normalize(),
				WorldPaths.resolve(root, "data/a.dat"));
	}

	@Test
	void refusesTraversal() {
		Path root = tempDir.resolve("world");
		assertThrows(IllegalArgumentException.class, () -> WorldPaths.resolve(root, "../escape.dat"));
		assertThrows(IllegalArgumentException.class, () -> WorldPaths.resolve(root, "data/../../escape.dat"));
		assertThrows(IllegalArgumentException.class, () -> WorldPaths.resolve(root, ""));
	}

	@Test
	void defaultExclusionsMatchTheSpec() {
		Exclusions exclusions = Exclusions.defaults();

		assertTrue(exclusions.isExcluded("session.lock"));
		assertTrue(exclusions.isExcluded("logs/latest.log"));
		assertTrue(exclusions.isExcluded("crash-reports/crash.txt"));
		assertTrue(exclusions.isExcluded("debug/profiling.txt"));
		assertTrue(exclusions.isExcluded(".cloudgram/tmp/part.bin"));
		assertTrue(exclusions.isExcluded("sub/dir/.DS_Store"));
		assertTrue(exclusions.isExcludedDirectory("logs"));

		assertFalse(exclusions.isExcluded("level.dat"));
		assertFalse(exclusions.isExcluded(".cloudgram/world.json"));
		assertFalse(exclusions.isExcluded("data/session.lock"), "only the root lock file is skipped");
		assertFalse(exclusions.isExcludedDirectory("data"));
	}

	@Test
	void hashesAreLowercaseHex() throws IOException {
		Path file = tempDir.resolve("f.bin");
		Files.write(file, new byte[] {1, 2, 3});

		assertEquals(64, Hex.encode(PackSplitter.sha256().digest(Files.readAllBytes(file))).length());
		assertTrue(Hex.isHex("0123456789abcdef", 16));
		assertFalse(Hex.isHex("0123456789ABCDEF", 16));
		assertFalse(Hex.isHex("0123", 16));
	}
}
