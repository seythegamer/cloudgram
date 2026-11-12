package dev.cloudgram.core.config;

/**
 * Everything Cloudgram remembers between launches.
 *
 * <p>There is exactly one bot per installation: the user's own bot, created in BotFather. The
 * token is stored in plain text in {@code config/cloudgram.json} (see the README for the security
 * note); it never leaves this machine except as an Authorization path segment to
 * {@code api.telegram.org}.
 *
 * <p>{@code chatId} is empty until the user presses Start in the bot chat and the mod binds to it.
 * A config file is forward-compatible: unknown fields are ignored, but the {@code format} tag is
 * checked so a future version never silently misreads the file.
 *
 * <p>{@code encryptionKey} is the URL-safe base64 recovery key used to encrypt every uploaded
 * object. It is generated once, at startup or when the first chat is bound, and is independent of
 * the bot token so Telegram can never derive it.
 */
public record CloudgramConfig(
		String format,
		String botToken,
		String botUsername,
		Long chatId,
		String chatTitle,
		long boundAt,
		boolean backupOnExit,
		String encryptionKey) {

	public static final String CURRENT_FORMAT = "cloudgram/config/1";

	public CloudgramConfig {
		if (format == null || format.isBlank()) {
			throw new IllegalArgumentException("format is required");
		}
		if (botToken != null && botToken.isBlank()) {
			throw new IllegalArgumentException("botToken must not be blank when present");
		}
		if (botUsername != null && botUsername.isBlank()) {
			throw new IllegalArgumentException("botUsername must not be blank when present");
		}
		if (chatId != null && chatId <= 0) {
			throw new IllegalArgumentException("chatId must be positive when present");
		}
		if (chatTitle != null && chatTitle.isBlank()) {
			throw new IllegalArgumentException("chatTitle must not be blank when present");
		}
		if (boundAt < 0) {
			throw new IllegalArgumentException("boundAt must not be negative");
		}
		if (encryptionKey != null && encryptionKey.isBlank()) {
			throw new IllegalArgumentException("encryptionKey must not be blank when present");
		}
	}

	/** Keeps configs written before encryption existed readable. */
	public CloudgramConfig(
			String format,
			String botToken,
			String botUsername,
			Long chatId,
			String chatTitle,
			long boundAt,
			boolean backupOnExit) {
		this(format, botToken, botUsername, chatId, chatTitle, boundAt, backupOnExit, null);
	}

	/** A config for a fresh installation: no bot, no chat, no key, backups enabled. */
	public static CloudgramConfig empty() {
		return new CloudgramConfig(CURRENT_FORMAT, null, null, null, null, 0L, true, null);
	}

	public boolean hasBot() {
		return botToken != null;
	}

	public boolean isBound() {
		return chatId != null;
	}

	/**
	 * Returns a copy that remembers {@code token} and the bot's username.
	 *
	 * <p>When the token changes, the previous chat binding is dropped: a chat id is only
	 * meaningful to the bot that received the Start message.
	 */
	public CloudgramConfig withBot(String token, String username) {
		if (token == null || token.isBlank()) {
			throw new IllegalArgumentException("token is required");
		}
		if (username == null || username.isBlank()) {
			throw new IllegalArgumentException("username is required");
		}
		boolean sameBot = token.equals(botToken);
		return new CloudgramConfig(
				CURRENT_FORMAT,
				token,
				username,
				sameBot ? chatId : null,
				sameBot ? chatTitle : null,
				sameBot ? boundAt : 0L,
				backupOnExit,
				encryptionKey);
	}

	/** Returns a copy bound to {@code chatId}. */
	public CloudgramConfig withBinding(long chatId, String title, long boundAt) {
		if (chatId <= 0) {
			throw new IllegalArgumentException("chatId must be positive");
		}
		if (title == null || title.isBlank()) {
			throw new IllegalArgumentException("title is required");
		}
		if (boundAt < 0) {
			throw new IllegalArgumentException("boundAt must not be negative");
		}
		return new CloudgramConfig(
				CURRENT_FORMAT, botToken, botUsername, chatId, title, boundAt, backupOnExit, encryptionKey);
	}

	public CloudgramConfig withBackupOnExit(boolean enabled) {
		return new CloudgramConfig(
				CURRENT_FORMAT, botToken, botUsername, chatId, chatTitle, boundAt, enabled, encryptionKey);
	}

	/** Returns a copy carrying the base64 recovery key; an existing key is replaced only here. */
	public CloudgramConfig withEncryptionKey(String key) {
		if (key == null || key.isBlank()) {
			throw new IllegalArgumentException("key is required");
		}
		return new CloudgramConfig(
				CURRENT_FORMAT, botToken, botUsername, chatId, chatTitle, boundAt, backupOnExit, key);
	}

	public boolean hasEncryptionKey() {
		return encryptionKey != null && !encryptionKey.isBlank();
	}
}
