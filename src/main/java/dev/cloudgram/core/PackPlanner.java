package dev.cloudgram.core;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Scans a world directory and groups its files into packs.
 *
 * <p>Region, entities and poi files are grouped per region coordinate across all dimensions, so
 * playing in one area only rewrites a handful of packs. Everything else falls into {@code meta}
 * (things needed to open the world) or {@code misc} (unknown leftovers).
 */
public final class PackPlanner {
	private static final Set<String> REGION_DIRECTORIES = Set.of("region", "entities", "poi");
	private static final Set<String> ROOT_META_FILES = Set.of("level.dat", "level.dat_old", "icon.png");
	private static final List<String> META_DIRECTORIES = List.of(
			".cloudgram/",
			"data/",
			"playerdata/",
			"stats/",
			"advancements/",
			"datapacks/");

	private final PackOptions options;

	public PackPlanner(PackOptions options) {
		if (options == null) {
			throw new IllegalArgumentException("options is required");
		}
		this.options = options;
	}

	/** Returns packs in a deterministic order; an empty world yields an empty list. */
	public List<PackGroup> plan(Path worldRoot) throws IOException {
		Path root = worldRoot.toAbsolutePath().normalize();
		if (!Files.isDirectory(root)) {
			throw new IOException("world directory does not exist: " + root);
		}

		TreeMap<String, List<ScannedFile>> groups = new TreeMap<>();

		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				if (!dir.equals(root) && options.exclusions().isExcludedDirectory(WorldPaths.relative(root, dir))) {
					return FileVisitResult.SKIP_SUBTREE;
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				if (!attrs.isRegularFile()) {
					return FileVisitResult.CONTINUE;
				}
				String relative = WorldPaths.relative(root, file);
				if (options.exclusions().isExcluded(relative)) {
					return FileVisitResult.CONTINUE;
				}
				groups.computeIfAbsent(groupKey(relative), key -> new ArrayList<>())
						.add(new ScannedFile(relative, attrs.size()));
				return FileVisitResult.CONTINUE;
			}
		});

		List<PackGroup> packs = new ArrayList<>();
		for (var entry : groups.entrySet()) {
			List<ScannedFile> files = new ArrayList<>(entry.getValue());
			files.sort(Comparator.comparing(ScannedFile::path));
			packs.addAll(split(entry.getKey(), files));
		}
		return List.copyOf(packs);
	}

	/** Assigns a group key to a pack-relative path. Visible for tests. */
	static String groupKey(String relativePath) {
		String path = WorldPaths.normalize(relativePath);
		String[] parts = path.split(WorldPaths.SEPARATOR);

		for (int i = 0; i < parts.length - 1; i++) {
			if (!REGION_DIRECTORIES.contains(parts[i]) || !isDimensionPrefix(parts, i)) {
				continue;
			}
			int[] coordinates = parseRegionFile(parts[i + 1]);
			if (coordinates != null) {
				return "cell:" + coordinates[0] + ":" + coordinates[1];
			}
		}

		for (String prefix : META_DIRECTORIES) {
			if (path.startsWith(prefix)) {
				return "meta";
			}
		}
		if (ROOT_META_FILES.contains(path)) {
			return "meta";
		}
		return "misc";
	}

	/**
	 * {@code true} when {@code parts[0..index)} describe a dimension directory: nothing (the
	 * overworld), {@code DIM-1}, {@code DIM1}, or {@code dimensions/<ns>/<path>}.
	 */
	private static boolean isDimensionPrefix(String[] parts, int index) {
		if (index == 0) {
			return true;
		}
		if (index == 1) {
			return parts[0].equals("DIM-1") || parts[0].equals("DIM1");
		}
		return parts[0].equals("dimensions");
	}

	private static int[] parseRegionFile(String name) {
		// r.<x>.<z>.mca, where x and z may be negative
		if (!name.startsWith("r.") || !name.endsWith(".mca")) {
			return null;
		}
		String[] parts = name.split("\\.");
		if (parts.length != 4) {
			return null;
		}
		try {
			return new int[] {Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private List<PackGroup> split(String key, List<ScannedFile> files) {
		List<PackGroup> packs = new ArrayList<>();
		List<ScannedFile> current = new ArrayList<>();
		long currentBytes = 0;

		for (ScannedFile file : files) {
			boolean full = !current.isEmpty()
					&& (currentBytes + file.size() > options.targetPackBytes()
					|| current.size() >= options.maxEntriesPerPack());
			if (full) {
				packs.add(new PackGroup(chunkId(key, packs.size() + 1), current));
				current = new ArrayList<>();
				currentBytes = 0;
			}
			current.add(file);
			currentBytes += file.size();
		}
		if (!current.isEmpty()) {
			packs.add(new PackGroup(chunkId(key, packs.size() + 1), current));
		}
		return packs;
	}

	private static String chunkId(String key, int index) {
		return index == 1 ? key : key + "~" + index;
	}
}
