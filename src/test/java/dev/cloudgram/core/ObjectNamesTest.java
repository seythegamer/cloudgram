package dev.cloudgram.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObjectNamesTest {
	private static final String WORLD = "0f3c9a2e-7b1d-4f5a-9c3e-2a1b4c5d6e7f";
	private static final String PACK_SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

	@Test
	void shortWorldIdDropsDashes() {
		assertEquals("0f3c9a2e", ObjectNames.shortWorldId(WORLD));
		assertEquals("0f3c9a2e", ObjectNames.shortWorldId("0f3c9a2e"));
		assertThrows(IllegalArgumentException.class, () -> ObjectNames.shortWorldId("abc"));
		assertThrows(IllegalArgumentException.class, () -> ObjectNames.shortWorldId(" "));
	}

	@Test
	void partNamesFollowTheSpec() {
		assertEquals(
				"cg1_0f3c9a2e_cell_n1_2_e3b0c44298fc_p000.bin",
				ObjectNames.partFileName(WORLD, "cell:-1:2", PACK_SHA, 0));
		assertEquals(
				"cg1_0f3c9a2e_meta_e3b0c44298fc_p012.bin",
				ObjectNames.partFileName(WORLD, "meta", PACK_SHA, 12));
		assertThrows(IllegalArgumentException.class,
				() -> ObjectNames.partFileName(WORLD, "meta", PACK_SHA, -1));
		assertThrows(IllegalArgumentException.class,
				() -> ObjectNames.partFileName(WORLD, "meta", "not-a-hash", 0));
	}

	@Test
	void manifestNamesContainTheSnapshot() {
		assertEquals(
				"cg1_0f3c9a2e_20261111T230000Z-a1b2c3_manifest.json",
				ObjectNames.manifestFileName(WORLD, "20261111T230000Z-a1b2c3"));
		assertThrows(IllegalArgumentException.class, () -> ObjectNames.manifestFileName(WORLD, " "));
	}

	@Test
	void restoreCodesReferToTheManifestMessage() {
		assertEquals("CG1-42", ObjectNames.restoreCode(42));
		assertThrows(IllegalArgumentException.class, () -> ObjectNames.restoreCode(0));
	}
}
