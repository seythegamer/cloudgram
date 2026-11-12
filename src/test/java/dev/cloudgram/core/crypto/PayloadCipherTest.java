package dev.cloudgram.core.crypto;

import dev.cloudgram.core.Hex;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadCipherTest {
	private static final byte[] AAD = Aad.part(
			"0f3c9a2e-7b1d-4f5a-9c3e-2a1b4c5d6e7f",
			"20261111T230000Z-a1b2c3",
			"cell:0:0",
			0);

	private final EncryptionKey key = EncryptionKey.generate();
	private final PayloadCipher cipher = new PayloadCipher(key);

	@Test
	void roundTripsAndKeepsTheHeader() throws CryptoException {
		byte[] plaintext = "region data that must survive a round trip".getBytes(StandardCharsets.UTF_8);

		byte[] encrypted = cipher.encrypt(plaintext, AAD);

		assertTrue(PayloadCipher.isEncrypted(encrypted));
		assertArrayEquals("CG1E".getBytes(StandardCharsets.US_ASCII),
				new byte[] {encrypted[0], encrypted[1], encrypted[2], encrypted[3]});
		assertEquals(1, encrypted[4], "version byte");
		assertEquals(plaintext.length + 33, encrypted.length, "header plus nonce and GCM tag");
		assertArrayEquals(plaintext, cipher.decrypt(encrypted, AAD));
	}

	@Test
	void usesAFreshNonceForEveryObject() throws CryptoException {
		byte[] plaintext = "same pack bytes".getBytes(StandardCharsets.UTF_8);

		byte[] first = cipher.encrypt(plaintext, AAD);
		byte[] second = cipher.encrypt(plaintext, AAD);

		assertFalse(java.util.Arrays.equals(first, second), "nonces make ciphertext unique");
		assertArrayEquals(plaintext, cipher.decrypt(first, AAD));
		assertArrayEquals(plaintext, cipher.decrypt(second, AAD));
	}

	@Test
	void bindsCiphertextToItsPlaceInTheSnapshot() {
		byte[] encrypted = cipher.encrypt("part".getBytes(StandardCharsets.UTF_8), AAD);

		byte[] otherPart = Aad.part(
				"0f3c9a2e-7b1d-4f5a-9c3e-2a1b4c5d6e7f",
				"20261111T230000Z-a1b2c3",
				"cell:0:0",
				1);

		assertThrows(CryptoException.class, () -> cipher.decrypt(encrypted, otherPart));
		assertThrows(CryptoException.class, () -> cipher.decrypt(encrypted, new byte[0]));
	}

	@Test
	void rejectsAnotherKey() {
		byte[] encrypted = cipher.encrypt("secret".getBytes(StandardCharsets.UTF_8), AAD);
		PayloadCipher other = new PayloadCipher(EncryptionKey.generate());

		assertThrows(CryptoException.class, () -> other.decrypt(encrypted, AAD));
	}

	@Test
	void detectsTamperingInTheBodyAndTheHeader() {
		byte[] encrypted = cipher.encrypt("payload".getBytes(StandardCharsets.UTF_8), AAD);

		byte[] tamperedBody = encrypted.clone();
		tamperedBody[tamperedBody.length - 1] ^= 0x01;
		assertThrows(CryptoException.class, () -> cipher.decrypt(tamperedBody, AAD));

		byte[] tamperedMagic = encrypted.clone();
		tamperedMagic[0] = 'X';
		assertFalse(PayloadCipher.isEncrypted(tamperedMagic));
		assertThrows(CryptoException.class, () -> cipher.decrypt(tamperedMagic, AAD));
	}

	@Test
	void doesNotMistakePlaintextForCiphertext() {
		assertFalse(PayloadCipher.isEncrypted(null));
		assertFalse(PayloadCipher.isEncrypted(new byte[0]));
		assertFalse(PayloadCipher.isEncrypted("CG1E".getBytes(StandardCharsets.US_ASCII)));
		assertFalse(PayloadCipher.isEncrypted("plain manifest json".getBytes(StandardCharsets.UTF_8)));
		assertThrows(CryptoException.class,
				() -> cipher.decrypt("{}".getBytes(StandardCharsets.UTF_8), AAD));
	}

	@Test
	void keysRoundTripThroughUrlSafeBase64() {
		String encoded = key.toBase64();

		assertFalse(encoded.contains("="), "padding is stripped");
		assertFalse(encoded.contains("+") && encoded.contains("/"), "URL-safe alphabet is used");
		assertEquals(key.fingerprint(), EncryptionKey.fromBase64(encoded).fingerprint());
		assertEquals(encoded, EncryptionKey.fromBase64("  " + encoded + "  ").toBase64());
	}

	@Test
	void acceptsStandardBase64Too() {
		String standard = Base64.getEncoder().encodeToString(Base64.getUrlDecoder().decode(key.toBase64()));

		assertEquals(key.fingerprint(), EncryptionKey.fromBase64(standard).fingerprint());
	}

	@Test
	void rejectsMalformedRecoveryKeys() {
		assertThrows(IllegalArgumentException.class, () -> EncryptionKey.fromBase64(null));
		assertThrows(IllegalArgumentException.class, () -> EncryptionKey.fromBase64("   "));
		assertThrows(IllegalArgumentException.class, () -> EncryptionKey.fromBase64("not base64!"));
		assertThrows(IllegalArgumentException.class,
				() -> EncryptionKey.fromBase64(Base64.getUrlEncoder().encodeToString(new byte[16])));
	}

	@Test
	void fingerprintsAreShortStableHexAndNotTheKey() {
		String fingerprint = key.fingerprint();

		assertTrue(Hex.isHex(fingerprint, 16));
		assertEquals(fingerprint, EncryptionKey.fromBase64(key.toBase64()).fingerprint());
		assertNotEquals(fingerprint, EncryptionKey.generate().fingerprint());
		assertFalse(key.toString().contains(key.toBase64()), "toString never prints the key");
		assertTrue(key.toString().contains(fingerprint));
	}

	@Test
	void aadSeparatesPartsManifestsAndSnapshots() {
		byte[] part = Aad.part("world", "snapshot", "pack", 0);
		byte[] otherPart = Aad.part("world", "snapshot", "pack", 1);
		byte[] manifest = Aad.manifest("world", "snapshot");

		assertFalse(java.util.Arrays.equals(part, otherPart));
		assertFalse(java.util.Arrays.equals(part, manifest));
		assertFalse(java.util.Arrays.equals(manifest, Aad.manifest("world", "other-snapshot")));
		assertThrows(IllegalArgumentException.class, () -> Aad.part(" ", "snapshot", "pack", 0));
		assertThrows(IllegalArgumentException.class, () -> Aad.part("world", "snapshot", "pack", -1));
		assertThrows(IllegalArgumentException.class, () -> Aad.manifest("world", " "));
	}
}
