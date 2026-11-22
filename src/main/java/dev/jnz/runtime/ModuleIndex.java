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

    static ModuleIndex read(String prefix) throws IOException {
        try (DataInputStream input = PackedResources.require(prefix + "index.bin")) {
            if (input.readInt() != 0x4a4e5a31) throw new IOException("未知模块索引版本");
            int count = input.readInt();
            long size = input.readLong();
            byte[] digest = new byte[32];
            input.readFully(digest);
            if (input.read() != -1 || count < 1 || size < count) throw new IOException("模块索引损坏");
            return new ModuleIndex(count, size, digest);
        }
    }
}
