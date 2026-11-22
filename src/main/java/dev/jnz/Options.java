package dev.jnz;

import java.io.*;
import java.nio.file.*;
import java.util.*;

final class Options {
    final String command;
    private final Map<String,String> values = new LinkedHashMap<String,String>();
    final boolean strict;

    Options(String[] argv) throws IOException {
        command = argv[0];
        Set<String> allowed = BuildConfig.optionsFor(command);
        Map<String, String> overrides = new LinkedHashMap<>();
        Path config = null;
        for (int i = 1; i < argv.length; i++) {
            String token = argv[i];
            if (!token.startsWith("--")) throw new IllegalArgumentException("选项格式错误 " + token);
            int equals = token.indexOf('=');
            String name = token.substring(2, equals == -1 ? token.length() : equals);
            if (token.equals("--no-strict")) {
                putOnce(overrides, "strict", "false");
                continue;
            }
            if (name.equals("strict")) {
                String value = equals < 0 ? "true" : token.substring(equals + 1);
                putOnce(overrides, name, Boolean.toString(BuildConfig.parseBoolean(name, value)));
                continue;
            }
            if (!allowed.contains(name) && !name.equals("config")) throw new IllegalArgumentException("未知选项 " + token);
            if (equals == -1 && (i+1 >= argv.length || argv[i+1].startsWith("--")))
                throw new IllegalArgumentException("缺少参数 " + token);
            String value = equals == -1 ? argv[++i] : token.substring(equals+1);
            if (name.equals("config")) {
                if (config != null) throw new IllegalArgumentException("重复选项 --config");
                if (value.trim().isEmpty()) throw new IllegalArgumentException("--config 需要配置文件路径");
                config = Paths.get(value);
            } else {
                BuildConfig.validateChoice(name, value);
                putOnce(overrides, name, value);
            }
        }
        if (config != null) {
            for (Map.Entry<String, String> entry : BuildConfig.read(config).entrySet()) {
                if (allowed.contains(entry.getKey())) values.put(entry.getKey(), entry.getValue());
            }
        }
        values.putAll(overrides);
        strict = Boolean.parseBoolean(get("strict", "false"));
    }

    private static void putOnce(Map<String, String> overrides, String name, String value) {
        if (overrides.putIfAbsent(name, value) != null) throw new IllegalArgumentException("重复选项 --" + name);
    }

    String get(String name, String fallback) { return values.getOrDefault(name, fallback); }
    boolean has(String name) { return values.containsKey(name); }
    Path path(String name) {
        String value = get(name, "");
        if (value.trim().isEmpty()) throw new IllegalArgumentException("必须指定 --" + name);
        return Paths.get(value).toAbsolutePath().normalize();
    }

    int size(String name, int fallback) {
        String raw = get(name, String.valueOf(fallback)).toUpperCase(Locale.ROOT);
        int multiplier = raw.endsWith("M") ? 1024 * 1024 : raw.endsWith("K") ? 1024 : 1;
        if (multiplier != 1) raw = raw.substring(0, raw.length()-1);
        long value = Math.multiplyExact(Long.parseLong(raw), multiplier);
        if (value < 1 || value > Integer.MAX_VALUE) throw new IllegalArgumentException("无效大小 --" + name);
        return (int)value;
    }

    int bridgeCount() {
        int count = Integer.parseInt(get("jni-bridges", "1"));
        if (count < 1 || count > 256) throw new IllegalArgumentException("--jni-bridges 需要介于 1 和 256 之间");
        return count;
    }

    String rustc() throws Exception {
        if (has("rustc")) return path("rustc").toString();
        String environment = System.getenv("JNZ_RUSTC");
        if (environment != null && !environment.isEmpty()) return environment;
        String executable = System.getProperty("os.name").startsWith("Windows") ? "rustc.exe" : "rustc";
        Path jarPath = Paths.get(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
        Path fromJar = jarPath.resolveSibling("tools/rust/bin/" + executable);
        if (Files.isRegularFile(fromJar)) return fromJar.toString();
        Path fromProject = Paths.get("out", "tools", "rust", "bin", executable).toAbsolutePath();
        if (Files.isRegularFile(fromProject)) return fromProject.toString();
        return executable;
    }

    String zig() throws Exception {
        if (has("zig")) return path("zig").toString();
        List<Path> bases = new ArrayList<Path>();
        bases.add(Paths.get("").toAbsolutePath());
        Path jar = Paths.get(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
        if (jar.getParent() != null) {
            bases.add(jar.getParent());
            if (jar.getParent().getParent() != null) bases.add(jar.getParent().getParent());
        }
        String exe = System.getProperty("os.name").startsWith("Windows") ? "zig.exe" : "zig";
        for (Path base : new LinkedHashSet<Path>(bases)) {
            if (!Files.isDirectory(base)) continue;
            if (Files.isRegularFile(base.resolve(exe))) return base.resolve(exe).toString();
            List<Path> candidates = new ArrayList<Path>();
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(base, "zig*")) {
                for (Path dir : dirs) if (Files.isRegularFile(dir.resolve(exe))) candidates.add(dir.resolve(exe));
            }
            if (candidates.size() == 1) return candidates.get(0).toString();
            if (candidates.size() > 1) throw new IOException("项目内有多个 Zig，请通过 --zig 选择 " + candidates);
        }
        String environment = System.getenv("JNZ_ZIG");
        return environment == null || environment.isEmpty() ? "zig" : environment;
    }
}
