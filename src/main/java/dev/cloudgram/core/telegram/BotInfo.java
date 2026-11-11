package dev.cloudgram.core.telegram;

/** The result of {@code getMe}. */
public record BotInfo(long id, String username, String firstName) {
	public BotInfo {
		if (id <= 0) {
			throw new IllegalArgumentException("id must be positive");
		}
		if (username == null || username.isBlank()) {
			throw new IllegalArgumentException("username is required");
		}
		firstName = firstName == null ? username : firstName;
	}

	public String displayName() {
		return "@" + username;
	}
}
