package dev.cloudgram.core.setup;

/** A Telegram chat that has been bound to this installation. */
public record Binding(long chatId, String title, String chatType, long boundAt) {
	public Binding {
		if (chatId <= 0) {
			throw new IllegalArgumentException("chatId must be positive");
		}
		if (title == null || title.isBlank()) {
			throw new IllegalArgumentException("title is required");
		}
		chatType = chatType == null || chatType.isBlank() ? "private" : chatType;
		if (boundAt < 0) {
			throw new IllegalArgumentException("boundAt must not be negative");
		}
	}
}
