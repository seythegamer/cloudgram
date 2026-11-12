package dev.cloudgram.core.telegram;

import java.util.List;

/**
 * The subset of the Telegram Bot API that Cloudgram uses.
 *
 * <p>Kept as an interface so the setup flow, the upload queue and the restore path can be tested
 * without a network, and so a future backend (another Telegram-compatible host, or a local stub)
 * only has to implement these calls.
 */
public interface TelegramTransport {
	/** {@code getMe}: verifies the token and returns the bot identity. */
	BotInfo getMe(String token) throws TelegramException;

	/**
	 * {@code getUpdates} with long polling disabled or bounded by {@code timeoutSeconds}.
	 *
	 * <p>Pass {@code offset = -1} to fetch the most recent pending update, and
	 * {@code offset = <id> + 1} to confirm everything up to {@code id}.
	 */
	List<IncomingMessage> getUpdates(String token, long offset, int timeoutSeconds) throws TelegramException;

	/** Sends a plain text message and returns its {@code message_id}. */
	long sendMessage(String token, long chatId, String text) throws TelegramException;

	/**
	 * Uploads one in-memory document with {@code sendDocument}.
	 *
	 * <p>Cloudgram only sends packs that were already split into byte-range parts (at most 19 MiB)
	 * and the small manifest, so a byte array keeps the transport independent of temporary files
	 * and lets callers verify a part's hash before it leaves the machine.
	 */
	UploadedDocument sendDocument(String token, long chatId, String fileName, byte[] content, String caption)
			throws TelegramException;

	/** Pins a message in a chat without producing a notification. */
	void pinChatMessage(String token, long chatId, long messageId) throws TelegramException;
}
