package dev.cloudgram.core.catalog;

import dev.cloudgram.core.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * A local copy of the last catalog this installation published.
 *
 * <p>The pinned message is the only discovery channel the Bot API offers, so an accidental unpin
 * would make every snapshot that came before it invisible. The next upload would then publish a
 * catalog containing only the new snapshot. This mirror lets that upload start from the previous
 * catalog instead, which restores the old entries because their index documents are still in the
 * chat.
 *
 * <p>The chat id is stored with the catalog: entries carry file ids that only exist in one chat, so
 * a mirror written for another bot chat is never reused. The file holds no token and no key.
 */
public record CatalogMirror(String format, long chatId, Catalog catalog) {
	public static final String CURRENT_FORMAT = "cloudgram/catalog-mirror/1";
	public static final String FILE_NAME = "catalog.json";

	public CatalogMirror {
		if (format == null || !CURRENT_FORMAT.equals(format)) {
			throw new IllegalArgumentException("unsupported catalog mirror format: " + format);
		}
		if (chatId <= 0) {
			throw new IllegalArgumentException("chatId must be positive");
		}
		if (catalog == null) {
			throw new IllegalArgumentException("catalog is required");
		}
	}

	/**
	 * Reads the mirror when it belongs to {@code chatId}.
	 *
	 * @return the mirror, or {@code null} when it is missing, unreadable, or from another chat
	 */
	public static CatalogMirror load(Path file, long chatId) throws IOException {
		if (file == null) {
			throw new IllegalArgumentException("file is required");
		}
		if (!Files.isRegularFile(file)) {
			return null;
		}
		CatalogMirror mirror;
		try {
			mirror = Json.gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), CatalogMirror.class);
		} catch (RuntimeException e) {
			throw new IOException("catalog mirror is not valid JSON: " + file, e);
		}
		if (mirror == null || !CURRENT_FORMAT.equals(mirror.format()) || mirror.catalog() == null) {
			return null;
		}
		return mirror.chatId() == chatId ? mirror : null;
	}

	/** Writes the mirror atomically, replacing any previous copy. */
	public void save(Path file) throws IOException {
		if (file == null) {
			throw new IllegalArgumentException("file is required");
		}
		Files.createDirectories(file.getParent());
		Path temp = file.resolveSibling(FILE_NAME + ".tmp");
		Files.writeString(temp, Json.gson().toJson(this) + "\n", StandardCharsets.UTF_8);
		try {
			Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
