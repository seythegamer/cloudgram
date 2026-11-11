package dev.cloudgram.core;

import java.util.List;
import java.util.Set;

/** Decides which files inside a world directory are never packed. */
public final class Exclusions {
	private static final List<String> DEFAULT_DIRECTORY_PREFIXES = List.of(
			"logs/",
			"crash-reports/",
			"debug/",
			".cloudgram/tmp/");
	private static final Set<String> DEFAULT_ROOT_FILES = Set.of("session.lock");
	private static final Set<String> DEFAULT_FILE_NAMES = Set.of(".DS_Store");

	private final List<String> directoryPrefixes;
	private final Set<String> rootFiles;
	private final Set<String> fileNames;

	public static Exclusions defaults() {
		return new Exclusions(DEFAULT_DIRECTORY_PREFIXES, DEFAULT_ROOT_FILES, DEFAULT_FILE_NAMES);
	}

	public Exclusions(List<String> directoryPrefixes, Set<String> rootFiles, Set<String> fileNames) {
		this.directoryPrefixes = List.copyOf(directoryPrefixes);
		this.rootFiles = Set.copyOf(rootFiles);
		this.fileNames = Set.copyOf(fileNames);
	}

	/** {@code true} when the whole directory (and therefore its subtree) must be skipped. */
	public boolean isExcludedDirectory(String relativePath) {
		String path = WorldPaths.normalize(relativePath);
		if (path.isEmpty()) {
			return false;
		}
		String withSlash = path + WorldPaths.SEPARATOR;
		for (String prefix : directoryPrefixes) {
			if (withSlash.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	/** {@code true} when this single file must not be packed. */
	public boolean isExcluded(String relativePath) {
		String path = WorldPaths.normalize(relativePath);
		if (path.isEmpty() || isExcludedDirectory(path)) {
			return true;
		}
		if (rootFiles.contains(path)) {
			return true;
		}
		int slash = path.lastIndexOf('/');
		String name = slash < 0 ? path : path.substring(slash + 1);
		return fileNames.contains(name);
	}
}
