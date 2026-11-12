package dev.cloudgram.core.telegram;

/** A document attached to a message, as returned by {@code getChat} or {@code forwardMessage}. */
public record RemoteDocument(String fileId, String fileName, long fileSize) {
	public RemoteDocument {
		if (fileId == null || fileId.isBlank()) {
			throw new IllegalArgumentException("fileId is required");
		}
		if (fileName != null && fileName.isBlank()) {
			throw new IllegalArgumentException("fileName must not be blank when present");
		}
		if (fileSize < 0) {
			throw new IllegalArgumentException("fileSize must not be negative");
		}
	}
}
