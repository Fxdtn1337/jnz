package dev.jnz.backend;

import dev.jnz.frontend.Program;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Rust 负责程序执行与 JNI 入口，Zig 静态库提供现有的认证、JNI 链接与异常能力。 */
public final class RustCompiler {
    private RustCompiler() {}

    public static List<Path> compile(List<Program> programs, Path work, String target, String zig, String rustc,
                                     byte[] vendorKeySec1, long requiredFeatures, Set<Integer> directIds,
                                     String bridgeOwner, String registerMethod, String verifyMethod)
            throws IOException, InterruptedException {
        if (programs.isEmpty()) return Collections.emptyList();
        Path directory = emit(programs, work, vendorKeySec1, requiredFeatures, directIds,
                bridgeOwner, registerMethod, verifyMethod);
        String compiler = executable(rustc, "rustc");
        String version = run(directory, "rustc-version.log", "Rust compiler", compiler, "-vV");
        String host = null;
        for (String line : version.split("\\r?\\n")) {
            if (line.startsWith("host: ")) host = line.substring(6).trim();
        }
        String rustTarget = rustTarget(target, host);
        Path libDirectory = Paths.get(run(directory, "rustc-target.log", "Rust target", compiler,
                "--print=target-libdir", "--target", rustTarget).trim());
        if (!hasCore(libDirectory)) {
            throw new IOException("Rust target " + rustTarget + " 未安装，已生成离线源码 " + directory);
        }

        boolean msvc = rustTarget.endsWith("-msvc");
        String staticLibrary = msvc ? "jnz_rust_support.lib" : "libjnz_rust_support.a";
        String zigTarget = msvc ? target.replace("-gnu", "-msvc") : target;
        // GNU COFF linking uses the stack probes supplied by Rust/MinGW. Zig's weak aliases conflict with them.
        String compilerRuntime = rustTarget.endsWith("-windows-gnu") ? "-fno-compiler-rt" : "-fcompiler-rt";
        run(directory, "zig-support.log", "Zig JNI companion", executable(zig, "zig"),
                "build-lib", "bridge.zig", "-static", "-O", "ReleaseSafe", "-fPIC", compilerRuntime,
                "-target", zigTarget, "-femit-bin=" + staticLibrary);
        String extension = target.contains("-windows-") ? ".dll" : target.endsWith("-macos") ? ".dylib" : ".so";
        String artifact = "jnzr_" + UUID.randomUUID().toString().replace("-", "") + extension;
        List<String> command = new ArrayList<>(Arrays.asList(compiler, "module.rs", "--edition=2021",
                "--crate-name=jnz_module", "--crate-type=cdylib", "--target", rustTarget, "-C", "opt-level=3",
                "-C", "panic=abort", "-C", "strip=symbols", "-L", "native=.", "-l", "static=jnz_rust_support",
                "-o", artifact));
        if (target.contains("-windows-")) {
            command.add("-l");
            command.add("dylib=ntdll");
        } else if (target.endsWith("-musl")) {
            command.add("-C");
            command.add("target-feature=-crt-static");
        }
        String linker = System.getenv("JNZ_RUST_LINKER");
        if (linker != null && !linker.trim().isEmpty()) {
            command.add("-C");
            command.add("linker=" + executable(linker, linker));
        }
        run(directory, "rustc.log", "Rust native build", command.toArray(new String[0]));
        Path output = directory.resolve(artifact);
        if (!Files.isRegularFile(output) || Files.size(output) == 0) {
            throw new IOException("Rust 未生成动态库，构建目录 " + directory);
        }
        return Collections.singletonList(output);
    }

