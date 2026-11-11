package dev.cloudgram.core.setup;

import dev.cloudgram.core.telegram.BotInfo;
import dev.cloudgram.core.telegram.BotTokens;
import dev.cloudgram.core.telegram.IncomingMessage;
import dev.cloudgram.core.telegram.TelegramException;
import dev.cloudgram.core.telegram.TelegramTransport;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The logic behind the setup wizard, independent of Minecraft and of the screen.
 *
 * <p>The flow is deliberately explicit about Telegram's lack of a history API:
 *
 * <ol>
 *   <li>{@link #verify(String)} calls {@code getMe} to prove the token works.</li>
 *   <li>{@link #startCursor(String)} records the newest pending update id <em>before</em> the user
 *       is asked to press Start, so an old message can never be mistaken for a fresh binding.</li>
 *   <li>{@link #bind(String, String, long)} reads updates after that cursor, keeps only
 *       {@code /start} in a private chat, and binds to the newest one.</li>
 * </ol>
 *
 * <p>Binding does not write the config: the caller does that after it has a {@link Binding}, so a
 * cancelled wizard never leaves a half-configured file behind.
 */
public final class TelegramSetup {
	public static final String BOTFATHER_URL = "https://t.me/BotFather";
	private static final String TELEGRAM_WEB = "https://t.me/";

	private final TelegramTransport transport;
	private final Clock clock;

	public TelegramSetup(TelegramTransport transport) {
		this(transport, Clock.systemUTC());
	}

	public TelegramSetup(TelegramTransport transport, Clock clock) {
		if (transport == null) {
			throw new IllegalArgumentException("transport is required");
		}
		if (clock == null) {
			throw new IllegalArgumentException("clock is required");
		}
		this.transport = transport;
		this.clock = clock;
	}

	/** Builds the deep link that opens a bot chat in Telegram. */
	public static String botUrl(String username) {
		if (username == null || username.isBlank()) {
			throw new IllegalArgumentException("username is required");
		}
		return TELEGRAM_WEB + username;
	}

	/** Validates the token locally, then asks Telegram who the bot is. */
	public BotInfo verify(String token) throws TelegramException {
		return transport.getMe(BotTokens.normalize(token));
	}

	/**
	 * Returns the newest pending update id, or {@code 0} when the bot has never received a message.
	 *
	 * <p>Uses {@code offset = -1}, which asks Telegram for only the last update.
	 */
	public long startCursor(String token) throws TelegramException {
		String normalized = BotTokens.normalize(token);
		List<IncomingMessage> updates = transport.getUpdates(normalized, -1, 0);
		return updates.stream()
				.mapToLong(IncomingMessage::updateId)
				.max()
				.orElse(0L);
	}

	/**
	 * Finds the newest private {@code /start} received after {@code afterUpdateId}.
	 *
	 * @throws SetupException when no such message exists yet
	 */
	public Binding bind(String token, String botUsername, long afterUpdateId) throws TelegramException, SetupException {
		if (afterUpdateId < 0) {
			throw new IllegalArgumentException("afterUpdateId must not be negative");
		}
		String normalized = BotTokens.normalize(token);
		List<IncomingMessage> updates = transport.getUpdates(normalized, afterUpdateId + 1, 0);

		Optional<IncomingMessage> newest = updates.stream()
				.filter(message -> message.updateId() > afterUpdateId)
				.filter(IncomingMessage::isPrivate)
				.filter(IncomingMessage::isStartCommand)
				.max(Comparator.comparingLong(IncomingMessage::updateId));

		if (newest.isEmpty()) {
			throw new SetupException(
					SetupException.Reason.NO_START,
					"No Start message yet. Open the bot chat, press Start, then try again.");
		}

		IncomingMessage message = newest.get();
		return new Binding(message.chatId(), message.chatTitle(), message.chatType(), clock.instant().getEpochSecond());
	}

	/** Sends the confirmation message; returns its id so it can be pinned. */
	public long announce(String token, long chatId, String text) throws TelegramException {
		return transport.sendMessage(BotTokens.normalize(token), chatId, text);
	}

	/** Pins the confirmation message. A failure here never invalidates the binding. */
	public void pin(String token, long chatId, long messageId) throws TelegramException {
		transport.pinChatMessage(BotTokens.normalize(token), chatId, messageId);
	}

	/**
	 * Best-effort welcome message for a newly bound chat. The chat already has the user's
	 * {@code /start}; this message is what makes the bot's role and the backup destination obvious
	 * on every device.
	 */
	public long announceBinding(String token, Binding binding, String device, String modVersion)
			throws TelegramException {
		String text = "Cloudgram is connected to this chat.\n\n"
				+ "Worlds you leave in Minecraft will be backed up here.\n"
				+ "Device: " + device + "\n"
				+ "Mod: " + modVersion + "\n\n"
				+ "The bot only reacts to this device; it has no commands to run.";
		long messageId = announce(token, binding.chatId(), text);
		try {
			pin(token, binding.chatId(), messageId);
		} catch (TelegramException e) {
			// Pinning is cosmetic; a private chat may refuse it depending on account settings.
		}
		return messageId;
	}
}
