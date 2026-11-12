package dev.cloudgram.core.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * Encrypts one object (a pack part or the manifest) with AES-256-GCM.
 *
 * <p>Layout: {@code "CG1E" | version 1 | 12-byte nonce | ciphertext | 16-byte tag}. A fresh random
 * nonce is used for every upload, so uploading the same pack twice produces different bytes while
 * the manifest hashes stay over plaintext. The 33-byte overhead (5-byte header, 12-byte nonce,
 * 16-byte tag) is far below the part limit.
 */
public final class PayloadCipher {
	public static final String SCHEME = "aes-256-gcm/v1";

	private static final byte[] MAGIC = {'C', 'G', '1', 'E'};
	private static final byte VERSION = 1;
	private static final int NONCE_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final int TAG_BYTES = TAG_BITS / 8;
	private static final int HEADER_BYTES = MAGIC.length + 1 + NONCE_BYTES;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final EncryptionKey key;

	public PayloadCipher(EncryptionKey key) {
		if (key == null) {
			throw new IllegalArgumentException("key is required");
		}
		this.key = key;
	}

	/** The non-secret identifier of the key this cipher uses. */
	public String keyFingerprint() {
		return key.fingerprint();
	}

	/** Returns {@code magic | version | nonce | AES-GCM(plaintext)}. */
	public byte[] encrypt(byte[] plaintext, byte[] aad) {
		if (plaintext == null) {
			throw new IllegalArgumentException("plaintext is required");
		}
		byte[] nonce = new byte[NONCE_BYTES];
		RANDOM.nextBytes(nonce);
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE,
					new SecretKeySpec(key.copyBytes(), "AES"),
					new GCMParameterSpec(TAG_BITS, nonce));
			if (aad != null && aad.length > 0) {
				cipher.updateAAD(aad);
			}
			byte[] sealed = cipher.doFinal(plaintext);

			ByteBuffer out = ByteBuffer.allocate(HEADER_BYTES + sealed.length);
			out.put(MAGIC);
			out.put(VERSION);
			out.put(nonce);
			out.put(sealed);
			return out.array();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("AES-GCM is not available", e);
		}
	}

	/** Decrypts an object produced by {@link #encrypt}; fails on tampering or a wrong key/AAD. */
	public byte[] decrypt(byte[] payload, byte[] aad) throws CryptoException {
		if (payload == null || payload.length < HEADER_BYTES + TAG_BYTES) {
			throw new CryptoException("Encrypted payload is too short");
		}
		if (!isEncrypted(payload)) {
			throw new CryptoException("Payload does not have the Cloudgram encryption header");
		}
		ByteBuffer in = ByteBuffer.wrap(payload);
		in.position(MAGIC.length);
		byte version = in.get();
		if (version != VERSION) {
			throw new CryptoException("Unsupported encryption version: " + version);
		}
		byte[] nonce = new byte[NONCE_BYTES];
		in.get(nonce);
		byte[] sealed = new byte[in.remaining()];
		in.get(sealed);

		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE,
					new SecretKeySpec(key.copyBytes(), "AES"),
					new GCMParameterSpec(TAG_BITS, nonce));
			if (aad != null && aad.length > 0) {
				cipher.updateAAD(aad);
			}
			return cipher.doFinal(sealed);
		} catch (GeneralSecurityException e) {
			throw new CryptoException("Could not decrypt the payload (wrong key or modified data)", e);
		}
	}

	/** Cheap detection for backups made before encryption existed. */
	public static boolean isEncrypted(byte[] payload) {
		if (payload == null || payload.length < HEADER_BYTES + TAG_BYTES) {
			return false;
		}
		for (int i = 0; i < MAGIC.length; i++) {
			if (payload[i] != MAGIC[i]) {
				return false;
			}
		}
		return payload[MAGIC.length] == VERSION;
	}
}
