package dev.cloudgram.core.crypto;

import java.nio.charset.StandardCharsets;

/**
 * Additional authenticated data for each encrypted object.
 *
 * <p>AAD binds ciphertext to its exact place in a snapshot, so a valid part cannot be replayed as
 * another part, pack or snapshot even though all objects share one key.
 */
public final class Aad {
	private static final String PREFIX = "cloudgram";

	private Aad() {
	}

	public static byte[] part(String worldId, String snapshotId, String packId, int partIndex) {
		require("worldId", worldId);
		require("snapshotId", snapshotId);
		require("packId", packId);
		if (partIndex < 0) {
			throw new IllegalArgumentException("partIndex must not be negative");
		}
		return text(PREFIX + "|" + PayloadCipher.SCHEME + "|part|" + worldId + "|" + snapshotId
				+ "|" + packId + "|" + partIndex);
	}

	public static byte[] manifest(String worldId, String snapshotId) {
		require("worldId", worldId);
		require("snapshotId", snapshotId);
		return text(PREFIX + "|" + PayloadCipher.SCHEME + "|manifest|" + worldId + "|" + snapshotId);
	}

	private static byte[] text(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}

	private static void require(String name, String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " is required");
		}
	}
}
