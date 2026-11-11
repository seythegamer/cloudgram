package dev.cloudgram.core;

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
}
