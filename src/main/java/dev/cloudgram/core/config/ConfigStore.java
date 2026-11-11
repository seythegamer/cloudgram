package dev.cloudgram.core.config;

import dev.cloudgram.core.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Reads and writes {@code cloudgram.json} atomically.
 *
 * <p>The file contains a bot token, so writes try to restrict the file to the current user on
 * POSIX systems. Windows has no portable equivalent through the NIO API; that limitation is
 * documented in the README and is planned to be replaced by DPAPI.
 */
public final class ConfigStore {
	public static final String FILE_NAME = "cloudgram.json";
	private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

	private final Path file;

	public ConfigStore(Path file) {
		if (file == null) {
			throw new IllegalArgumentException("file is required");
		}
		this.file = file.toAbsolutePath().normalize();
	}

	public static ConfigStore inDirectory(Path configDir) {
		if (configDir == null) {
			throw new IllegalArgumentException("configDir is required");
		}
		return new ConfigStore(configDir.resolve(FILE_NAME));
	}

	public Path file() {
		return file;
	}

	public boolean exists() {
		return Files.isRegularFile(file);
	}

	/** Reads the config, returning {@link CloudgramConfig#empty()} when the file does not exist. */
	public CloudgramConfig load() throws IOException {
		if (!Files.isRegularFile(file)) {
			return CloudgramConfig.empty();
		}

		CloudgramConfig config;
		String json = Files.readString(file, StandardCharsets.UTF_8);
		try {
			config = Json.gson().fromJson(json, CloudgramConfig.class);
		} catch (RuntimeException e) {
			throw new IOException("config is not valid JSON: " + file, e);
		}
		if (config == null) {
			throw new IOException("config is empty: " + file);
		}
		if (!CloudgramConfig.CURRENT_FORMAT.equals(config.format())) {
			throw new IOException("unsupported config format '" + config.format() + "' in " + file);
		}
		return config;
	}

	/** Writes the config atomically, creating the parent directory if needed. */
	public void save(CloudgramConfig config) throws IOException {
		if (config == null) {
			throw new IllegalArgumentException("config is required");
		}
		Path parent = file.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}

		Path temp = file.resolveSibling(FILE_NAME + ".tmp");
		Files.writeString(temp, Json.gson().toJson(config) + "\n", StandardCharsets.UTF_8);
		restrictToOwner(temp);
		try {
			Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Best effort: unsupported platforms (Windows) simply keep their inherited ACLs. */
	private static void restrictToOwner(Path path) {
		try {
			Files.setPosixFilePermissions(path, OWNER_ONLY);
		} catch (UnsupportedOperationException | IOException ignored) {
			// Not a POSIX filesystem.
		}
	}
}
