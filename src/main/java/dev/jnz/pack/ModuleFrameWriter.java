package dev.jnz.pack;

import dev.jnz.runtime.DigestCodec;
import dev.jnz.runtime.ModuleFormat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** 将逻辑分片编码为可独立校验的模块帧。 */
final class ModuleFrameWriter {
    private ModuleFrameWriter() {}

    static void validateCodec(String codec) throws IOException {
        if (!"raw".equals(codec) && !"deflate".equals(codec) && !"auto".equals(codec))
            throw new IOException("未知的模块分片编解码 " + codec + "。可选 auto、raw、deflate");
    }

    static byte[] encode(String codec, int index, byte[] plain, int length) throws IOException {
        byte[] compressed = "raw".equals(codec) ? null : deflate(plain, length);
        boolean useDeflate = compressed != null && ("deflate".equals(codec) || compressed.length < length);
        int bodyLength = useDeflate ? compressed.length : length;
        if (bodyLength > ModuleFormat.STORED_BYTES) throw new IOException("编码模块分片长度超限");

        MessageDigest digest = DigestCodec.sha256();
        digest.update(plain, 0, length);
        ByteBuffer frame = ByteBuffer.allocate(ModuleFormat.FRAME_HEADER_BYTES + bodyLength);
        frame.putInt(ModuleFormat.FRAME_MAGIC);
        frame.put((byte) (useDeflate ? ModuleFormat.DEFLATE : ModuleFormat.RAW));
        frame.putInt(index);
        frame.putInt(length);
        frame.putInt(bodyLength);
        frame.put(digest.digest());
        frame.put(useDeflate ? compressed : plain, 0, bodyLength);
        return frame.array();
    }

    private static byte[] deflate(byte[] plain, int length) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(length, 65536));
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try (DeflaterOutputStream stream = new DeflaterOutputStream(bytes, deflater, 16_384)) {
            stream.write(plain, 0, length);
        } finally {
            deflater.end();
        }
        return bytes.toByteArray();
    }
}
