package dev.jnz.runtime;

import java.io.DataInputStream;
import java.io.IOException;

/** 固定长模块索引，认证解码后的动态库和存储帧流。 */
final class ModuleIndex {
    final int blocks;
    final long length;
    final byte[] digest;
    final byte[] storedDigest;

    private ModuleIndex(int blocks, long length, byte[] digest, byte[] storedDigest) {
        this.blocks = blocks;
        this.length = length;
        this.digest = digest;
        this.storedDigest = storedDigest;
    }

    static ModuleIndex read(String name) throws IOException {
        try (DataInputStream input = MetadataCatalog.open(name)) {
            if (input.readInt() != ModuleFormat.INDEX_MAGIC) throw new IOException("未知模块索引版本");
            int count = input.readInt();
            long size = input.readLong();
            byte[] digest = new byte[ResourceLimits.DIGEST_BYTES];
            byte[] storedDigest = new byte[ResourceLimits.DIGEST_BYTES];
            input.readFully(digest);
            input.readFully(storedDigest);
            BinaryStream.end(input, name);
            if (count < 1 || count > ResourceLimits.MODULE_BLOCKS || size < count || size > ResourceLimits.MODULE_BYTES
                    || size > (long) count * ModuleFormat.FRAME_BYTES)
                throw new IOException("模块索引损坏");
            return new ModuleIndex(count, size, digest, storedDigest);
        }
    }
}
