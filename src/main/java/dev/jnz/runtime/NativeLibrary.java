package dev.jnz.runtime;

import java.io.IOException;
import java.nio.file.Path;

/** 拼接并校验模块，再交给 JVM 和操作系统的标准动态库加载器。 */
final class NativeLibrary {
    private NativeLibrary() {}

    static void link(String target, int group) throws IOException {
        ModuleSource module = new ModuleSource(target, group);
        Path file = TempWorkspace.reserve(target);
        try {
            ModuleTransfer.verifiedCopy(module, file);
            System.load(file.toAbsolutePath().toString());
        } catch (IOException | RuntimeException | LinkageError error) {
            StagingCleanup.discardFailedFile(file, error);
            throw error;
        }
    }
}
