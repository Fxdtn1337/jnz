package dev.jnz.runtime;

import dev.jnz.license.DeviceFingerprint;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.security.MessageDigest;

/** 校验机器绑定和时间，签名验证交给该模块的 JNI 方法。 */
final class LicenseGate {
    private LicenseGate() {}

    static void verify(NativeBridge bridge) throws Exception {
        DataInputStream resource = PackedResources.optional("license.bin");
        if (resource == null) return;
        try (DataInputStream input = resource) {
            long required = input.readLong();
            byte[] key = new byte[65], payload = new byte[56], signature = new byte[64];
            input.readFully(key);
            input.readFully(payload);
            input.readFully(signature);
            if (input.read() != -1) throw new IOException("许可证资源长度错误");
            verifyPayload(payload, required);
            if (!bridge.verify(key, payload, signature)) throw new SecurityException("许可证签名无效");
        }
    }

    private static void verifyPayload(byte[] payload, long required) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
        if (input.readInt() != 0x4a4e5a4c || input.readUnsignedShort() != 1 || input.readUnsignedShort() != 1)
            throw new SecurityException("许可证版本错误");
        byte[] bound = new byte[32];
        input.readFully(bound);
        long expiry = input.readLong(), features = input.readLong();
        if (System.currentTimeMillis() / 1000 >= expiry || (features & required) != required)
            throw new SecurityException("许可证过期或功能未授权");
        if (!MessageDigest.isEqual(bound, DeviceFingerprint.current()))
            throw new SecurityException("许可证机器指纹不匹配");
    }
}
