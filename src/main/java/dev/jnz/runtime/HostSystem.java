package dev.jnz.runtime;

import java.util.Locale;

final class HostSystem {
    private HostSystem() {}

    static String target(String cpu) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) return cpu + "-windows-gnu";
        if (os.contains("mac") || os.contains("darwin")) return cpu + "-macos";
        if (os.contains("linux")) return cpu + "-linux-" + LinuxAbi.value(cpu);
        throw new IllegalStateException("不支持的平台 " + os);
    }
}
