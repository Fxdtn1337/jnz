package dev.jnz.protect;

import dev.jnz.runtime.StringMask;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class LiteralMask {
    private LiteralMask() {}

    public static String encode(String text, int seed) {
        byte[] bytes = codeUnits(text);
        StringMask.apply(bytes, seed);
        return Base64.getEncoder().encodeToString(bytes);
    }

    public static String encodeAes(String text, int seed, SecureRandom random) throws GeneralSecurityException {
        byte[] key = new byte[16];
        byte[] nonce = new byte[12];
        byte[] clear = codeUnits(text);
        random.nextBytes(key);
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            byte[] ciphertext = cipher.doFinal(clear);
            byte[] packed = new byte[nonce.length + key.length + ciphertext.length];
            System.arraycopy(nonce, 0, packed, 0, nonce.length);
            StringMask.apply(key, seed);
            System.arraycopy(key, 0, packed, nonce.length, key.length);
            System.arraycopy(ciphertext, 0, packed, nonce.length + key.length, ciphertext.length);
            return "A:" + Base64.getEncoder().encodeToString(packed);
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(clear, (byte) 0);
        }
    }

    private static byte[] codeUnits(String text) {
        byte[] bytes = new byte[text.length() * 2];
        for (int i = 0; i < text.length(); i++) {
            char unit = text.charAt(i);
            bytes[2 * i] = (byte) (unit >>> 8);
            bytes[2 * i + 1] = (byte) unit;
        }
        return bytes;
    }
}
