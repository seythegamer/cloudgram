package dev.cloudgram.core.setup;

import dev.cloudgram.core.telegram.BotInfo;
import dev.cloudgram.core.telegram.IncomingMessage;
import dev.cloudgram.core.telegram.TelegramException;
import dev.cloudgram.core.telegram.TelegramTransport;
import dev.cloudgram.core.telegram.UploadedDocument;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramSetupTest {
	private static final String TOKEN = "123456789:TEST-TOKEN-NOT-A-REAL-BOT-0000";
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC);

	private final StubTransport transport = new StubTransport();
	private final TelegramSetup setup = new TelegramSetup(transport, CLOCK);

	@Test
	void verifyNormalisesTheTokenAndReturnsTheBot() throws TelegramException {
		BotInfo bot = setup.verify("  " + TOKEN + "\n");

		assertEquals("cloudgram_bot", bot.username());
		assertEquals(TOKEN, transport.lastToken);
		assertTrue(transport.meCalled.get());
	}

	@Test
	void startCursorIsTheNewestPendingUpdate() throws TelegramException {
		transport.updates = List.of(
				message(10, 1, 100, "private", "/start"),
				message(12, 2, 100, "private", "hello"));

		assertEquals(12, setup.startCursor(TOKEN));
		assertEquals(-1, transport.lastOffset);
	}

	@Test
	void startCursorIsZeroForAFreshBot() throws TelegramException {
		transport.updates = List.of();
		assertEquals(0, setup.startCursor(TOKEN));
	}

	@Test
	void bindsToTheNewestPrivateStartAfterTheCursor() throws TelegramException, SetupException {
		transport.updates = List.of(
				message(5, 1, 100, "private", "/start"),          // before the cursor
				message(11, 2, 200, "channel", "/start"),          // not a private chat
				message(12, 3, 300, "private", "hello"),           // not a command
				message(13, 4, 400, "private", "/start"),
				message(14, 5, 500, "private", "/start now"));

		Binding binding = setup.bind(TOKEN, "cloudgram_bot", 10);

		assertEquals(500, binding.chatId());
		assertEquals("Chat 500", binding.title());
		assertEquals("private", binding.chatType());
		assertEquals(Instant.parse("2026-11-12T10:00:00Z").getEpochSecond(), binding.boundAt());
		assertEquals(11, transport.lastOffset, "only updates after the cursor are requested");
	}

	@Test
	void ignoresStaleStartMessages() throws TelegramException {
		transport.updates = List.of(message(5, 1, 100, "private", "/start"));

		SetupException error = assertThrows(SetupException.class, () -> setup.bind(TOKEN, "cloudgram_bot", 10));

		assertEquals(SetupException.Reason.NO_START, error.reason());
		assertTrue(error.getMessage().contains("Start"));
	}

	@Test
	void failsClearlyWhenTheUserHasNotPressedStart() {
		transport.updates = List.of(message(11, 2, 200, "channel", "/start"));

		SetupException error = assertThrows(SetupException.class, () -> setup.bind(TOKEN, "cloudgram_bot", 10));

		assertEquals(SetupException.Reason.NO_START, error.reason());
	}

	@Test
	void announceBindingSendsAndPinsAConfirmation() throws TelegramException {
		Binding binding = new Binding(42L, "sey", "private", 1L);
		transport.sentMessageId = 77;

		long messageId = setup.announceBinding(TOKEN, binding, "DESKTOP", "0.1.0");

		assertEquals(77, messageId);
		assertTrue(transport.sentText.contains("Cloudgram is connected"));
		assertTrue(transport.sentText.contains("DESKTOP"));
		assertEquals(List.of(77L), transport.pinned);
		assertEquals(42L, transport.lastChatId);
	}

	@Test
	void pinFailureDoesNotFailTheBinding() throws TelegramException {
		Binding binding = new Binding(42L, "sey", "private", 1L);
		transport.failPin = true;

		long messageId = setup.announceBinding(TOKEN, binding, "DESKTOP", "0.1.0");

		assertEquals(transport.sentMessageId, messageId);
		assertTrue(transport.sentText.contains("DESKTOP"));
	}

	@Test
	void buildsDeepLinks() {
		assertEquals("https://t.me/BotFather", TelegramSetup.BOTFATHER_URL);
		assertEquals("https://t.me/cloudgram_bot", TelegramSetup.botUrl("cloudgram_bot"));
		assertThrows(IllegalArgumentException.class, () -> TelegramSetup.botUrl(" "));
	}

	@Test
	void rejectsNegativeCursor() {
		assertThrows(IllegalArgumentException.class, () -> setup.bind(TOKEN, "bot", -1));
	}

	@Test
	void transportIsRequired() {
		assertThrows(IllegalArgumentException.class, () -> new TelegramSetup(null));
	}

	private static IncomingMessage message(long updateId, long messageId, long chatId, String chatType,
			String text) {
		return new IncomingMessage(
				updateId, messageId, chatId, chatType, "Chat " + chatId, text, 1_800_000_000L);
	}

	private static final class StubTransport implements TelegramTransport {
		private List<IncomingMessage> updates = new ArrayList<>();
		private String lastToken;
		private long lastOffset = Long.MIN_VALUE;
		private long lastChatId;
		private long sentMessageId = 1;
		private String sentText;
		private List<Long> pinned = new ArrayList<>();
		private boolean failPin;
		private final AtomicBoolean meCalled = new AtomicBoolean();

		@Override
		public BotInfo getMe(String token) {
			meCalled.set(true);
			lastToken = token;
			return new BotInfo(123456789L, "cloudgram_bot", "Cloudgram");
		}

		@Override
		public List<IncomingMessage> getUpdates(String token, long offset, int timeoutSeconds) {
			lastToken = token;
			lastOffset = offset;
			return List.copyOf(updates);
		}

		@Override
		public long sendMessage(String token, long chatId, String text) {
			lastToken = token;
			lastChatId = chatId;
			sentText = text;
			return sentMessageId;
		}

		@Override
		public UploadedDocument sendDocument(
				String token, long chatId, String fileName, byte[] content, String caption) {
			throw new UnsupportedOperationException("setup tests do not upload documents");
		}

		@Override
		public void pinChatMessage(String token, long chatId, long messageId) throws TelegramException {
			if (failPin) {
				throw new TelegramException(400, "chat not modified", 0);
			}
			lastToken = token;
			lastChatId = chatId;
			pinned.add(messageId);
		}
	}
}
