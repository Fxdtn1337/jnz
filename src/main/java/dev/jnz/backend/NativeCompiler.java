package dev.jnz.backend;

import dev.jnz.frontend.Program;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 将同一分片内的全部方法编译为标准 JNI 动态库，无网络操作。 */
public final class NativeCompiler {
    private static final Set<String> TARGETS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "x86_64-windows-gnu", "aarch64-windows-gnu", "x86_64-linux-gnu", "aarch64-linux-gnu",
            "x86_64-linux-musl", "aarch64-linux-musl", "x86_64-macos", "aarch64-macos")));
    private NativeCompiler() {}

    public static List<Path> compile(List<Program> programs, Path work, String target, String zig)
            throws IOException, InterruptedException {
        return compile(programs, work, target, zig, null);
    }

    public static List<Path> compile(List<Program> programs, Path work, String target, String zig, byte[] vendorKeySec1)
            throws IOException, InterruptedException {
        return compile(programs, work, target, zig, vendorKeySec1, 0L);
    }

    public static List<Path> compile(List<Program> programs, Path work, String target, String zig,
                                     byte[] vendorKeySec1, long requiredFeatures) throws IOException, InterruptedException {
        return compile(programs, work, target, zig, vendorKeySec1, requiredFeatures, Collections.<Integer>emptySet());
    }

    public static List<Path> compile(List<Program> programs, Path work, String target, String zig,
                                     byte[] vendorKeySec1, long requiredFeatures, Set<Integer> directIds)
            throws IOException, InterruptedException {
        if (programs.isEmpty()) return Collections.emptyList();
        return compile(programs, work, target, zig, vendorKeySec1, requiredFeatures, directIds,
                "dev/jnz/runtime/Group" + programs.get(0).id, "register", "verifyLicense");
    }

    public static List<Path> compile(List<Program> programs, Path work, String target, String zig,
                                     byte[] vendorKeySec1, long requiredFeatures, Set<Integer> directIds,
                                     String bridgeOwner, String registerMethod, String verifyMethod)
            throws IOException, InterruptedException {
        if (!TARGETS.contains(target)) throw new IOException("Unsupported Zig target: " + target);
        if (programs.isEmpty()) return Collections.emptyList();
        if (vendorKeySec1 != null && vendorKeySec1.length != 0 &&
                (vendorKeySec1.length != 65 || vendorKeySec1[0] != 4)) {
            throw new IOException("Expected an uncompressed SEC1 P-256 public key");
        }
        Files.createDirectories(work);
        Path directory = Files.createTempDirectory(work.toAbsolutePath(), "module-");
        for (String template : Arrays.asList("vm.zig", "jni.zig", "links.zig", "handlers.zig", "build.zig")) {
            try (InputStream in = NativeCompiler.class.getResourceAsStream(template)) {
                if (in == null) throw new IOException("Missing Zig compiler template " + template);
                Files.copy(in, directory.resolve(template));
            }
        }
        SecureRandom random = new SecureRandom();
        EncryptedPrograms encrypted = new EncryptedPrograms(random);
        Set<Integer> ids = new HashSet<>();
        for (Program p : programs) {
            if (!ids.add(p.id)) throw new IOException("Duplicate method ID " + p.id);
            if (p.handlers.length > 0) {
                if (directIds.contains(p.id)) throw new IOException("Exception-table methods require VM mode: " + p.id);
                encrypted.writeHandlers(p, directory);
            }
            if (!directIds.contains(p.id)) encrypted.write(p, directory);
            if (!p.links.isEmpty()) encrypted.writeLinks(p, directory);
        }
        byte[] pinned = vendorKeySec1 == null ? new byte[0] : vendorKeySec1;
        String source = ZigSource.generate(programs, encrypted, pinned, requiredFeatures, random, directory, directIds,
                jniSymbol(bridgeOwner, registerMethod), jniSymbol(bridgeOwner, verifyMethod));
        Files.write(directory.resolve("module.zig"), source.getBytes(StandardCharsets.UTF_8));
        String extension = target.contains("-windows-") ? ".dll" : target.endsWith("-macos") ? ".dylib" : ".so";
        String fileName = "jnz_" + UUID.randomUUID().toString().replace("-", "") + extension;
        Path output = directory.resolve(fileName);
        String executable = zig == null || zig.isEmpty() ? "zig" : zig;
        if (Paths.get(executable).getParent() != null) executable = Paths.get(executable).toAbsolutePath().toString();
        Path log = directory.resolve("compiler.log");
        Process process = new ProcessBuilder(executable, "build-lib", "module.zig", "-dynamic", "-O", "ReleaseSafe",
                "-fstrip", "-fPIC", "-target", target, "-femit-bin=" + fileName)
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        int status;
        try {
            status = process.waitFor();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            throw e;
        }
        if (status != 0 || !Files.isRegularFile(output) || Files.size(output) == 0) {
            String detail = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
            throw new IOException("Zig failed for " + target + " (exit " + status + "):\n" + detail);
        }
        return Collections.singletonList(output);
    }

    private static String jniSymbol(String owner, String method) throws IOException {
        if (!owner.matches("[A-Za-z][A-Za-z0-9]*(/[A-Za-z][A-Za-z0-9]*)*")
                || !method.matches("[A-Za-z][A-Za-z0-9]*")) throw new IOException("Invalid generated JNI binding");
        return "Java_" + owner.replace('/', '_') + "_" + method;
    }
}
