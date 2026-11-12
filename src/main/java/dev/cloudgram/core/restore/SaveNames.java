package dev.cloudgram.core.restore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** Turns a world name from a manifest into a safe, unused save directory name. */
public final class SaveNames {
	private static final int MAX_LENGTH = 60;
	private static final Set<String> RESERVED = Set.of(
			"con", "prn", "aux", "nul",
			"com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
			"lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

	private SaveNames() {
	}

	public static String sanitize(String worldName) {
		String value = worldName == null ? "" : worldName;
		StringBuilder builder = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c < 0x20 || c == '<' || c == '>' || c == ':' || c == '"' || c == '/'
					|| c == '\\' || c == '|' || c == '?' || c == '*') {
				builder.append('_');
			} else {
				builder.append(c);
			}
		}
		String sanitized = builder.toString().trim();
		while (sanitized.endsWith(".") || sanitized.endsWith(" ")) {
			sanitized = sanitized.substring(0, sanitized.length() - 1);
		}
		if (sanitized.isEmpty()) {
			sanitized = "Cloudgram world";
		}
		if (sanitized.length() > MAX_LENGTH) {
			sanitized = sanitized.substring(0, MAX_LENGTH).trim();
		}
		if (RESERVED.contains(sanitized.toLowerCase(Locale.ROOT))) {
			sanitized = "_" + sanitized;
		}
		return sanitized;
	}

	/** Returns a directory below {@code savesDirectory} that does not exist yet. */
	public static Path unique(Path savesDirectory, String worldName) throws IOException {
		if (savesDirectory == null) {
			throw new IllegalArgumentException("savesDirectory is required");
		}
		Files.createDirectories(savesDirectory);
		String base = sanitize(worldName);
		Path candidate = savesDirectory.resolve(base);
		if (!Files.exists(candidate)) {
			return candidate;
		}
		for (int attempt = 2; attempt < 10_000; attempt++) {
			candidate = savesDirectory.resolve(base + " (Cloudgram " + attempt + ")");
			if (!Files.exists(candidate)) {
				return candidate;
			}
		}
		throw new IOException("Could not find a free save name for '" + base + "'");
	}
}
