package dev.cloudgram.core.upload;

import dev.cloudgram.core.Hex;
import dev.cloudgram.core.PackPart;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Reads one byte-range part out of a pack and checks it against the hash recorded when the pack
 * was split.
 *
 * <p>Parts are never copied on disk: this is the only place that materialises one, immediately
 * before it is handed to the transport. A mismatch aborts the upload instead of publishing
 * corrupt data.
 */
public final class PartReader {
	private PartReader() {
	}

	public static byte[] read(Path packFile, PackPart part) throws IOException {
		if (packFile == null) {
			throw new IllegalArgumentException("packFile is required");
		}
		if (part == null) {
			throw new IllegalArgumentException("part is required");
		}
		if (part.size() > Integer.MAX_VALUE - 8) {
			throw new IOException("part is too large to address in memory: " + part.size() + " bytes");
		}

		long fileSize = Files.size(packFile);
		long end = part.offset() + part.size();
		if (end < part.offset() || end > fileSize) {
			throw new IOException("part " + part.index() + " lies outside " + packFile
					+ " (offset " + part.offset() + ", size " + part.size() + ", file " + fileSize + ")");
		}

		byte[] bytes = new byte[(int) part.size()];
		try (FileChannel channel = FileChannel.open(packFile, StandardOpenOption.READ)) {
			ByteBuffer buffer = ByteBuffer.wrap(bytes);
			long position = part.offset();
			while (buffer.hasRemaining()) {
				int read = channel.read(buffer, position);
				if (read < 0) {
					throw new IOException("unexpected end of pack file: " + packFile);
				}
				if (read == 0) {
					continue;
				}
				position += read;
			}
		}

		String actual = Hex.encode(sha256(bytes));
		if (!actual.equals(part.sha256())) {
			throw new IOException("part " + part.index() + " of " + packFile
					+ " does not match its recorded hash");
		}
		return bytes;
	}

	private static byte[] sha256(byte[] bytes) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}
}
