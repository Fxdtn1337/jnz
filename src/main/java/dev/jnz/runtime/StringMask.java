package dev.jnz.runtime;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** 还原编译器写入的字符串字节，保持 Java UTF-16 码元。 */
public final class StringMask {
    private StringMask() {}

    public static String reveal(String text, int seed) {
        boolean authenticated = text.startsWith("A:");
        byte[] bytes = Base64.getDecoder().decode(authenticated ? text.substring(2) : text);
        if (authenticated) bytes = decrypt(bytes, seed);
        else apply(bytes, seed);
        try {
            if ((bytes.length & 1) != 0) throw new IllegalArgumentException("invalid UTF-16 literal");
            char[] result = new char[bytes.length / 2];
            for (int i = 0; i < result.length; i++) {
                result[i] = (char) ((bytes[2 * i] & 0xff) << 8 | bytes[2 * i + 1] & 0xff);
            }
            return new String(result);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static byte[] decrypt(byte[] packed, int seed) {
        if (packed.length < 44) throw new IllegalArgumentException("invalid AES literal");
        byte[] key = Arrays.copyOfRange(packed, 12, 28);
        try {
            apply(key, seed);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, packed, 0, 12));
            return cipher.doFinal(packed, 28, packed.length - 28);
        } catch (GeneralSecurityException error) {
            throw new IllegalArgumentException("invalid AES literal", error);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    public static void apply(byte[] bytes, int seed) {
        int word = seed;
        for (int i = 0; i < bytes.length; i++) {
            word ^= word << 13;
            word ^= word >>> 17;
            word ^= word << 5;
            bytes[i] ^= (byte) word;
        }
    }
}
