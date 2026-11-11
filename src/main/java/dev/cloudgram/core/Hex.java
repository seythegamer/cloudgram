package dev.cloudgram.core;

/** Lower-case hex helpers for hashes. */
public final class Hex {
	private static final char[] DIGITS = "0123456789abcdef".toCharArray();

	private Hex() {
	}

	public static String encode(byte[] bytes) {
		char[] chars = new char[bytes.length * 2];
		for (int i = 0; i < bytes.length; i++) {
			int value = bytes[i] & 0xFF;
			chars[i * 2] = DIGITS[value >>> 4];
			chars[i * 2 + 1] = DIGITS[value & 0x0F];
		}
		return new String(chars);
	}

	public static boolean isHex(String value, int length) {
		if (value == null || value.length() != length) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
			if (!hex) {
				return false;
			}
		}
		return true;
	}
}
