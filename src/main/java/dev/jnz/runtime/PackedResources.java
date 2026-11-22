package dev.jnz.runtime;

import java.io.DataInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/** 统一从 loader 所属 JAR 读取模块路由和资源。 */
final class PackedResources {
    private static final String ROOT = root();

    private PackedResources() {}

    private static String root() {
        return "/META-INF/jnz/";
    }

    static int groupOf(int classId) throws IOException {
        try (DataInputStream input = require("classes/" + classId)) {
            int group = input.readInt();
            if (group < 0 || input.read() != -1) throw new IOException("无效的类分组索引");
            return group;
        }
    }

    static String groupBinding(int group) throws IOException {
        try (DataInputStream input = require("groups/" + group)) {
            int seed = input.readInt();
            String encoded = input.readUTF();
            if (input.read() != -1) throw new IOException("无效的 JNI 桥接索引");
            return StringMask.reveal(encoded, seed);
        } catch (IllegalArgumentException error) {
            throw new IOException("无效的 JNI 桥接索引", error);
        }
    }

    static DataInputStream optional(String name) {
        InputStream raw = PackedResources.class.getResourceAsStream(ROOT + name);
        return raw == null ? null : new DataInputStream(raw);
    }

    static DataInputStream require(String name) throws IOException {
        DataInputStream input = optional(name);
        if (input == null) throw new FileNotFoundException(ROOT + name);
        return input;
    }
}
