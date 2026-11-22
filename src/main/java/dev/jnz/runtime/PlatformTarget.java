package dev.jnz.runtime;

/** 将当前 JVM 的架构和平台规范化为 Zig 目标。 */
final class PlatformTarget {
    private PlatformTarget() {}

    static String hostTarget() {
        return HostSystem.target(HostCpu.value());
    }

    static String libraryExtension(String target) {
        if (target.endsWith("-macos")) return ".dylib";
        if (target.endsWith("-windows-gnu")) return ".dll";
        return ".so";
    }
}
