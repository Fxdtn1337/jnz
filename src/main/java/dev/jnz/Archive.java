package dev.jnz;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

final class Archive {
    private Archive() {}

    static Map<String, byte[]> read(Path path) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (InputStream in = zip.getInputStream(entry)) {
                    byte[] buffer = new byte[16384];
                    int n;
                    while ((n = in.read(buffer)) != -1) bytes.write(buffer, 0, n);
                }
                if (entries.put(entry.getName(), bytes.toByteArray()) != null)
                    throw new IOException("输入包含重复条目 " + entry.getName());
            }
        }
        return entries;
    }

    static void checkWritable(Map<String, byte[]> entries) throws IOException {
        for (String name : entries.keySet()) {
            String upper = name.toUpperCase(Locale.ROOT);
            if (upper.matches("META-INF/(SIG-[^/]*|[^/]+[.](SF|RSA|DSA|EC))"))
                throw new IOException("输入 JAR 包含签名，请使用签名前的构建产物 " + name);
            String base = name.replaceFirst("^META-INF/versions/[0-9]+/", "");
            if (base.startsWith("dev/jnz/runtime/") || base.startsWith("dev/jnz/license/") || base.startsWith("META-INF/jnz/"))
                throw new IOException("输入已使用 jnative-zig 运行时命名空间 " + name);
        }
    }

    interface Writer { void write(OutputStream out) throws IOException; }

    static void publish(Path dest, Writer writer) throws IOException {
        Path directory = dest.toAbsolutePath().normalize().getParent();
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, ".jnative-", ".tmp");
        try {
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) { writer.write(out); }
            try { Files.move(temp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    static void writeJar(Path dest, final Map<String, byte[]> entries) throws IOException {
        publish(dest, stream -> {
            try (ZipOutputStream zip = new ZipOutputStream(stream)) {
                // 将 manifest 置于首位，兼容 JarInputStream 的顺序读取方式。
                List<String> names = new ArrayList<String>(entries.keySet());
                if (names.remove("META-INF/MANIFEST.MF")) names.add(0, "META-INF/MANIFEST.MF");
                for (String name : names) {
                    ZipEntry entry = new ZipEntry(name);
                    entry.setTime(315532800000L);
                    zip.putNextEntry(entry);
                    zip.write(entries.get(name));
                    zip.closeEntry();
                }
            }
        });
    }
}
