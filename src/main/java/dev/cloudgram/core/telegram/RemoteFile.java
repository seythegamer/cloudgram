package dev.cloudgram.core.telegram;

/** The {@code getFile} result: an opaque id plus the short-lived path used to download bytes. */
public record RemoteFile(String fileId, String filePath, long fileSize) {
	public RemoteFile {
		if (fileId == null || fileId.isBlank()) {
			throw new IllegalArgumentException("fileId is required");
		}
		if (filePath == null || filePath.isBlank()) {
			throw new IllegalArgumentException("filePath is required");
		}
		if (fileSize < 0) {
			throw new IllegalArgumentException("fileSize must not be negative");
		}
	}
}
