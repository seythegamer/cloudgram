package dev.cloudgram.core.telegram;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotTokensTest {
	private static final String TOKEN = "123456789:TEST-TOKEN-NOT-A-REAL-BOT-0000";

	@Test
	void acceptsATelegramShapedToken() {
		assertTrue(BotTokens.isValid(TOKEN));
		assertEquals(TOKEN, BotTokens.normalize(TOKEN));
		assertEquals(TOKEN, BotTokens.normalize("  " + TOKEN + "\n"));
		assertEquals(123456789L, BotTokens.botId(TOKEN));
	}

	@Test
	void rejectsMalformedTokens() {
		assertFalse(BotTokens.isValid(null));
		assertFalse(BotTokens.isValid(""));
		assertFalse(BotTokens.isValid("not-a-token"));
		assertFalse(BotTokens.isValid("123456789:short"));
		assertFalse(BotTokens.isValid("123456789:TEST-TOKEN-NOT-A-REAL-BOT-00 00"));
		assertFalse(BotTokens.isValid(":TEST-TOKEN-NOT-A-REAL-BOT-0000"));
		assertThrows(IllegalArgumentException.class, () -> BotTokens.normalize(null));
		assertThrows(IllegalArgumentException.class, () -> BotTokens.normalize("  "));
		assertThrows(IllegalArgumentException.class, () -> BotTokens.normalize("123456789:short"));
	}

	@Test
	void redactionKeepsTheBotIdButNeverTheSecret() {
		String redacted = BotTokens.redact(TOKEN);

		assertEquals("123456789:<redacted>", redacted);
		assertFalse(redacted.contains("TEST-TOKEN"));
		assertEquals("<no token>", BotTokens.redact(null));
		assertEquals("<invalid token>", BotTokens.redact("not-a-token"));
	}
}
