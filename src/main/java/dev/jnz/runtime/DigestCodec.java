package dev.jnz.runtime;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class DigestCodec {
    private DigestCodec() {}

    public static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException missing) { throw new AssertionError(missing); }
    }

    public static byte[] hash(byte[] bytes) { return sha256().digest(bytes); }

    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(Character.forDigit((b & 255) >> 4, 16)).append(Character.forDigit(b & 15, 16));
        return out.toString();
    }

    static byte[] unhex(String text) {
        if (text.length() != ResourceLimits.DIGEST_BYTES * 2) throw new IllegalArgumentException("SHA-256 长度错误");
        byte[] result = new byte[ResourceLimits.DIGEST_BYTES];
        for (int i = 0; i < result.length; i++) {
            int a = Character.digit(text.charAt(2 * i), 16), b = Character.digit(text.charAt(2 * i + 1), 16);
            if (a < 0 || b < 0) throw new IllegalArgumentException("SHA-256 格式错误");
            result[i] = (byte) (a * 16 + b);
        }
        return result;
    }
}
