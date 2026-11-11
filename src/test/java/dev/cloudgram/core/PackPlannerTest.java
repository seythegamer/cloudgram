package dev.cloudgram.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackPlannerTest {
	@TempDir
	Path tempDir;

	@Test
	void groupsRegionFilesByCellAcrossDimensions() throws IOException {
		Path world = tempDir.resolve("world");
		TestWorlds.write(world, "region/r.0.0.mca", 32);
		TestWorlds.write(world, "entities/r.0.0.mca", 32);
		TestWorlds.write(world, "poi/r.0.0.mca", 32);
		TestWorlds.write(world, "DIM1/region/r.0.0.mca", 32);
		TestWorlds.write(world, "DIM-1/region/r.-1.2.mca", 32);
		TestWorlds.write(world, "dimensions/mymod/sky/region/r.3.4.mca", 32);
		TestWorlds.write(world, "level.dat", 64);
		TestWorlds.write(world, "level.dat_old", 64);
		TestWorlds.write(world, "icon.png", 16);
		TestWorlds.write(world, ".cloudgram/world.json", 48);
		TestWorlds.write(world, "data/raids.dat", 8);
		TestWorlds.write(world, "playerdata/abc.dat", 8);
		TestWorlds.write(world, "stats/abc.json", 8);
		TestWorlds.write(world, "advancements/abc.json", 8);
		TestWorlds.write(world, "datapacks/pack.zip", 8);
		TestWorlds.write(world, "custom.bin", 8);

		List<PackGroup> packs = new PackPlanner(PackOptions.defaults()).plan(world);
		Map<String, PackGroup> byId = packs.stream()
				.collect(Collectors.toMap(PackGroup::id, Function.identity()));

		assertEquals(
				List.of("DIM1/region/r.0.0.mca", "entities/r.0.0.mca", "poi/r.0.0.mca", "region/r.0.0.mca"),
				paths(byId.get("cell:0:0")));
		assertEquals(List.of("DIM-1/region/r.-1.2.mca"), paths(byId.get("cell:-1:2")));
		assertEquals(List.of("dimensions/mymod/sky/region/r.3.4.mca"), paths(byId.get("cell:3:4")));
		assertEquals(
				List.of(
						".cloudgram/world.json",
						"advancements/abc.json",
						"data/raids.dat",
						"datapacks/pack.zip",
						"icon.png",
						"level.dat",
						"level.dat_old",
						"playerdata/abc.dat",
						"stats/abc.json"),
				paths(byId.get("meta")));
		assertEquals(List.of("custom.bin"), paths(byId.get("misc")));
	}

	@Test
	void skipsLockSessionsAndLogs() throws IOException {
		Path world = tempDir.resolve("world");
		TestWorlds.write(world, "session.lock", 8);
		TestWorlds.write(world, "logs/latest.log", 8);
		TestWorlds.write(world, "logs/2026-11-11.log.gz", 8);
		TestWorlds.write(world, "crash-reports/crash.txt", 8);
		TestWorlds.write(world, "debug/profiling.txt", 8);
		TestWorlds.write(world, ".cloudgram/tmp/leftover.part", 8);
		TestWorlds.write(world, ".DS_Store", 8);
		TestWorlds.write(world, "data/kept.dat", 8);

		List<PackGroup> packs = new PackPlanner(PackOptions.defaults()).plan(world);
		List<String> allPaths = packs.stream()
				.flatMap(pack -> pack.files().stream())
				.map(ScannedFile::path)
				.toList();

		assertEquals(List.of("data/kept.dat"), allPaths);
	}

	@Test
	void splitsOversizedGroupAtFileBoundaries() throws IOException {
		Path world = tempDir.resolve("world");
		TestWorlds.write(world, "level.dat", 60);
		TestWorlds.write(world, "data/a.dat", 60);
		TestWorlds.write(world, "data/b.dat", 10);

		PackOptions options = new PackOptions(100, PackOptions.DEFAULT_PART_MAX_BYTES, 30_000, Exclusions.defaults());
		List<PackGroup> packs = new PackPlanner(options).plan(world);

		assertEquals(2, packs.size());
		assertEquals("meta", packs.get(0).id());
		assertEquals(List.of("data/a.dat", "data/b.dat"), paths(packs.get(0)));
		assertEquals(70, packs.get(0).totalBytes());
		assertEquals("meta~2", packs.get(1).id());
		assertEquals(List.of("level.dat"), paths(packs.get(1)));
	}

	@Test
	void keepsSingleFileLargerThanTargetInItsOwnPack() throws IOException {
		Path world = tempDir.resolve("world");
		TestWorlds.write(world, "data/huge.dat", 500);
		TestWorlds.write(world, "data/small.dat", 10);

		PackOptions options = new PackOptions(100, PackOptions.DEFAULT_PART_MAX_BYTES, 30_000, Exclusions.defaults());
		List<PackGroup> packs = new PackPlanner(options).plan(world);

		assertEquals(2, packs.size());
		assertEquals(List.of("data/huge.dat"), paths(packs.get(0)));
		assertEquals(List.of("data/small.dat"), paths(packs.get(1)));
	}

	@Test
	void splitsWhenEntryLimitIsReached() throws IOException {
		Path world = tempDir.resolve("world");
		for (int i = 0; i < 5; i++) {
			TestWorlds.write(world, "data/f" + i + ".dat", 4);
		}

		PackOptions options = new PackOptions(
				PackOptions.DEFAULT_TARGET_PACK_BYTES,
				PackOptions.DEFAULT_PART_MAX_BYTES,
				2,
				Exclusions.defaults());
		List<PackGroup> packs = new PackPlanner(options).plan(world);

		assertEquals(List.of("meta", "meta~2", "meta~3"), packs.stream().map(PackGroup::id).toList());
		assertEquals(List.of(2, 2, 1), packs.stream().map(pack -> pack.files().size()).toList());
	}

	@Test
	void returnsNoPacksForEmptyWorld() throws IOException {
		Path world = tempDir.resolve("world");
		Files.createDirectories(world);
		assertTrue(new PackPlanner(PackOptions.defaults()).plan(world).isEmpty());
	}

	@Test
	void assignsUnknownRegionLikeTreesToMisc() throws IOException {
		Path world = tempDir.resolve("world");
		TestWorlds.write(world, "data/region/r.0.0.mca", 8);
		TestWorlds.write(world, "region/not-a-region.mca", 8);
		TestWorlds.write(world, "region/r.x.0.mca", 8);

		List<PackGroup> packs = new PackPlanner(PackOptions.defaults()).plan(world);
		assertEquals(List.of("meta", "misc"), packs.stream().map(PackGroup::id).toList());
		assertEquals(1, packOf(packs, "meta").files().size());
		assertEquals(2, packOf(packs, "misc").files().size());
	}

	@Test
	void handlesThe26WorldLayout() throws IOException {
		Path world = tempDir.resolve("world");
		TestWorlds.write(world, "level.dat", 64);
		TestWorlds.write(world, "dimensions/minecraft/overworld/region/r.0.0.mca", 32);
		TestWorlds.write(world, "dimensions/minecraft/overworld/data/minecraft/world_border.dat", 16);
		TestWorlds.write(world, "dimensions/minecraft/the_nether/region/r.0.0.mca", 32);
		TestWorlds.write(world, "data/minecraft/game_rules.dat", 16);
		TestWorlds.write(world, "players/00000000-0000-0000-0000-000000000000.dat", 16);

		List<PackGroup> packs = new PackPlanner(PackOptions.defaults()).plan(world);
		Map<String, PackGroup> byId = packs.stream()
				.collect(Collectors.toMap(PackGroup::id, Function.identity()));

		assertEquals(
				List.of(
						"dimensions/minecraft/overworld/region/r.0.0.mca",
						"dimensions/minecraft/the_nether/region/r.0.0.mca"),
				paths(byId.get("cell:0:0")));
		assertEquals(
				List.of(
						"data/minecraft/game_rules.dat",
						"dimensions/minecraft/overworld/data/minecraft/world_border.dat",
						"level.dat",
						"players/00000000-0000-0000-0000-000000000000.dat"),
				paths(byId.get("meta")));
	}

	@Test
	void rejectsMissingWorldDirectory() {
		assertThrows(IOException.class, () -> new PackPlanner(PackOptions.defaults()).plan(tempDir.resolve("nope")));
	}

	private static PackGroup packOf(List<PackGroup> packs, String id) {
		return packs.stream().filter(pack -> pack.id().equals(id)).findFirst().orElseThrow();
	}

	private static List<String> paths(PackGroup pack) {
		return pack.files().stream().map(ScannedFile::path).toList();
	}
}
