package dev.cloudgram.core.upload;

import dev.cloudgram.core.crypto.PayloadCipher;
import dev.cloudgram.core.telegram.UploadedDocument;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadRecordTest {
	private static final UploadedDocument MANIFEST =
			new UploadedDocument(2, "file-id", "manifest.json", 100);

	@Test
	void readsRecordsWrittenBeforeEncryption() throws IOException {
		String json = """
				{
				  "format": "cloudgram/upload/1",
				  "worldId": "0f3c9a2e-7b1d-4f5a-9c3e-2a1b4c5d6e7f",
				  "snapshotId": "20261111T230000Z-a1b2c3",
				  "chatId": 42,
				  "manifest": {"messageId": 2, "fileId": "file-id", "fileName": "manifest.json", "fileSize": 100},
				  "packs": [],
				  "summaryMessageId": 3,
				  "restoreCode": "CG1-2"
				}
				""";

		UploadRecord record = UploadRecord.fromJson(json);

		assertFalse(record.isEncrypted(), "old backups are plaintext and must stay readable");
		assertNull(record.encryption());
		assertNull(record.keyFingerprint());
		assertEquals("CG1-2", record.restoreCode());
	}

	@Test
	void roundTripsEncryptionMetadata() throws IOException {
		UploadRecord encrypted = new UploadRecord(
				UploadRecord.CURRENT_FORMAT,
				"0f3c9a2e-7b1d-4f5a-9c3e-2a1b4c5d6e7f",
				"20261111T230000Z-a1b2c3",
				42L,
				MANIFEST,
				List.of(),
				3L,
				"CG1-2",
				null,
				PayloadCipher.SCHEME,
				"335ecb5127723917");

		UploadRecord parsed = UploadRecord.fromJson(encrypted.toJson());

		assertTrue(parsed.isEncrypted());
		assertEquals(PayloadCipher.SCHEME, parsed.encryption());
		assertEquals("335ecb5127723917", parsed.keyFingerprint());
		assertEquals(encrypted, parsed);
	}

	@Test
	void requiresAFormatAndCoreFields() {
		assertThrows(IllegalArgumentException.class, () -> new UploadRecord(
				"cloudgram/upload/999", "world", "snapshot", 1L, MANIFEST, List.of(), 0L, "CG1-1", null));
		assertThrows(IllegalArgumentException.class, () -> new UploadRecord(
				UploadRecord.CURRENT_FORMAT, "world", "snapshot", 1L, MANIFEST, List.of(), 0L, "CG1-1",
				null, " ", "fingerprint"));
	}
}
