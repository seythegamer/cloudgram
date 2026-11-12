package dev.cloudgram.core.restore;

/** Pacing and retry policy for downloads. */
public record RestoreOptions(int maxAttempts, long retryBaseMillis, long pauseBetweenPartsMillis) {
	public static final int DEFAULT_MAX_ATTEMPTS = 4;
	public static final long DEFAULT_RETRY_BASE_MILLIS = 1_000;
	/** One getFile + download pair per part; a small gap stays well below Bot API limits. */
	public static final long DEFAULT_PAUSE_MILLIS = 350;

	public RestoreOptions {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("maxAttempts must be at least 1");
		}
		if (retryBaseMillis < 0) {
			throw new IllegalArgumentException("retryBaseMillis must not be negative");
		}
		if (pauseBetweenPartsMillis < 0) {
			throw new IllegalArgumentException("pauseBetweenPartsMillis must not be negative");
		}
	}

	public static RestoreOptions defaults() {
		return new RestoreOptions(DEFAULT_MAX_ATTEMPTS, DEFAULT_RETRY_BASE_MILLIS, DEFAULT_PAUSE_MILLIS);
	}
}
