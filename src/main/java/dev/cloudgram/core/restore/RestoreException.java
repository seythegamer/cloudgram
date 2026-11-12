package dev.cloudgram.core.restore;

/** A snapshot could not be restored; the target save directory was not created. */
public final class RestoreException extends Exception {
	public RestoreException(String message) {
		super(message);
	}

	public RestoreException(String message, Throwable cause) {
		super(message, cause);
	}
}
