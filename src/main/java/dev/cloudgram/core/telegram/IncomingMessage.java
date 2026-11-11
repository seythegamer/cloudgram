package dev.cloudgram.core.telegram;

/**
 * One message that arrived in the bot's chat, as returned by {@code getUpdates}.
 *
 * <p>Only the fields the setup wizard needs are kept; the rest of Telegram's update object is
 * intentionally ignored so a schema change cannot break binding.
 */
public record IncomingMessage(
		long updateId,
		long messageId,
		long chatId,
		String chatType,
		String chatTitle,
		String text,
		long date) {

	public IncomingMessage {
		if (updateId < 0) {
			throw new IllegalArgumentException("updateId must not be negative");
		}
		if (messageId <= 0) {
			throw new IllegalArgumentException("messageId must be positive");
		}
		if (chatId == 0) {
			throw new IllegalArgumentException("chatId must not be zero");
		}
		chatType = chatType == null ? "unknown" : chatType;
		text = text == null ? "" : text;
	}

	public boolean isPrivate() {
		return "private".equals(chatType);
	}

	public boolean isStartCommand() {
		String trimmed = text.trim();
		return trimmed.equals("/start") || trimmed.startsWith("/start ");
	}
}
