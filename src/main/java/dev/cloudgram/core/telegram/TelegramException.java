package dev.cloudgram.core.telegram;

/** A failure reported by Telegram or by the transport that talks to it. */
public final class TelegramException extends Exception {
	private final int code;
	private final int retryAfterSeconds;

	/** A transport failure: DNS, TLS, timeout, connection reset. */
	public TelegramException(String message, Throwable cause) {
		super(message, cause);
		this.code = 0;
		this.retryAfterSeconds = 0;
	}

	public TelegramException(String message) {
		super(message);
		this.code = 0;
		this.retryAfterSeconds = 0;
	}

	public TelegramException(int code, String description, int retryAfterSeconds) {
		super("Telegram API error " + code + ": " + description);
		this.code = code;
		this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
	}

	public int code() {
		return code;
	}

	public boolean isUnauthorized() {
		return code == 401;
	}

	public boolean isRateLimited() {
		return code == 429;
	}

	public int retryAfterSeconds() {
		return retryAfterSeconds;
	}
}
