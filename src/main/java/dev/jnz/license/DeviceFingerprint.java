package dev.jnz.license;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 使用本机设备标识生成稳定的离线指纹。原始标识仅在本进程使用。 */
public final class DeviceFingerprint {
    private DeviceFingerprint() { }

    public static byte[] current() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String family;
        String machine;
        if (os.startsWith("windows")) {
            family = "windows";
            String root = System.getenv("SystemRoot");
            if (root == null) {
                throw new IOException("Windows 缺少 SystemRoot");
            }
            String reg = Paths.get(root, "System32", "reg.exe").toString();
            machine = commandId(Pattern.compile("(?i)^\\s*MachineGuid\\s+REG_SZ\\s+([0-9a-f-]+)\\s*$"),
                    reg, "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid", "/reg:64");
        } else if (os.startsWith("linux")) {
            family = "linux";
            Path id = Paths.get("/etc/machine-id");
            if (!Files.isReadable(id)) {
                id = Paths.get("/var/lib/dbus/machine-id");
            }
            try (BufferedReader in = Files.newBufferedReader(id, StandardCharsets.US_ASCII)) {
                String line = in.readLine();
                machine = line == null ? "" : line.trim();
            }
        } else if (os.startsWith("mac") || os.startsWith("darwin")) {
            family = "macos";
            machine = commandId(Pattern.compile("\"IOPlatformUUID\"\\s*=\\s*\"([0-9a-fA-F-]+)\""),
                    "/usr/sbin/ioreg", "-rd1", "-c", "IOPlatformExpertDevice");
        } else {
            throw new IOException("当前平台未定义离线指纹来源");
        }
        String hex = machine.replace("-", "").toLowerCase(Locale.ROOT);
        if (!hex.matches("[0-9a-f]{32}") || hex.equals("00000000000000000000000000000000")) {
            throw new IOException("设备标识不存在或格式无效");
        }
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if ("amd64".equals(arch) || "x64".equals(arch)) {
            arch = "x86_64";
        } else if ("arm64".equals(arch)) {
            arch = "aarch64";
        }
        String material = "jnz-hwid-v1\0" + family + "\0" + arch + "\0" + hex;
        try {
            return MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JRE 缺少 SHA-256", e);
        }
    }

    private static String commandId(Pattern pattern, String... args) throws IOException {
        final Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        FutureTask<String> read = new FutureTask<>(() -> {
            String id = null;
            int size = 0;
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.ISO_8859_1))) {
                for (String line; (line = in.readLine()) != null;) {
                    size += line.length() + 1;
                    if (size > 1024 * 1024) {
                        throw new IOException("系统设备信息超过长度限制");
                    }
                    Matcher matcher = pattern.matcher(line);
                    if (id == null && matcher.find()) {
                        id = matcher.group(1);
                    }
                }
            }
            return id;
        });
        Thread reader = new Thread(read, "jnz-device-id");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                throw new IOException("获取系统设备标识超时");
            }
            String id = read.get(2, TimeUnit.SECONDS);
            if (process.exitValue() != 0 || id == null) {
                throw new IOException("无法从本机系统读取设备标识");
            }
            return id;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("获取设备标识被中断", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("读取设备标识失败", e);
        } finally {
            process.destroyForcibly();
            read.cancel(true);
        }
    }
}
