package dev.cloudgram.core;

/** One byte range of a pack, small enough for Telegram to hand back via {@code getFile}. */
public record PackPart(int index, long offset, long size, String sha256) {
	public PackPart {
		if (index < 0) {
			throw new IllegalArgumentException("index must not be negative: " + index);
		}
		if (offset < 0) {
			throw new IllegalArgumentException("offset must not be negative: " + offset);
		}
		if (size <= 0) {
			throw new IllegalArgumentException("size must be positive: " + size);
		}
		if (!Hex.isHex(sha256, 64)) {
			throw new IllegalArgumentException("sha256 must be 64 lower-case hex characters");
		}
	}
}
