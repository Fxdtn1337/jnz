package dev.jnz.runtime;

import java.io.DataInputStream;
import java.io.IOException;

final class CatalogRecord {
    final String path;
    final byte[] digest;

    private CatalogRecord(String path, byte[] digest) {
        this.path = path;
        this.digest = digest;
    }

    static CatalogRecord read(DataInputStream input) throws IOException {
        String path = input.readUTF();
        if (!path.matches("[a-zA-Z0-9/_.-]+") || path.startsWith("/") || path.contains(".."))
            throw new IOException("目录条目路径非法");
        byte[] hash = new byte[ResourceLimits.DIGEST_BYTES];
        input.readFully(hash);
        return new CatalogRecord(path, hash);
    }
}
