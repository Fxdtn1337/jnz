package dev.jnz.pack;

import dev.jnz.protect.LiteralMask;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.util.*;

/** 平台模块索引、可校验资源分块以及 JVM 绑定声明。 */
public final class Bundle {
    public static final String ROOT = "META-INF/jnz/";
    static final String RUNTIME = "dev/jnz/runtime/";
    private Bundle() {}

    /** 一次构建中所有目标平台共享同一组 JVM/JNI ABI 名称。 */
    public static final class Layout {
        private final SecureRandom random = new SecureRandom();
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
        public Group group(int id) {
            return groups.computeIfAbsent(id, key -> new Group(RUNTIME + name('R'), name('m'), name('m')));
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
        private final String owner, registerMethod, verifyMethod;

        private Group(String owner, String registerMethod, String verifyMethod) {
            this.owner = owner;
            this.registerMethod = registerMethod;
            this.verifyMethod = verifyMethod;
        }

        public String owner() { return owner; }
        public String registerMethod() { return registerMethod; }
        public String verifyMethod() { return verifyMethod; }
    }

    public static Layout plan() {
        return new Layout();
    }

    public static void addRuntime(Map<String, byte[]> entries, Layout layout) throws IOException {
        RuntimeObfuscator.emit(entries, layout);
    }

    public static void addLicense(Map<String, byte[]> entries, byte[] key, byte[] license, long features)
            throws IOException {
        if (key.length != 65 || license.length != 120) throw new IOException("公钥或许可证长度无效");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeLong(features);
        out.write(key);
        out.write(license);
        put(entries, ROOT + "license.bin", bytes.toByteArray());
    }

    public static void addGroup(Map<String, byte[]> entries, Layout layout, int groupId, Collection<Integer> classIds)
            throws IOException {
        Group group = layout.group(groupId);
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, group.owner(), null, "java/lang/Object", null);
        int flags = Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE;
        writer.visitMethod(flags, group.registerMethod(), "(Ljava/lang/Class;I)V", null, null).visitEnd();
        writer.visitMethod(flags, group.verifyMethod(), "([B[B[B)Z", null, null).visitEnd();
        writer.visitEnd();
        put(entries, group.owner() + ".class", writer.toByteArray());
        ByteArrayOutputStream binding = new ByteArrayOutputStream();
        DataOutputStream entry = new DataOutputStream(binding);
        int seed = layout.seed();
        entry.writeInt(seed);
        String name = group.owner().substring(RUNTIME.length());
        entry.writeUTF(LiteralMask.encode(name + '|' + group.registerMethod() + '|' + group.verifyMethod(), seed));
        put(entries, ROOT + "groups/" + groupId, binding.toByteArray());
        for (int classId : classIds) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            new DataOutputStream(bytes).writeInt(groupId);
            put(entries, ROOT + "classes/" + classId, bytes.toByteArray());
        }
    }

    public static void addModule(Map<String, byte[]> entries, String target, int groupId, Path library,
                                 int chunkSize) throws IOException {
        if (chunkSize <= 0) throw new IOException("分片长度必须为正");
        String prefix = ROOT + target + "/" + groupId + "/";
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
        byte[] block = new byte[chunkSize];
        long size = 0;
        int count = 0;
        try (InputStream input = Files.newInputStream(library)) {
            while (true) {
                int length = 0, n;
                while (length < block.length && (n = input.read(block, length, block.length - length)) != -1)
                    length += n;
                if (length == 0) break;
                digest.update(block, 0, length);
                put(entries, prefix + count++ + ".part", Arrays.copyOf(block, length));
                size += length;
            }
        }
        if (count == 0) throw new IOException("Zig 输出空库 " + library);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0x4a4e5a31);
        out.writeInt(count);
        out.writeLong(size);
        out.write(digest.digest());
        put(entries, prefix + "index.bin", bytes.toByteArray());
    }

    public static void put(Map<String, byte[]> entries, String name, byte[] data) throws IOException {
        if (entries.containsKey(name)) throw new IOException("JAR 条目重名 " + name);
        entries.put(name, data);
    }
}
