package dev.cloudgram.core;

import java.util.List;

/**
 * A set of world files that belong together: one region cell, {@code meta} or {@code misc}.
 *
 * <p>{@link PackPlanner} may split a group that is too large, producing ids such as
 * {@code meta~2} and {@code meta~3}.
 */
public record PackGroup(String id, List<ScannedFile> files) {
	public PackGroup {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("pack id is required");
		}
		files = List.copyOf(files);
	}

	public long totalBytes() {
		long total = 0;
		for (ScannedFile file : files) {
			total += file.size();
		}
		return total;
	}
}
