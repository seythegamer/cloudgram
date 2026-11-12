package dev.cloudgram.core.telegram;

/**
 * The message a chat currently has pinned.
 *
 * <p>Cloudgram uses this as its only discovery channel because the Bot API has no chat history:
 * the pinned catalog document carries the file ids needed to restore every snapshot.
 */
public record PinnedMessage(long messageId, String text, RemoteDocument document) {
	public PinnedMessage {
		if (messageId <= 0) {
			throw new IllegalArgumentException("messageId must be positive");
		}
		if (text != null && text.isBlank()) {
			throw new IllegalArgumentException("text must not be blank when present");
		}
	}
}
