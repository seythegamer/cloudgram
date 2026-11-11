package dev.cloudgram.core;

import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Stable identity of a world, stored inside the world directory.
 *
 * <p>Worlds are never identified by folder name: users rename folders, copy saves and restore
 * snapshots. The UUID in {@code .cloudgram/world.json} is created once and then travels with the
 * world, including through backups.
 */
public record WorldIdentity(String format, String id, long createdAt, String origin) {
	public static final String CURRENT_FORMAT = "cloudgram/world/1";
	public static final String IDENTITY_PATH = ".cloudgram/world.json";

	public static final String ORIGIN_LOCAL = "local";

	public static WorldIdentity create(Instant createdAt) {
		return new WorldIdentity(CURRENT_FORMAT, UUID.randomUUID().toString(), createdAt.getEpochSecond(), ORIGIN_LOCAL);
	}

	/** Reads the identity, generating and persisting one if the world has none yet. */
	public static WorldIdentity loadOrCreate(Path worldRoot) throws IOException {
		Optional<WorldIdentity> existing = read(worldRoot);
		if (existing.isPresent()) {
			return existing.get();
		}
		WorldIdentity created = create(Instant.now());
		created.write(worldRoot);
		return created;
	}

	/** Reads the identity if present. A malformed file is an error, never silently replaced. */
	public static Optional<WorldIdentity> read(Path worldRoot) throws IOException {
		Path file = identityFile(worldRoot);
		if (!Files.isRegularFile(file)) {
			return Optional.empty();
		}

		WorldIdentity identity;
		try {
			identity = Json.gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), WorldIdentity.class);
		} catch (JsonParseException e) {
			throw new IOException("world identity is not valid JSON: " + file, e);
		}

		if (identity == null) {
			throw new IOException("world identity is empty: " + file);
		}
		if (!CURRENT_FORMAT.equals(identity.format())) {
			throw new IOException("unsupported world identity format '" + identity.format() + "' in " + file);
		}
		if (identity.id() == null) {
			throw new IOException("world identity has no id: " + file);
		}
		try {
			UUID.fromString(identity.id());
		} catch (IllegalArgumentException e) {
			throw new IOException("world identity id is not a UUID: " + identity.id(), e);
		}
		return Optional.of(identity);
	}

	/** Writes the identity atomically, replacing any previous file. */
	public void write(Path worldRoot) throws IOException {
		Path file = identityFile(worldRoot);
		Files.createDirectories(file.getParent());
		Path temp = file.resolveSibling("world.json.tmp");
		Files.writeString(temp, Json.gson().toJson(this) + "\n", StandardCharsets.UTF_8);
		try {
			Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public static Path identityFile(Path worldRoot) {
		return worldRoot.resolve(IDENTITY_PATH);
	}
}
