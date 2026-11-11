package dev.cloudgram.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a pack into byte-range parts and hashes each range.
 *
 * <p>Parts are not copies: the descriptors carry offsets into the pack, so a part can be uploaded
 * by streaming that range. Concatenating the parts in index order rebuilds the pack exactly.
 */
public final class PackSplitter {
	private final long partMaxBytes;

	public PackSplitter(long partMaxBytes) {
		if (partMaxBytes <= 0) {
			throw new IllegalArgumentException("partMaxBytes must be positive");
		}
		this.partMaxBytes = partMaxBytes;
	}

	public long partMaxBytes() {
		return partMaxBytes;
	}

	public List<PackPart> split(Path packFile) throws IOException {
		long size = Files.size(packFile);
		if (size == 0) {
			return List.of();
		}

		List<PackPart> parts = new ArrayList<>();
		int bufferSize = (int) Math.min(partMaxBytes, 1 << 20);

		try (FileChannel channel = FileChannel.open(packFile, StandardOpenOption.READ)) {
			ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
			long offset = 0;
			int index = 0;

			while (offset < size) {
				long length = Math.min(partMaxBytes, size - offset);
				MessageDigest digest = sha256();
				long position = offset;
				long remaining = length;

				while (remaining > 0) {
					buffer.clear();
					buffer.limit((int) Math.min(buffer.capacity(), remaining));
					int read = channel.read(buffer, position);
					if (read < 0) {
						throw new IOException("unexpected end of pack file: " + packFile);
					}
					if (read == 0) {
						continue;
					}
					buffer.flip();
					digest.update(buffer);
					position += read;
					remaining -= read;
				}

				parts.add(new PackPart(index++, offset, length, Hex.encode(digest.digest())));
				offset += length;
			}
		}

		return List.copyOf(parts);
	}

	static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}
}
