package dev.jnz.runtime;

import java.util.Locale;

final class HostCpu {
    private HostCpu() {}

    static String value() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.equals("amd64") || arch.equals("x86_64")) return "x86_64";
        if (arch.equals("aarch64") || arch.equals("arm64")) return "aarch64";
        throw new IllegalStateException("不支持的 CPU " + arch);
    }
}
