package dev.cloudgram.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudgramConfigTest {
	private static final String TOKEN = "123456789:TEST-TOKEN-NOT-A-REAL-BOT-0000";

	@Test
	void emptyHasNoBotAndNoChat() {
		CloudgramConfig config = CloudgramConfig.empty();

		assertEquals(CloudgramConfig.CURRENT_FORMAT, config.format());
		assertFalse(config.hasBot());
		assertFalse(config.isBound());
		assertNull(config.chatId());
		assertTrue(config.backupOnExit());
	}

	@Test
	void withBotStoresTokenAndUsername() {
		CloudgramConfig config = CloudgramConfig.empty().withBot(TOKEN, "cloudgram_bot");

		assertTrue(config.hasBot());
		assertEquals(TOKEN, config.botToken());
		assertEquals("cloudgram_bot", config.botUsername());
		assertFalse(config.isBound());
	}

	@Test
	void withBindingKeepsTheBot() {
		CloudgramConfig config = CloudgramConfig.empty()
				.withBot(TOKEN, "cloudgram_bot")
				.withBinding(42L, "sey", 1_800_000_000L);

		assertTrue(config.isBound());
		assertEquals(42L, config.chatId());
		assertEquals("sey", config.chatTitle());
		assertEquals(TOKEN, config.botToken());
	}

	@Test
	void newBotTokenDropsTheOldChatBinding() {
		CloudgramConfig bound = CloudgramConfig.empty()
				.withBot(TOKEN, "cloudgram_bot")
				.withBinding(42L, "sey", 1_800_000_000L);

		CloudgramConfig sameBot = bound.withBot(TOKEN, "cloudgram_bot");
		assertEquals(42L, sameBot.chatId(), "the same token keeps its chat");

		CloudgramConfig otherBot = bound.withBot(
				"987654321:TEST-TOKEN-OTHER-BOT-000000", "other_bot");
		assertFalse(otherBot.isBound(), "a chat id is only valid for the bot that received Start");
		assertNull(otherBot.chatId());
		assertNull(otherBot.chatTitle());
		assertEquals(0L, otherBot.boundAt());
	}

	@Test
	void rejectsInvalidValues() {
		assertThrows(IllegalArgumentException.class,
				() -> new CloudgramConfig(" ", null, null, null, null, 0L, true));
		assertThrows(IllegalArgumentException.class,
				() -> new CloudgramConfig(CloudgramConfig.CURRENT_FORMAT, " ", null, null, null, 0L, true));
		assertThrows(IllegalArgumentException.class,
				() -> new CloudgramConfig(CloudgramConfig.CURRENT_FORMAT, TOKEN, null, 0L, null, 0L, true));
		assertThrows(IllegalArgumentException.class,
				() -> new CloudgramConfig(CloudgramConfig.CURRENT_FORMAT, TOKEN, null, null, null, -1L, true));
		assertThrows(IllegalArgumentException.class,
				() -> CloudgramConfig.empty().withBot(" ", "bot"));
		assertThrows(IllegalArgumentException.class,
				() -> CloudgramConfig.empty().withBot(TOKEN, " "));
		assertThrows(IllegalArgumentException.class,
				() -> CloudgramConfig.empty().withBinding(0L, "chat", 1L));
		assertThrows(IllegalArgumentException.class,
				() -> CloudgramConfig.empty().withBinding(1L, " ", 1L));
		assertThrows(IllegalArgumentException.class,
				() -> CloudgramConfig.empty().withBinding(1L, "chat", -1L));
	}

	@Test
	void backupToggleKeepsEverythingElse() {
		CloudgramConfig config = CloudgramConfig.empty()
				.withBot(TOKEN, "cloudgram_bot")
				.withBinding(42L, "sey", 1_800_000_000L)
				.withBackupOnExit(false);

		assertFalse(config.backupOnExit());
		assertEquals(42L, config.chatId());
		assertEquals(TOKEN, config.botToken());
	}
}
