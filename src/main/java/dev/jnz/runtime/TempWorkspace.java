package dev.jnz.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class TempWorkspace {
    private static Path directory;
    private TempWorkspace() {}

    static synchronized Path reserve(String target) throws IOException {
        if (directory == null) {
            directory = Files.createTempDirectory("j2rust-");
            directory.toFile().deleteOnExit();
        }
        Path file = Files.createTempFile(directory, "module-", PlatformTarget.libraryExtension(target));
        file.toFile().deleteOnExit();
        return file;
    }
}
