package dev.cloudgram.core.telegram;

/** The subset of {@code getChat} that Cloudgram needs: identity plus the pinned message. */
public record ChatInfo(long id, String type, String title, PinnedMessage pinnedMessage) {
	public ChatInfo {
		if (id == 0) {
			throw new IllegalArgumentException("id is required");
		}
		if (type != null && type.isBlank()) {
			throw new IllegalArgumentException("type must not be blank when present");
		}
		if (title != null && title.isBlank()) {
			throw new IllegalArgumentException("title must not be blank when present");
		}
	}
}
