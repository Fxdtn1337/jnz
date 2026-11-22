package dev.jnz.runtime;

import java.io.DataInputStream;
import java.io.IOException;

/** 固定长模块目录，包含拼接长度和 SHA-256。 */
final class ModuleIndex {
    final int blocks;
    final long length;
    final byte[] digest;

    private ModuleIndex(int blocks, long length, byte[] digest) {
        this.blocks = blocks;
        this.length = length;
        this.digest = digest;
    }

    static ModuleIndex read(String name) throws IOException {
        try (DataInputStream input = MetadataCatalog.open(name)) {
            if (input.readInt() != 0x4a4e5a31) throw new IOException("未知模块索引版本");
            int count = input.readInt();
            long size = input.readLong();
            byte[] digest = new byte[ResourceLimits.DIGEST_BYTES];
            input.readFully(digest);
            BinaryStream.end(input, name);
            if (count < 1 || count > ResourceLimits.MODULE_BLOCKS || size < count || size > ResourceLimits.MODULE_BYTES)
                throw new IOException("模块索引损坏");
            return new ModuleIndex(count, size, digest);
        }
    }
}
