package dev.cloudgram.core;

/** Everything about the world that is known outside the world directory when a snapshot starts. */
public record SnapshotRequest(
		String worldName,
		String mcVersion,
		int dataVersion,
		String device,
		String mod) {

	public SnapshotRequest {
		if (worldName == null || worldName.isBlank()) {
			throw new IllegalArgumentException("worldName is required");
		}
		if (mcVersion == null || mcVersion.isBlank()) {
			throw new IllegalArgumentException("mcVersion is required");
		}
		if (dataVersion < 0) {
			throw new IllegalArgumentException("dataVersion must not be negative: " + dataVersion);
		}
		if (device == null || device.isBlank()) {
			throw new IllegalArgumentException("device is required");
		}
		if (mod == null || mod.isBlank()) {
			throw new IllegalArgumentException("mod version is required");
		}
	}
}
