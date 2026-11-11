package dev.cloudgram.core;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Writes a {@link PackGroup} as a deterministic ZIP archive.
 *
 * <p>The archive is deliberately boring so that the same input always produces the same bytes:
 * entries are sorted by path, all data is STORED (region files are already compressed internally),
 * timestamps are fixed at 1980-01-01, and no extra fields or comments are emitted. That makes the
 * SHA-256 stable across machines and JDKs, which is what snapshot deduplication relies on.
 *
 * <p>ZIP records are written by hand instead of via {@link java.util.zip.ZipOutputStream} because
 * the JDK converts timestamps through the local time zone, which would make pack hashes depend on
 * the user's clock settings.
 */
public final class PackWriter {
	private static final long LOCAL_HEADER_SIGNATURE = 0x04034b50L;
	private static final long CENTRAL_HEADER_SIGNATURE = 0x02014b50L;
	private static final long END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50L;

	private static final int VERSION_NEEDED = 10;
	private static final int VERSION_MADE_BY = 20;
	private static final int FLAG_UTF8 = 0x0800;
	private static final int METHOD_STORED = 0;
	private static final int DOS_TIME = 0;
	/** 1980-01-01: {@code (year - 1980) << 9 | month << 5 | day}. */
	private static final int DOS_DATE = (1 << 5) | 1;

	private static final int LOCAL_HEADER_SIZE = 30;
	private static final int CENTRAL_HEADER_SIZE = 46;
	private static final int END_OF_CENTRAL_DIRECTORY_SIZE = 22;
	private static final long MAX_UINT32 = 0xFFFFFFFFL;

	private final PackOptions options;
	private final PackSplitter splitter;

	public PackWriter(PackOptions options) {
		if (options == null) {
			throw new IllegalArgumentException("options is required");
		}
		this.options = options;
		this.splitter = new PackSplitter(options.partMaxBytes());
	}

	/**
	 * Writes {@code group} to {@code target} and returns its hash and parts.
	 *
	 * <p>The target file is replaced. On failure a partial file is removed.
	 */
	public BuiltPack write(Path worldRoot, PackGroup group, Path target) throws IOException {
		Path root = worldRoot.toAbsolutePath().normalize();
		List<ScannedFile> files = new ArrayList<>(group.files());
		files.sort(Comparator.comparing(ScannedFile::path));

		if (files.size() > 0xFFFF) {
			throw new IOException("pack '" + group.id() + "' has too many entries: " + files.size());
		}
		if (target.getParent() != null) {
			Files.createDirectories(target.getParent());
		}

		MessageDigest digest = PackSplitter.sha256();
		boolean completed = false;
		try (OutputStream fileOut = Files.newOutputStream(target);
			 DigestOutputStream digestOut = new DigestOutputStream(
					 new BufferedOutputStream(fileOut, 1 << 16), digest)) {
			writeArchive(digestOut, root, files);
			completed = true;
		} finally {
			if (!completed) {
				Files.deleteIfExists(target);
			}
		}

		long size = Files.size(target);
		String sha256 = Hex.encode(digest.digest());
		return new BuiltPack(group.id(), target.toAbsolutePath().normalize(), size, sha256, splitter.split(target));
	}

	private void writeArchive(OutputStream out, Path root, List<ScannedFile> files) throws IOException {
		List<CentralEntry> central = new ArrayList<>(files.size());
		long offset = 0;

		for (ScannedFile file : files) {
			byte[] name = file.path().getBytes(StandardCharsets.UTF_8);
			if (name.length > 0xFFFF) {
				throw new IOException("entry name is too long: " + file.path());
			}

			Path source = WorldPaths.resolve(root, file.path());
			long size = Files.size(source);
			if (size > MAX_UINT32) {
				throw new IOException("file is too large for a pack: " + file.path());
			}
			long crc = crc32(source);

			writeUInt32(out, LOCAL_HEADER_SIGNATURE);
			writeUInt16(out, VERSION_NEEDED);
			writeUInt16(out, FLAG_UTF8);
			writeUInt16(out, METHOD_STORED);
			writeUInt16(out, DOS_TIME);
			writeUInt16(out, DOS_DATE);
			writeUInt32(out, crc);
			writeUInt32(out, size);
			writeUInt32(out, size);
			writeUInt16(out, name.length);
			writeUInt16(out, 0);
			out.write(name);

			copyChecked(source, out, size, crc);

			central.add(new CentralEntry(name, crc, size, offset));
			offset += LOCAL_HEADER_SIZE + name.length + size;
		}

		long centralOffset = offset;
		for (CentralEntry entry : central) {
			writeUInt32(out, CENTRAL_HEADER_SIGNATURE);
			writeUInt16(out, VERSION_MADE_BY);
			writeUInt16(out, VERSION_NEEDED);
			writeUInt16(out, FLAG_UTF8);
			writeUInt16(out, METHOD_STORED);
			writeUInt16(out, DOS_TIME);
			writeUInt16(out, DOS_DATE);
			writeUInt32(out, entry.crc());
			writeUInt32(out, entry.size());
			writeUInt32(out, entry.size());
			writeUInt16(out, entry.name().length);
			writeUInt16(out, 0);
			writeUInt16(out, 0);
			writeUInt16(out, 0);
			writeUInt16(out, 0);
			writeUInt32(out, 0);
			writeUInt32(out, entry.offset());
			out.write(entry.name());
			offset += CENTRAL_HEADER_SIZE + entry.name().length;
		}

		long centralSize = offset - centralOffset;
		if (centralSize > MAX_UINT32 || centralOffset > MAX_UINT32) {
			throw new IOException("pack exceeds the 4 GiB ZIP limit");
		}

		writeUInt32(out, END_OF_CENTRAL_DIRECTORY_SIGNATURE);
		writeUInt16(out, 0);
		writeUInt16(out, 0);
		writeUInt16(out, central.size());
		writeUInt16(out, central.size());
		writeUInt32(out, centralSize);
		writeUInt32(out, centralOffset);
		writeUInt16(out, 0);
	}

	private static long crc32(Path file) throws IOException {
		CRC32 crc = new CRC32();
		byte[] buffer = new byte[1 << 16];
		try (InputStream in = Files.newInputStream(file)) {
			int read;
			while ((read = in.read(buffer)) != -1) {
				crc.update(buffer, 0, read);
			}
		}
		return crc.getValue();
	}

	/** Copies the file while re-checking size and CRC, so a world changing mid-pack fails loudly. */
	private static void copyChecked(Path source, OutputStream out, long expectedSize, long expectedCrc)
			throws IOException {
		CRC32 crc = new CRC32();
		byte[] buffer = new byte[1 << 16];
		long total = 0;
		try (InputStream in = Files.newInputStream(source)) {
			int read;
			while ((read = in.read(buffer)) != -1) {
				out.write(buffer, 0, read);
				crc.update(buffer, 0, read);
				total += read;
			}
		}
		if (total != expectedSize || crc.getValue() != expectedCrc) {
			throw new IOException("file changed while packing: " + source);
		}
	}

	private static void writeUInt16(OutputStream out, int value) throws IOException {
		out.write(value & 0xFF);
		out.write((value >>> 8) & 0xFF);
	}

	private static void writeUInt32(OutputStream out, long value) throws IOException {
		out.write((int) (value & 0xFF));
		out.write((int) ((value >>> 8) & 0xFF));
		out.write((int) ((value >>> 16) & 0xFF));
		out.write((int) ((value >>> 24) & 0xFF));
	}

	private record CentralEntry(byte[] name, long crc, long size, long offset) {
	}
}
