package dev.cloudgram.core.telegram;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validation and safe display of bot tokens, without ever logging the secret part. */
public final class BotTokens {
	private static final Pattern TOKEN = Pattern.compile("(\\d{1,20}):([A-Za-z0-9_-]{20,})");

	private BotTokens() {
	}

	/**
	 * Trims surrounding whitespace and checks the structure {@code <botId>:<secret>}.
	 *
	 * @throws IllegalArgumentException when the value cannot be a bot token
	 */
	public static String normalize(String raw) {
		if (raw == null) {
			throw new IllegalArgumentException("Bot token is empty.");
		}
		String token = raw.trim();
		if (token.isEmpty()) {
			throw new IllegalArgumentException("Bot token is empty.");
		}
		if (!TOKEN.matcher(token).matches()) {
			throw new IllegalArgumentException(
					"That does not look like a bot token. It should look like 123456789:AA...AA.");
		}
		return token;
	}

	public static boolean isValid(String raw) {
		return raw != null && TOKEN.matcher(raw.trim()).matches();
	}

	/** The numeric part before the colon; throws for malformed input. */
	public static long botId(String raw) {
		Matcher matcher = TOKEN.matcher(normalize(raw));
		matcher.matches();
		return Long.parseLong(matcher.group(1));
	}

	/**
	 * A value safe for logs and error messages: the bot id stays, the secret becomes
	 * {@code <redacted>}.
	 */
	public static String redact(String raw) {
		if (raw == null) {
			return "<no token>";
		}
		String token = raw.trim();
		int colon = token.indexOf(':');
		if (colon <= 0 || colon == token.length() - 1) {
			return "<invalid token>";
		}
		return token.substring(0, colon) + ":<redacted>";
	}
}
