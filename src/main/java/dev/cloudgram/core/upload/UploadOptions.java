package dev.cloudgram.core.upload;

/** Pacing and retry policy for the upload queue. */
public record UploadOptions(int maxAttempts, long retryBaseMillis, long minIntervalMillis) {
	public static final int DEFAULT_MAX_ATTEMPTS = 4;
	public static final long DEFAULT_RETRY_BASE_MILLIS = 1_000;
	/** Telegram dislikes bursts of documents; one per 1.2 s is comfortably below the limit. */
	public static final long DEFAULT_MIN_INTERVAL_MILLIS = 1_200;

	public UploadOptions {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("maxAttempts must be at least 1");
		}
		if (retryBaseMillis < 0) {
			throw new IllegalArgumentException("retryBaseMillis must not be negative");
		}
		if (minIntervalMillis < 0) {
			throw new IllegalArgumentException("minIntervalMillis must not be negative");
		}
	}

	public static UploadOptions defaults() {
		return new UploadOptions(DEFAULT_MAX_ATTEMPTS, DEFAULT_RETRY_BASE_MILLIS, DEFAULT_MIN_INTERVAL_MILLIS);
	}
}
