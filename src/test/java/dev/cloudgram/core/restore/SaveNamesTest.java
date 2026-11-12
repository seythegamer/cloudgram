package dev.cloudgram.core.restore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveNamesTest {
	@TempDir
	Path tempDir;

	@Test
	void replacesCharactersThatAreIllegalOnWindows() {
		assertEquals("a_b_c_d_e_f_g_h_i_j", SaveNames.sanitize("a<b>c:d\"e/f\\g|h?i*j"));
		assertEquals("Survival world", SaveNames.sanitize("  Survival world  "));
		assertEquals("Cloudgram world", SaveNames.sanitize("   "));
		assertEquals("dots", SaveNames.sanitize("dots...   "));
	}

	@Test
	void prefixesWindowsDeviceNames() {
		assertEquals("_CON", SaveNames.sanitize("CON"));
		assertEquals("_nul", SaveNames.sanitize("nul"));
		assertEquals("_LPT1", SaveNames.sanitize("LPT1"));
		assertEquals("console", SaveNames.sanitize("console"));
	}

	@Test
	void shortensVeryLongNames() {
		String name = "x".repeat(500);

		String sanitized = SaveNames.sanitize(name);

		assertEquals(60, sanitized.length());
	}

	@Test
	void picksANewNameInsteadOfOverwriting() throws IOException {
		Path saves = tempDir.resolve("saves");
		Files.createDirectories(saves.resolve("Survival"));

		assertEquals(saves.resolve("Survival (Cloudgram 2)"), SaveNames.unique(saves, "Survival"));

		Files.createDirectories(saves.resolve("Survival (Cloudgram 2)"));
		assertEquals(saves.resolve("Survival (Cloudgram 3)"), SaveNames.unique(saves, "Survival"));
	}

	@Test
	void returnsThePlainNameWhenItIsFree() throws IOException {
		Path saves = tempDir.resolve("fresh-saves");

		Path result = SaveNames.unique(saves, "Survival");

		assertEquals(saves.resolve("Survival"), result);
		assertTrue(Files.isDirectory(saves));
		assertFalse(Files.exists(result));
	}
}
