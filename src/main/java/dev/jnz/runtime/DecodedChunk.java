package dev.jnz.runtime;

import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;

/** 在写出每段数据前核对解码上限，同时更新帧及模块摘要。 */
final class DecodedChunk {
    private final OutputStream output;
    private final MessageDigest module;
    private final MessageDigest frame = DigestCodec.sha256();
    private int remaining;

    DecodedChunk(OutputStream output, MessageDigest module, int length) {
        this.output = output;
        this.module = module;
        remaining = length;
    }

    void write(byte[] buffer, int count) throws IOException {
        if (count > remaining) throw new IOException("模块分片超出解码长度");
        remaining -= count;
        frame.update(buffer, 0, count);
        module.update(buffer, 0, count);
        output.write(buffer, 0, count);
    }

    void verify(byte[] expected) throws IOException {
        if (remaining != 0 || !MessageDigest.isEqual(expected, frame.digest()))
            throw new IOException("模块分片 SHA-256 或解码长度校验失败");
    }
}
