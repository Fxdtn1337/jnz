package dev.jnz.pack;

import dev.jnz.protect.LiteralMask;
import dev.jnz.runtime.DigestCodec;
import dev.jnz.runtime.ModuleFormat;
import dev.jnz.runtime.ResourceLimits;
import dev.jnz.runtime.ResourceNamespace;
import dev.jnz.runtime.ResourcePaths;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.util.*;

/** 平台模块索引、可校验资源分块以及 JVM 绑定声明。 */
public final class Bundle {
    static final String RUNTIME = "dev/jnz/runtime/";
    private Bundle() {}

    /** 一次构建中所有目标平台共享同一组 JVM/JNI ABI 名称。 */
    public static final class Layout {
        private final SecureRandom random = new SecureRandom();
        final long seedLeft = random.nextLong(), seedRight = random.nextLong();
        final String resourceRoot = ResourceNamespace.derive(seedLeft, seedRight);
        private final Set<String> metadata = new TreeSet<>();
        private final Set<String> names = new HashSet<>();
        private final Map<Integer, Group> groups = new HashMap<>();
        private final Map<String, String> templates = new LinkedHashMap<>();
        private final String bootstrapMethod, literalBootstrap, literalDecode;

        private Layout() {
            for (String original : RuntimeObfuscator.TEMPLATES) templates.put(original, RUNTIME + name('R'));
            bootstrapMethod = name('m');
            literalBootstrap = name('m');
            literalDecode = name('m');
        }

        public String bootstrapOwner() { return templates.get(RuntimeObfuscator.BOOTSTRAP); }
        public String bootstrapMethod() { return bootstrapMethod; }
        public String literalOwner() { return templates.get(RuntimeObfuscator.LITERAL_SITE); }
        public String literalBootstrap() { return literalBootstrap; }
        public String literalDecode() { return literalDecode; }
        public int runtimeClassCount() { return templates.size() + groups.size(); }
        public Group group(int id) {
            return groups.computeIfAbsent(id, key -> new Group(RUNTIME + name('R'), name('m')));
        }

        Map<String, String> templates() { return Collections.unmodifiableMap(templates); }

        String name(char prefix) {
            String fresh;
            do {
                byte[] bytes = new byte[12];
                random.nextBytes(bytes);
                StringBuilder out = new StringBuilder(25).append(prefix);
                for (byte value : bytes) {
                    out.append(Character.forDigit((value & 0xff) >>> 4, 16));
                    out.append(Character.forDigit(value & 0xf, 16));
                }
                fresh = out.toString();
            } while (!names.add(fresh));
            return fresh;
        }

        int seed() {
            int value;
            do { value = random.nextInt(); }
            while (value == 0);
            return value;
        }
    }

    public static final class Group {
        private final String owner, registerMethod;

        private Group(String owner, String registerMethod) {
            this.owner = owner;
            this.registerMethod = registerMethod;
        }

        public String owner() { return owner; }
        public String registerMethod() { return registerMethod; }
    }

    public static Layout plan() {
        return new Layout();
    }

    public static void addRuntime(Map<String, byte[]> entries, Layout layout) throws IOException {
        byte[] catalog = buildCatalog(entries, layout);
        put(entries, layout.resourceRoot + ResourcePaths.catalog(), catalog);
        RuntimeObfuscator.emit(entries, layout, DigestCodec.hex(DigestCodec.hash(catalog)));
    }

