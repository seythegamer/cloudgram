package dev.cloudgram.core.setup;

/** A problem in the setup flow rather than in the Telegram API itself. */
public final class SetupException extends Exception {
	public enum Reason {
		/** No {@code /start} message arrived in a private chat after the token was verified. */
		NO_START
	}

	private final Reason reason;

	public SetupException(Reason reason, String message) {
		super(message);
		if (reason == null) {
			throw new IllegalArgumentException("reason is required");
		}
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}
}
