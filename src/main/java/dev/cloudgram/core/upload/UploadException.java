package dev.cloudgram.core.upload;

/** A snapshot could not be uploaded; the local vault still holds the complete snapshot. */
public final class UploadException extends Exception {
	public UploadException(String message) {
		super(message);
	}

	public UploadException(String message, Throwable cause) {
		super(message, cause);
	}
}