    private static byte[] buildCatalog(Map<String, byte[]> entries, Layout layout) throws IOException {
        if (layout.metadata.isEmpty() || layout.metadata.size() > ResourceLimits.CATALOG_RECORDS)
            throw new IOException("模块目录条目数超出支持范围");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0x4a4e4332);
        out.writeInt(layout.metadata.size());
        for (String path : layout.metadata) {
            out.writeUTF(path);
            out.write(DigestCodec.hash(entries.get(layout.resourceRoot + path)));
        }
        if (bytes.size() > ResourceLimits.CATALOG_BYTES) throw new IOException("模块目录长度超限");
        return bytes.toByteArray();
    }

    public static void addGroup(Map<String, byte[]> entries, Layout layout, int groupId, Map<Integer, String> classes)
            throws IOException {
        Group group = layout.group(groupId);
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, group.owner(), null, "java/lang/Object", null);
        int flags = Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE;
        writer.visitMethod(flags, group.registerMethod(), "(Ljava/lang/Class;I)V", null, null).visitEnd();
        writer.visitEnd();
        put(entries, group.owner() + ".class", writer.toByteArray());
        ByteArrayOutputStream binding = new ByteArrayOutputStream();
        DataOutputStream entry = new DataOutputStream(binding);
        int seed = layout.seed();
        entry.writeInt(seed);
        String name = group.owner().substring(RUNTIME.length());
        entry.writeUTF(LiteralMask.encode(name + '|' + group.registerMethod(), seed));
        putMetadata(entries, layout, ResourcePaths.groupRoute(groupId), binding.toByteArray());
        for (Map.Entry<Integer, String> route : classes.entrySet()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(groupId);
            out.writeUTF(route.getValue().replace('/', '.'));
            putMetadata(entries, layout, ResourcePaths.classRoute(route.getKey()), bytes.toByteArray());
        }
    }

    public static void addModule(Map<String, byte[]> entries, Layout layout, String target, int groupId, Path library,
                                 int chunkSize) throws IOException {
        addModule(entries, layout, target, groupId, library, chunkSize, "auto");
    }

    public static void addModule(Map<String, byte[]> entries, Layout layout, String target, int groupId, Path library,
                                 int chunkSize, String codec) throws IOException {
        ModuleFrameWriter.validateCodec(codec);
        if (chunkSize <= 0 || chunkSize > ModuleFormat.FRAME_BYTES) throw new IOException("分片长度范围为 1 字节至 64 MiB");
        long fileSize = Files.size(library);
        if (fileSize < 1 || fileSize > ResourceLimits.MODULE_BYTES || 1 + (fileSize - 1) / chunkSize > ResourceLimits.MODULE_BLOCKS)
            throw new IOException("模块长度或分片数超限 " + library);
        MessageDigest digest = DigestCodec.sha256();
        MessageDigest stored = DigestCodec.sha256();
        byte[] block = new byte[(int) Math.min(fileSize, chunkSize)];
        long size = 0;
        int count = 0;
        try (InputStream input = Files.newInputStream(library)) {
            while (true) {
                int length = 0, n;
                while (length < block.length && (n = input.read(block, length, block.length - length)) != -1)
                    length += n;
                if (length == 0) break;
                digest.update(block, 0, length);
                byte[] frame = ModuleFrameWriter.encode(codec, count, block, length);
                stored.update(frame);
                put(entries, layout.resourceRoot + ResourcePaths.chunk(target, groupId, count++), frame);
                size += length;
            }
        }
        if (size != fileSize || count > ResourceLimits.MODULE_BLOCKS) throw new IOException("构建期间模块长度发生变化 " + library);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(ModuleFormat.INDEX_MAGIC);
        out.writeInt(count);
        out.writeLong(size);
        out.write(digest.digest());
        out.write(stored.digest());
        putMetadata(entries, layout, ResourcePaths.moduleIndex(target, groupId), bytes.toByteArray());
    }

    private static void putMetadata(Map<String, byte[]> entries, Layout layout, String path, byte[] data) throws IOException {
        if (data.length > ResourceLimits.METADATA_BYTES) throw new IOException("模块元数据长度超限 " + path);
        put(entries, layout.resourceRoot + path, data);
        layout.metadata.add(path);
    }

    public static void put(Map<String, byte[]> entries, String name, byte[] data) throws IOException {
        if (entries.containsKey(name)) throw new IOException("JAR 条目重名 " + name);
        entries.put(name, data);
    }
}
