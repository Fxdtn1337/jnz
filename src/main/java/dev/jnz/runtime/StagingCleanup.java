package dev.jnz.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class StagingCleanup {
    private StagingCleanup() {}

    static void discardFailedFile(Path file, Throwable original) {
        try { Files.deleteIfExists(file); }
        catch (IOException | SecurityException cleanup) { original.addSuppressed(cleanup); }
    }
}
