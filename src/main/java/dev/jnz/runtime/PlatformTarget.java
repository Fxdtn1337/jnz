package dev.jnz.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/** 将当前 JVM 的架构和平台规范化为 Zig 目标。 */
final class PlatformTarget {
    private PlatformTarget() {}

    static String hostTarget() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.equals("amd64") || arch.equals("x86_64")) arch = "x86_64";
        else if (arch.equals("aarch64") || arch.equals("arm64")) arch = "aarch64";
        else throw new IllegalStateException("不支持的 CPU " + arch);
        if (os.startsWith("windows")) return arch + "-windows-gnu";
        if (os.contains("mac") || os.contains("darwin")) return arch + "-macos";
        if (os.contains("linux")) {
            String abi = System.getProperty("jnz.linux.abi");
            if (abi == null) {
                Path ld = Paths.get("/lib", "ld-musl-" + arch + ".so.1");
                abi = Files.isRegularFile(ld) ? "musl" : "gnu";
            }
            if (abi.equals("gnu") || abi.equals("musl")) return arch + "-linux-" + abi;
        }
        throw new IllegalStateException("不支持的平台 " + os + " " + arch);
    }

    static String libraryExtension(String target) {
        if (target.endsWith("-macos")) return ".dylib";
        if (target.endsWith("-windows-gnu")) return ".dll";
        return ".so";
    }
}
