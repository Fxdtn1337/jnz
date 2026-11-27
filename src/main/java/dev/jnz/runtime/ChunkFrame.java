package dev.jnz.runtime;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;

/** 校验帧头和范围，再流式还原一块动态库资源。 */
final class ChunkFrame {
    final int codec;
    final int stored;
    final int decoded;
    final byte[] hash;

    private ChunkFrame(int codec, int stored, int decoded, byte[] hash) {
        this.codec = codec;
        this.stored = stored;
        this.decoded = decoded;
        this.hash = hash;
    }

    static ChunkFrame read(DataInputStream input, int index) throws IOException {
        if (input.readInt() != ModuleFormat.FRAME_MAGIC) throw new IOException("模块分片头校验失败");
        int codec = input.readUnsignedByte();
        if (codec != ModuleFormat.RAW && codec != ModuleFormat.DEFLATE) throw new IOException("未知模块分片编解码");
        if (input.readInt() != index) throw new IOException("模块分片顺序校验失败");
        int decoded = input.readInt();
        int stored = input.readInt();
        if (decoded < 1 || decoded > ModuleFormat.FRAME_BYTES || stored < 1 || stored > ModuleFormat.STORED_BYTES
                || (codec == ModuleFormat.RAW && stored != decoded))
            throw new IOException("模块分片长度校验失败");
        byte[] hash = new byte[ResourceLimits.DIGEST_BYTES];
        input.readFully(hash);
        return new ChunkFrame(codec, stored, decoded, hash);
    }

    void decode(DataInputStream input, OutputStream output, MessageDigest module,
                byte[] plainBuffer, byte[] compressedBuffer) throws IOException {
        DecodedChunk sink = new DecodedChunk(output, module, decoded);
        if (codec == ModuleFormat.RAW) {
            int left = stored;
            while (left > 0) {
                int count = Math.min(left, plainBuffer.length);
                input.readFully(plainBuffer, 0, count);
                sink.write(plainBuffer, count);
                left -= count;
            }
        } else {
            ChunkInflater.decode(input, sink, stored, compressedBuffer, plainBuffer);
        }
        if (input.read() != -1) throw new IOException("模块分片存在多余数据");
        sink.verify(hash);
    }
}
