package dev.cloudgram.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotManifestTest {
	private static SnapshotManifest sample() {
		return new SnapshotManifest(
				SnapshotManifest.CURRENT_FORMAT,
				new SnapshotManifest.World("0f3c9a2e-7b1d-4f5a-9c3e-2a1b4c5d6e7f", "Survival", "26.3", 4567),
				new SnapshotManifest.Snapshot("20261111T230000Z-a1b2c3", 1762898400L, "DESKTOP-SEY", "0.1.0"),
				List.of(new SnapshotManifest.PackRef(
						"cell:0:0",
						"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
						8123456,
						List.of(new SnapshotManifest.PartRef(
								0,
								0,
								8123456,
								"9f2c1e4d5a6b7c8d9e0f1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f")))),
				new SnapshotManifest.Stats(8123, 12884901888L, 9663676416L),
				"20261110T221000Z-9f8e");
	}

	@Test
	void roundTripsThroughJson() throws IOException {
		SnapshotManifest original = sample();
		SnapshotManifest parsed = SnapshotManifest.fromJson(original.toJson());

		assertEquals(original, parsed);
		assertEquals(original, SnapshotManifest.fromJsonBytes(original.toJsonBytes()));
	}

	@Test
	void writesStableFieldNames() {
		String json = sample().toJson();

		assertTrue(json.contains("\"format\": \"cloudgram/snapshot/1\""));
		assertTrue(json.contains("\"packBytes\""));
		assertTrue(json.contains("\"prev\""));
		assertTrue(json.contains("\"parts\""));
	}

	@Test
	void omitsNullPreviousSnapshot() {
		SnapshotManifest first = new SnapshotManifest(
				SnapshotManifest.CURRENT_FORMAT,
				sample().world(),
				sample().snapshot(),
				sample().packs(),
				sample().stats(),
				null);

		assertFalse(first.toJson().contains("\"prev\""));
		assertNull(first.prev());
	}

	@Test
	void rejectsUnknownFormat() {
		String json = sample().toJson().replace(SnapshotManifest.CURRENT_FORMAT, "cloudgram/snapshot/999");
		assertThrows(IOException.class, () -> SnapshotManifest.fromJson(json));
	}

	@Test
	void rejectsGarbage() {
		assertThrows(IOException.class, () -> SnapshotManifest.fromJson("not json"));
		assertThrows(IOException.class, () -> SnapshotManifest.fromJson("{}"));
		assertThrows(IOException.class, () -> SnapshotManifest.fromJson("null"));
	}

	@Test
	void buildsUtcSnapshotIds() {
		String id = SnapshotManifest.newSnapshotId(Instant.parse("2026-11-11T23:00:00Z"), "a1b2c3");

		assertEquals("20261111T230000Z-a1b2c3", id);
		assertThrows(IllegalArgumentException.class,
				() -> SnapshotManifest.newSnapshotId(Instant.parse("2026-11-11T23:00:00Z"), " "));
	}
}
