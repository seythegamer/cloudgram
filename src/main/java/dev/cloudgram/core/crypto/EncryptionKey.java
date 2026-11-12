package dev.cloudgram.core.crypto;

import dev.cloudgram.core.Hex;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * A 256-bit backup key.
 *
 * <p>The key is random, not derived from the bot token: Telegram sees the token on every request,
 * so a token-derived key would give Telegram the plaintext as well. The key lives in
 * {@code cloudgram.json} and can be copied out as a recovery key.
 */
public final class EncryptionKey {
	public static final int KEY_BYTES = 32;

	private static final SecureRandom RANDOM = new SecureRandom();

	private final byte[] bytes;

	private EncryptionKey(byte[] bytes) {
		this.bytes = bytes.clone();
	}

	public static EncryptionKey generate() {
		byte[] bytes = new byte[KEY_BYTES];
		RANDOM.nextBytes(bytes);
		return new EncryptionKey(bytes);
	}

	/** Accepts URL-safe or standard base64, with or without padding. */
	public static EncryptionKey fromBase64(String encoded) {
		if (encoded == null || encoded.isBlank()) {
			throw new IllegalArgumentException("Recovery key is empty.");
		}
		String value = encoded.trim();
		byte[] decoded;
		try {
			decoded = Base64.getUrlDecoder().decode(value);
		} catch (IllegalArgumentException urlSafeFailure) {
			try {
				decoded = Base64.getDecoder().decode(value);
			} catch (IllegalArgumentException standardFailure) {
				throw new IllegalArgumentException("Recovery key is not valid base64.", standardFailure);
			}
		}
		if (decoded.length != KEY_BYTES) {
			throw new IllegalArgumentException(
					"Recovery key must decode to " + KEY_BYTES + " bytes, not " + decoded.length + ".");
		}
		return new EncryptionKey(decoded);
	}

	/** URL-safe base64 without padding: safe to paste into a password manager. */
	public String toBase64() {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** A short, non-secret identifier so a restore can tell which key it needs. */
	public String fingerprint() {
		try {
			return Hex.encode(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}

	byte[] copyBytes() {
		return bytes.clone();
	}

	@Override
	public String toString() {
		return "EncryptionKey[" + fingerprint() + "]";
	}
}
