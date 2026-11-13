package dev.cloudgram.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigStoreTest {
	private static final String TOKEN = "123456789:TEST-TOKEN-NOT-A-REAL-BOT-0000";

	@TempDir
	Path tempDir;

	@Test
	void missingFileLoadsEmptyConfig() throws IOException {
		ConfigStore store = new ConfigStore(tempDir.resolve("config/cloudgram.json"));

		assertFalse(store.exists());
		assertEquals(CloudgramConfig.empty(), store.load());
	}

	@Test
	void roundTripsThroughJson() throws IOException {
		ConfigStore store = new ConfigStore(tempDir.resolve("config/cloudgram.json"));
		CloudgramConfig config = CloudgramConfig.empty()
				.withBot(TOKEN, "cloudgram_bot")
				.withBinding(42L, "sey", 1_800_000_000L)
				.withBackupOnExit(false)
				.withEncryptionKey("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8");

		store.save(config);

		assertTrue(store.exists());
		assertEquals(config, store.load());
		assertFalse(Files.exists(store.file().resolveSibling(ConfigStore.FILE_NAME + ".tmp")),
				"the temp file must be moved into place");
	}

	@Test
	void writesReadableStableJson() throws IOException {
		ConfigStore store = new ConfigStore(tempDir.resolve("config/cloudgram.json"));
		store.save(CloudgramConfig.empty().withBot(TOKEN, "cloudgram_bot"));

		String json = Files.readString(store.file(), StandardCharsets.UTF_8);

		assertTrue(json.startsWith("{\n"));
		assertTrue(json.endsWith("}\n"));
		assertTrue(json.contains("\"format\": \"cloudgram/config/1\""));
		assertTrue(json.contains("\"botUsername\": \"cloudgram_bot\""));
		assertFalse(json.contains("\"chatId\""), "null values are omitted");
	}

	@Test
	void overwritesAnExistingFile() throws IOException {
		ConfigStore store = new ConfigStore(tempDir.resolve("cloudgram.json"));
		store.save(CloudgramConfig.empty());
		store.save(CloudgramConfig.empty().withBot(TOKEN, "cloudgram_bot"));

		assertTrue(store.load().hasBot());
	}

	@Test
	void rejectsUnknownFormat() throws IOException {
		ConfigStore store = new ConfigStore(tempDir.resolve("cloudgram.json"));
		Files.writeString(store.file(), "{\"format\":\"cloudgram/config/999\"}", StandardCharsets.UTF_8);

		IOException error = assertThrows(IOException.class, store::load);
		assertTrue(error.getMessage().contains("unsupported config format"));
	}

	@Test
	void rejectsMalformedJson() throws IOException {
		ConfigStore store = new ConfigStore(tempDir.resolve("cloudgram.json"));
		Files.writeString(store.file(), "{ this is not json", StandardCharsets.UTF_8);

		assertThrows(IOException.class, store::load);
	}

	@Test
	void rejectsStructurallyInvalidConfig() throws IOException {
		ConfigStore store = new ConfigStore(tempDir.resolve("cloudgram.json"));
		Files.writeString(
				store.file(),
				"{\"format\":\"cloudgram/config/1\",\"chatId\":0}",
				StandardCharsets.UTF_8);

		assertThrows(IOException.class, store::load);
	}

	@Test
	void configsWithoutTheImportPromptFieldStillLoad() throws IOException {
		ConfigStore store = new ConfigStore(tempDir.resolve("cloudgram.json"));
		Files.writeString(
				store.file(),
				"{\"format\":\"cloudgram/config/1\",\"botToken\":\"" + TOKEN
						+ "\",\"botUsername\":\"cloudgram_bot\",\"chatId\":42,\"chatTitle\":\"sey\"}",
				StandardCharsets.UTF_8);

		CloudgramConfig config = store.load();

		assertTrue(config.isBound());
		assertFalse(config.importPrompted(), "installs from before the prompt must be offered it once");
	}

	@Test
	void createsMissingParentDirectory() throws IOException {
		ConfigStore store = new ConfigStore(tempDir.resolve("a/b/c/cloudgram.json"));

		store.save(CloudgramConfig.empty());

		assertTrue(Files.isRegularFile(store.file()));
	}
}
