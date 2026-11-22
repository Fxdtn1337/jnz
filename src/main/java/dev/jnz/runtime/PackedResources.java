package dev.jnz.runtime;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/** 统一从 loader 所属 JAR 读取模块路由和资源。 */
final class PackedResources {
    private static final String ROOT = "/" + ResourceNamespace.current();

    private PackedResources() {}

    static byte[] bytes(String name, int limit) throws IOException {
        try (InputStream input = require(name)) {
            return BinaryStream.limited(input, limit, name);
        }
    }

    static InputStream require(String name) throws IOException {
        InputStream input = PackedResources.class.getResourceAsStream(ROOT + name);
        if (input == null) throw new FileNotFoundException(ROOT + name);
        return input;
    }
}
