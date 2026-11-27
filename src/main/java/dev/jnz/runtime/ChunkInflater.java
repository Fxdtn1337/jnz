package dev.jnz.runtime;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** 只接受单个完整 zlib 流，拒绝字典、拼接流、尾随字节与提前结束。 */
final class ChunkInflater {
    private ChunkInflater() {}

    static void decode(DataInputStream input, DecodedChunk sink, int length,
                       byte[] compressed, byte[] plain) throws IOException {
        Inflater inflater = new Inflater();
        int remaining = length;
        try {
            while (!inflater.finished()) {
                if (inflater.needsDictionary()) throw new IOException("DEFLATE 分片需要不支持的字典");
                if (inflater.needsInput()) {
                    if (remaining == 0) throw new IOException("DEFLATE 分片未完整结束");
                    int count = Math.min(compressed.length, remaining);
                    input.readFully(compressed, 0, count);
                    inflater.setInput(compressed, 0, count);
                    remaining -= count;
                }
                int count = inflater.inflate(plain);
                if (count != 0) sink.write(plain, count);
                else if (!inflater.finished() && !inflater.needsInput() && !inflater.needsDictionary())
                    throw new IOException("DEFLATE 分片无法继续解码");
            }
            if (remaining != 0 || inflater.getRemaining() != 0)
                throw new IOException("DEFLATE 分片存在多余的编码数据");
        } catch (DataFormatException malformed) {
            throw new IOException("DEFLATE 分片校验失败", malformed);
        } finally {
            inflater.end();
        }
    }
}
