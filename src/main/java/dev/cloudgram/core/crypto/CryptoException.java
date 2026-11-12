package dev.cloudgram.core.crypto;

/** A payload is malformed, was encrypted with another key, or was modified in transit. */
public final class CryptoException extends Exception {
	public CryptoException(String message) {
		super(message);
	}

	public CryptoException(String message, Throwable cause) {
		super(message, cause);
	}
}
