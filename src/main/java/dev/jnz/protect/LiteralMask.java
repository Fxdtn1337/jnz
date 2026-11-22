package dev.jnz.protect;

import dev.jnz.runtime.StringMask;
import java.util.Base64;

public final class LiteralMask {
    private LiteralMask() {}

    public static String encode(String text, int seed) {
        byte[] bytes = new byte[text.length() * 2];
        for (int i = 0; i < text.length(); i++) {
            char unit = text.charAt(i);
            bytes[2 * i] = (byte) (unit >>> 8);
            bytes[2 * i + 1] = (byte) unit;
        }
        StringMask.apply(bytes, seed);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
