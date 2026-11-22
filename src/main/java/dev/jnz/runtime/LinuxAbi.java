package dev.jnz.runtime;

import java.nio.file.Files;
import java.nio.file.Paths;

final class LinuxAbi {
    private LinuxAbi() {}

    static String value(String cpu) {
        String abi = System.getProperty("jnz.linux.abi");
        if (abi == null) abi = Files.isRegularFile(Paths.get("/lib", "ld-musl-" + cpu + ".so.1")) ? "musl" : "gnu";
        if (abi.equals("gnu") || abi.equals("musl")) return abi;
        throw new IllegalStateException("不支持的 Linux ABI " + abi);
    }
}
