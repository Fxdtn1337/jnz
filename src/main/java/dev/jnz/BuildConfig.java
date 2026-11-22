package dev.jnz;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** 按 Java properties 格式读取配置。文件内的相对路径以配置文件所在目录为基准。 */
public final class BuildConfig {
    private static final Set<String> INSPECT_OPTIONS = names("input", "include", "exclude", "report", "strict");
    private static final Set<String> BUILD_OPTIONS = names("input", "include", "exclude", "report", "strict",
            "output", "targets", "shard-size", "module-size", "work-dir", "zig", "rustc", "vm-coverage",
            "vm-include", "jni-bridges", "pre-obfuscation", "native-backend", "flow-profile", "string-cipher");
    private static final Set<String> PATH_OPTIONS = names("input", "output", "report", "work-dir", "rustc", "zig");

    private BuildConfig() {}

    public static Map<String, String> read(Path file) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        Map<String, String> values = new LinkedHashMap<>();
        Properties properties = new Properties() {
            @Override
            public synchronized Object put(Object key, Object value) {
                String name = (String) key;
                if (!BUILD_OPTIONS.contains(name)) throw new IllegalArgumentException("未知配置项 " + name);
                if (values.putIfAbsent(name, (String) value) != null)
                    throw new IllegalArgumentException("重复的配置项 " + name);
                return null;
            }
        };
        try (BufferedReader reader = Files.newBufferedReader(absolute, StandardCharsets.UTF_8)) {
            reader.mark(1);
            if (reader.read() != '\ufeff') reader.reset();
            properties.load(reader);
            for (Map.Entry<String, String> entry : values.entrySet()) {
                String name = entry.getKey();
                String value = entry.getValue();
                if (PATH_OPTIONS.contains(name)) {
                    if (value.trim().isEmpty()) throw new IllegalArgumentException("路径不能为空 " + name);
                    try {
                        entry.setValue(absolute.getParent().resolve(Paths.get(value)).normalize().toString());
                    } catch (InvalidPathException e) {
                        throw new IllegalArgumentException("无效路径 " + name, e);
                    }
                } else if (name.equals("strict")) {
                    entry.setValue(Boolean.toString(parseBoolean(name, value)));
                } else {
                    validateChoice(name, value);
                }
            }
        } catch (IllegalArgumentException error) {
            throw new IOException("配置文件 " + absolute + " 中的 " + error.getMessage(), error);
        } catch (IOException error) {
            throw new IOException("无法读取配置文件 " + absolute + "，" + error.getMessage(), error);
        }
        return Collections.unmodifiableMap(values);
    }

    static Set<String> optionsFor(String command) {
        switch (command) {
            case "inspect": return INSPECT_OPTIONS;
            case "build":
            case "emit-rust": return BUILD_OPTIONS;
            default: throw new IllegalArgumentException("未知命令 " + command);
        }
    }

    static boolean parseBoolean(String name, String value) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException(name + " 需要 true 或 false");
    }

    static void validateChoice(String name, String value) {
        switch (name) {
            case "native-backend": requireChoice(name, value, "rust", "zig"); break;
            case "vm-coverage": requireChoice(name, value, "direct", "full", "partial"); break;
            case "pre-obfuscation": requireChoice(name, value, "full", "off"); break;
            case "flow-profile": requireChoice(name, value, "off", "balanced", "strong"); break;
            case "string-cipher": requireChoice(name, value, "off", "xor", "aes"); break;
            default: break;
        }
    }

    private static void requireChoice(String name, String value, String... choices) {
        for (String choice : choices) if (choice.equals(value)) return;
        throw new IllegalArgumentException(name + " 需要 " + String.join("、", choices));
    }

    private static Set<String> names(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));
    }
}
