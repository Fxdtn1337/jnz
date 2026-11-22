package dev.jnz.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 拼接并校验模块，再交给 JVM 和操作系统的标准动态库加载器。 */
final class NativeLibrary {
    private static Path directory;

    private NativeLibrary() {}

    static void link(String target, int group) throws IOException {
        String prefix = target + "/" + group + "/";
        ModuleIndex index = ModuleIndex.read(prefix);
        if (directory == null) {
            directory = Files.createTempDirectory("jnative-zig-");
            directory.toFile().deleteOnExit();
        }
        Path file = Files.createTempFile(directory, "group-" + group + "-", PlatformTarget.libraryExtension(target));
        file.toFile().deleteOnExit();
        try {
            copyVerified(prefix, index, file);
            System.load(file.toAbsolutePath().toString());
        } catch (IOException | RuntimeException | LinkageError error) {
            try { Files.deleteIfExists(file); }
            catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    private static void copyVerified(String prefix, ModuleIndex index, Path file) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new AssertionError(error); }
        String suffix = ".part";
        long remaining = index.length;
        try (OutputStream out = Files.newOutputStream(file)) {
            byte[] buffer = new byte[16_384];
            for (int i = 0; i < index.blocks; i++) {
                try (InputStream part = PackedResources.require(prefix + i + suffix)) {
                    int read;
                    while ((read = part.read(buffer)) != -1) {
                        if (read > remaining) throw new IOException("模块超出索引长度");
                        remaining -= read;
                        digest.update(buffer, 0, read);
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
        if (remaining != 0 || !MessageDigest.isEqual(index.digest, digest.digest()))
            throw new IOException("模块 SHA-256 或长度校验失败 " + prefix);
    }
}
