package dev.jnz.runtime;

import java.util.Base64;

/** 还原编译器写入的字符串字节，保持 Java UTF-16 码元。 */
public final class StringMask {
    private StringMask() {}

    public static String reveal(String text, int seed) {
        byte[] bytes = Base64.getDecoder().decode(text);
        if ((bytes.length & 1) != 0) throw new IllegalArgumentException();
        apply(bytes, seed);
        char[] result = new char[bytes.length / 2];
        for (int i = 0; i < result.length; i++)
            result[i] = (char) ((bytes[2 * i] & 0xff) << 8 | bytes[2 * i + 1] & 0xff);
        return new String(result);
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