    /** 可在未配置 rustc 的机器上生成完整可移交源码与认证程序数据。 */
    public static Path emit(List<Program> programs, Path work, byte[] vendorKeySec1, long requiredFeatures,
                            Set<Integer> directIds, String bridgeOwner, String registerMethod, String verifyMethod)
            throws IOException {
        if (programs.isEmpty()) throw new IOException("Rust module has no methods");
        byte[] pinned = vendorKeySec1 == null ? new byte[0] : vendorKeySec1;
        if (pinned.length != 0 && (pinned.length != 65 || pinned[0] != 4))
            throw new IOException("Expected an uncompressed SEC1 P-256 public key");
        Files.createDirectories(work);
        Path directory = Files.createTempDirectory(work.toAbsolutePath(), "rust-module-");
        for (String template : Arrays.asList("vm.zig", "jni.zig", "links.zig", "handlers.zig")) {
            copy(template, directory.resolve(template));
        }
        copy("rust/vm.rs", directory.resolve("vm.rs"));
        copy("rust/bridge.zig", directory.resolve("bridge.zig"));
        SecureRandom random = new SecureRandom();
        EncryptedPrograms data = new EncryptedPrograms(random);
        Set<Integer> ids = new HashSet<>();
        for (Program p : programs) {
            if (!ids.add(p.id)) throw new IOException("Duplicate method ID " + p.id);
            if (p.handlers.length != 0) {
                if (directIds.contains(p.id)) throw new IOException("Exception-table methods require VM mode: " + p.id);
                data.writeHandlers(p, directory);
            }
            if (!directIds.contains(p.id)) data.write(p, directory);
            if (!p.links.isEmpty()) data.writeLinks(p, directory);
        }
        String source = RustSource.generate(programs, data, pinned, requiredFeatures, random, directory, directIds,
                jniSymbol(bridgeOwner, registerMethod), jniSymbol(bridgeOwner, verifyMethod));
        Files.write(directory.resolve("module.rs"), source.getBytes(StandardCharsets.UTF_8));
        return directory;
    }

    private static String rustTarget(String target, String host) throws IOException {
        switch (target) {
            case "x86_64-windows-gnu":
                return "x86_64-pc-windows-msvc".equals(host) ? host : "x86_64-pc-windows-gnu";
            case "aarch64-windows-gnu":
                return "aarch64-pc-windows-msvc".equals(host) ? host : "aarch64-pc-windows-gnullvm";
            case "x86_64-linux-gnu": case "aarch64-linux-gnu":
            case "x86_64-linux-musl": case "aarch64-linux-musl":
                return target.replace("-linux-", "-unknown-linux-");
            case "x86_64-macos": case "aarch64-macos":
                return target.replace("-macos", "-apple-darwin");
            default:
                throw new IOException("Unsupported Rust target: " + target);
        }
    }

    private static boolean hasCore(Path libDirectory) throws IOException {
        if (!Files.isDirectory(libDirectory)) return false;
        try (DirectoryStream<Path> cores = Files.newDirectoryStream(libDirectory, "libcore-*.rlib")) {
            return cores.iterator().hasNext();
        }
    }

    private static String executable(String value, String fallback) {
        String name = value == null || value.isEmpty() ? fallback : value;
        Path path = Paths.get(name);
        return path.getParent() == null ? name : path.toAbsolutePath().normalize().toString();
    }

    private static void copy(String source, Path target) throws IOException {
        try (InputStream stream = RustCompiler.class.getResourceAsStream(source)) {
            if (stream == null) throw new IOException("Missing Rust compiler template " + source);
            Files.copy(stream, target);
        }
    }

    private static String jniSymbol(String owner, String method) throws IOException {
        if (!owner.matches("[A-Za-z][A-Za-z0-9]*(/[A-Za-z][A-Za-z0-9]*)*")
                || !method.matches("[A-Za-z][A-Za-z0-9]*")) throw new IOException("Invalid generated JNI binding");
        return "Java_" + owner.replace('/', '_') + "_" + method;
    }

    private static String run(Path directory, String logName, String stage, String... command)
            throws IOException, InterruptedException {
        Path log = directory.resolve(logName);
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("RUSTUP_AUTO_INSTALL", "0");
        builder.environment().put("CARGO_NET_OFFLINE", "true");
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IOException(stage + " 无法启动 " + command[0] + "，已生成离线源码 " + directory, e);
        }
        int status;
        try {
            status = process.waitFor();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            throw e;
        }
        String detail = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
        StringBuilder invocation = new StringBuilder("\n[process]\nexit=").append(status).append('\n');
        for (int i = 0; i < command.length; i++) {
            invocation.append("argv[").append(i).append("]=")
                      .append(command[i].replace("\r", "\\r").replace("\n", "\\n")).append('\n');
        }
        Files.write(log, invocation.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        if (status != 0) throw new IOException(stage + " failed (exit " + status + ") in " + directory + ":\n" + detail);
        return detail;
    }
}
