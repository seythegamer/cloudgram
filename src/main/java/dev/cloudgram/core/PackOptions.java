package dev.cloudgram.core;

/** Tunables for planning and writing packs. */
public record PackOptions(
		long targetPackBytes,
		long partMaxBytes,
		int maxEntriesPerPack,
		Exclusions exclusions) {

	/** Preferred pack size; groups larger than this are split at file boundaries. */
	public static final long DEFAULT_TARGET_PACK_BYTES = 16L * 1024 * 1024;
	/** Hard cap per part; Telegram's {@code getFile} download limit is 20 MB. */
	public static final long DEFAULT_PART_MAX_BYTES = 19L * 1024 * 1024;
	/** ZIP (non-Zip64) allows 65,535 entries; stay well below. */
	public static final int DEFAULT_MAX_ENTRIES_PER_PACK = 30_000;

	public PackOptions {
		if (targetPackBytes <= 0) {
			throw new IllegalArgumentException("targetPackBytes must be positive");
		}
		if (partMaxBytes <= 0) {
			throw new IllegalArgumentException("partMaxBytes must be positive");
		}
		if (maxEntriesPerPack <= 0 || maxEntriesPerPack > 0xFFFF) {
			throw new IllegalArgumentException("maxEntriesPerPack must be between 1 and 65535");
		}
		if (exclusions == null) {
			throw new IllegalArgumentException("exclusions is required");
		}
	}

	public static PackOptions defaults() {
		return new PackOptions(
				DEFAULT_TARGET_PACK_BYTES,
				DEFAULT_PART_MAX_BYTES,
				DEFAULT_MAX_ENTRIES_PER_PACK,
				Exclusions.defaults());
	}
}
