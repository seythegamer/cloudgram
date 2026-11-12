package dev.cloudgram.core.telegram;

/**
 * One document that Telegram accepted.
 *
 * <p>{@code fileId} is Telegram's opaque handle for the stored bytes; it is what a restore uses to
 * call {@code getFile} without needing the original message. Message ids are only meaningful in the
 * chat that received them.
 */
public record UploadedDocument(long messageId, String fileId, String fileName, long fileSize) {
	public UploadedDocument {
		if (messageId <= 0) {
			throw new IllegalArgumentException("messageId must be positive");
		}
		if (fileId == null || fileId.isBlank()) {
			throw new IllegalArgumentException("fileId is required");
		}
		if (fileName == null || fileName.isBlank()) {
			throw new IllegalArgumentException("fileName is required");
		}
		if (fileSize < 0) {
			throw new IllegalArgumentException("fileSize must not be negative");
		}
	}
}
