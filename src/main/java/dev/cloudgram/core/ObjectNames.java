package dev.cloudgram.core;

import java.util.Locale;

/**
 * Naming rules shared by local staging, upload and restore.
 *
 * <p>Telegram file names must be safe on every platform, so pack ids (which contain
 * {@code :}, {@code ~} and {@code -}) are folded into a flat alphabet.
 */
public final class ObjectNames {
	private ObjectNames() {
	}

	/** {@code cell:-1:2} becomes {@code cell_n1_2}, {@code meta~2} becomes {@code meta_2}. */
	public static String safePackId(String packId) {
		if (packId == null || packId.isBlank()) {
			throw new IllegalArgumentException("packId is required");
		}
		return packId.replace(':', '_').replace('~', '_').replace('-', 'n');
	}

	/** The first eight hex digits of a world UUID, as used by the local vault and upload names. */
	public static String shortWorldId(String worldId) {
		if (worldId == null || worldId.isBlank()) {
			throw new IllegalArgumentException("worldId is required");
		}
		String compact = worldId.replace("-", "");
		if (compact.length() < 8) {
			throw new IllegalArgumentException("worldId is too short: " + worldId);
		}
		return compact.substring(0, 8);
	}

	/** {@code cg1_<world>_<pack>_<packSha12>_p<part, 3 digits>.bin} from SPEC §5. */
	public static String partFileName(String worldId, String packId, String packSha256, int partIndex) {
		if (partIndex < 0) {
			throw new IllegalArgumentException("partIndex must not be negative");
		}
		if (!Hex.isHex(packSha256, 64)) {
			throw new IllegalArgumentException("packSha256 must be 64 lower-case hex characters");
		}
		return "cg1_" + shortWorldId(worldId) + "_" + safePackId(packId) + "_"
				+ packSha256.substring(0, 12) + "_p" + String.format(Locale.ROOT, "%03d", partIndex) + ".bin";
	}

	/** The manifest is the one object whose name identifies a snapshot directly. */
	public static String manifestFileName(String worldId, String snapshotId) {
		if (snapshotId == null || snapshotId.isBlank()) {
			throw new IllegalArgumentException("snapshotId is required");
		}
		return "cg1_" + shortWorldId(worldId) + "_" + snapshotId + "_manifest.json";
	}

	/** A short code a user can type into the bot chat to identify a snapshot. */
	public static String restoreCode(long manifestMessageId) {
		if (manifestMessageId <= 0) {
			throw new IllegalArgumentException("manifestMessageId must be positive");
		}
		return "CG1-" + manifestMessageId;
	}
}
