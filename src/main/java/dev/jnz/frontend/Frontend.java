package dev.jnz.frontend;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.*;
import java.util.jar.Manifest;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** 离线 class 前端。先完成所有计划，再一次性提交条目替换。 */
public final class Frontend implements Opcodes {
    private static final String BOOTSTRAP = "dev/jnz/runtime/Bootstrap";
    private static final String VERSIONED = "META-INF/versions/";
    private static final SecureRandom INITIALIZER_NAMES = new SecureRandom();
    private Frontend() {}

    public static final class Result {
        public final List<Program> programs = new ArrayList<>();
        public final List<String[]> skips = new ArrayList<>();
        public final List<Initializer> initializers = new ArrayList<>();
    }

    public static final class Initializer {
        public final String entry, owner, name, descriptor, helperName, helperDescriptor;
        public final int retainedInstructions, loweredInstructions;

        private Initializer(String entry, String owner, MethodNode method, InitializerTail plan,
                            InitializerTail.Part part) {
            this.entry = entry;
            this.owner = owner;
            this.name = method.name;
            this.descriptor = method.desc;
            this.helperName = part.helper.name;
            this.helperDescriptor = part.helper.desc;
            this.retainedInstructions = plan.retainedInstructions;
            this.loweredInstructions = part.loweredInstructions;
        }
    }

    public static final class StrictFailure extends IOException {
        public final List<String[]> skips;
        StrictFailure(List<String[]> skips) {
            super("Strict selection has " + skips.size() + " unconverted entries: "
                    + Arrays.toString(skips.get(0)));
            this.skips = Collections.unmodifiableList(new ArrayList<>(skips));
        }
    }

    public static Result transform(Map<String, byte[]> entries, String includes, String excludes,
                                   boolean strict) throws IOException {
        return transform(entries, includes, excludes, strict, BOOTSTRAP, "bind");
    }

    public static Result transform(Map<String, byte[]> entries, String includes, String excludes,
                                   boolean strict, String bootstrapOwner, String bootstrapMethod) throws IOException {
        Result result = new Result();
        MethodSelector selector = new MethodSelector(includes, excludes);
        Map<String, byte[]> changes = new HashMap<>();
        boolean multiRelease = isMultiRelease(entries);
        for (String entry : new TreeSet<>(entries.keySet())) {
            if (!entry.endsWith(".class")) continue;
            byte[] bytes = entries.get(entry);
            if (bytes.length < 8 || bytes[0] != (byte) 0xca || bytes[1] != (byte) 0xfe
                    || bytes[2] != (byte) 0xba || bytes[3] != (byte) 0xbe) {
                result.skips.add(new String[] {entry, "", "", "invalid class header"});
                continue;
            }
            int major = (bytes[6] & 255) << 8 | (bytes[7] & 255);
            int minor = (bytes[4] & 255) << 8 | (bytes[5] & 255);
            if (major > V27)
                throw new IOException("Unsupported class version " + major + "." + minor + " in " + entry);
            try {
                processClass(entry, bytes, major, minor, multiRelease, selector, changes, result,
                        bootstrapOwner, bootstrapMethod);
            } catch (RuntimeException ex) {
                result.skips.add(new String[] {entry, "", "", "obfuscation-incompatible: "
                        + ex.getClass().getSimpleName() + ": " + ex.getMessage()});
            }
        }
        if (strict && !result.skips.isEmpty()) throw new StrictFailure(result.skips);
        entries.putAll(changes);
        return result;
    }

    private static boolean isMultiRelease(Map<String, byte[]> entries) throws IOException {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (!entry.getKey().equalsIgnoreCase("META-INF/MANIFEST.MF")) continue;
            Manifest manifest = new Manifest(new ByteArrayInputStream(entry.getValue()));
            return "true".equalsIgnoreCase(manifest.getMainAttributes().getValue("Multi-Release"));
        }
        return false;
    }

    private static boolean matchesEntry(String entry, String owner, boolean multiRelease) {
        if (entry.equals(owner + ".class")) return true;
        if (!multiRelease || !entry.startsWith(VERSIONED)) return false;
        int separator = entry.indexOf('/', VERSIONED.length());
        if (separator < 0 || !entry.substring(separator + 1).equals(owner + ".class")) return false;
        String release = entry.substring(VERSIONED.length(), separator);
        if (!release.matches("[1-9][0-9]*")) return false;
        try { return Integer.parseInt(release) >= 9; }
        catch (NumberFormatException ignored) { return false; }
    }

    /** 如果模块显式列举包，注入运行时后也将新包登记到同一模块。 */
    public static void updateModulePackages(Map<String, byte[]> entries) throws IOException {
        updateModulePackages(entries, BOOTSTRAP);
    }

    public static void updateModulePackages(Map<String, byte[]> entries, String bootstrapOwner) throws IOException {
        List<String> injected = new ArrayList<>();
        int slash = bootstrapOwner.lastIndexOf('/');
        if (slash > 0 && entries.containsKey(bootstrapOwner + ".class"))
            injected.add(bootstrapOwner.substring(0, slash));
        if (entries.containsKey("dev/jnz/license/DeviceFingerprint.class")) injected.add("dev/jnz/license");
        if (injected.isEmpty()) return;
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String name = entry.getKey();
            if (!name.equals("module-info.class") && !name.matches("META-INF/versions/[0-9]+/module-info\\.class"))
                continue;
            try {
                ClassReader reader = new ClassReader(entry.getValue());
                ClassNode owner = new ClassNode(ASM9);
                reader.accept(owner, 0);
                if (owner.module == null || owner.module.packages == null) continue;
                boolean changed = false;
                for (String pkg : injected) {
                    if (!owner.module.packages.contains(pkg)) {
                        owner.module.packages.add(pkg);
                        changed = true;
                    }
                }
                if (changed) {
                    ClassWriter writer = new ClassWriter(reader, 0);
                    owner.accept(writer);
                    entry.setValue(writer.toByteArray());
                }
            } catch (RuntimeException ex) {
                throw new IOException("Unable to update module package list in " + name, ex);
            }
        }
    }

    private static void processClass(String entry, byte[] bytes, int major, int minor,
                                     boolean multiRelease, MethodSelector selector,
                                     Map<String, byte[]> changes, Result result,
                                     String bootstrapOwner, String bootstrapMethod) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode owner = new ClassNode(ASM9);
        reader.accept(owner, 0);
        if (major < V1_5) {
            for (MethodNode method : owner.methods) {
                if (!selector.accepts(owner, method)) continue;
                result.skips.add(new String[] {entry, "", "", "class version below 49: " + major + "." + minor
                        + " (legacy class preserved)"});
                break;
            }
            return;
        }
        boolean versioned = entry.startsWith(VERSIONED);
        String classReason = null;
        if (!matchesEntry(entry, owner.name, multiRelease)) classReason = "non-runtime class entry";
        else if ((owner.access & ACC_ANNOTATION) != 0) classReason = "annotation declaration";
        else if ((owner.access & ACC_INTERFACE) != 0) classReason = "interface cannot declare native methods";
        else if ((owner.access & ACC_MODULE) != 0) classReason = "module descriptor";
        MethodNode initializer = null;
        for (MethodNode method : owner.methods) {
            if (!"<clinit>".equals(method.name)) continue;
            initializer = method;
            if (method.attrs != null && !method.attrs.isEmpty()) classReason = "unknown attribute on class initializer";
        }

        List<Program> pending = new ArrayList<>();
        Set<MethodNode> selected = new HashSet<>();
        Map<MethodNode, InitializerTail> initializerPlans = new IdentityHashMap<>();
        List<Initializer> initializerRecords = new ArrayList<>();
        Set<String> occupiedNames = new HashSet<>();
        for (MethodNode method : owner.methods) occupiedNames.add(method.name);
        int firstId = result.programs.size();
        for (MethodNode method : owner.methods) {
            if (!selector.accepts(owner, method)) continue;
            String reason = classReason != null ? classReason : ineligible(owner, method);
            if (reason == null) {
                try {
                    if (method.name.startsWith("<")) {
                        String helperName = "$" + Long.toUnsignedString(INITIALIZER_NAMES.nextLong(), 36);
                        while (occupiedNames.contains(helperName)) helperName += "$";
                        CompiledInitializer initializerPlan = compileInitializer(entry, owner, method, helperName,
                                firstId + pending.size(), firstId, occupiedNames);
                        InitializerTail plan = initializerPlan.plan;
                        initializerPlans.put(method, plan);
                        for (InitializerTail.Part part : plan.parts) {
                            occupiedNames.add(part.helper.name);
                            initializerRecords.add(new Initializer(entry, owner.name, method, plan, part));
                        }
                        for (Program program : initializerPlan.programs) registerLinks(occupiedNames, program);
                        pending.addAll(initializerPlan.programs);
                    } else {
                        Program program = Lowering.compile(entry, owner.name, method, firstId + pending.size(),
                                firstId, occupiedNames);
                        registerLinks(occupiedNames, program);
                        pending.add(program);
                        selected.add(method);
                    }
                    continue;
                } catch (Lowering.Unsupported ex) {
                    reason = ex.getMessage();
                }
            }
            result.skips.add(new String[] {versioned ? entry : owner.name, method.name, method.desc, reason});
        }
        if (pending.isEmpty()) return;
        byte[] rewritten = rewrite(reader, owner, selected, initializerPlans, initializer, firstId,
                pending, bootstrapOwner, bootstrapMethod);
        changes.put(entry, rewritten);
        result.programs.addAll(pending);
        result.initializers.addAll(initializerRecords);
    }

    private static void registerLinks(Set<String> occupied, Program program) {
        for (Linkage link : program.links) if (link.mode != Linkage.CONSTRUCT) occupied.add(link.name);
    }

    private static CompiledInitializer compileInitializer(String entry, ClassNode owner, MethodNode source, String name,
                                                          int id, int classId, Set<String> occupied)
            throws Lowering.Unsupported {
        try {
            InitializerTail plan = InitializerTail.plan(owner, source, name, occupied, true);
            return lowerInitializer(entry, owner.name, plan, id, classId, occupied);
        } catch (Lowering.Unsupported first) {
            try {
                InitializerTail plan = InitializerTail.plan(owner, source, name, occupied, false);
                return lowerInitializer(entry, owner.name, plan, id, classId, occupied);
            } catch (Lowering.Unsupported second) {
                if (first.getMessage().equals(second.getMessage())) throw first;
                throw new Lowering.Unsupported(first.getMessage() + " / tail fallback: " + second.getMessage());
            }
        }
    }

    private static CompiledInitializer lowerInitializer(String entry, String owner, InitializerTail plan,
                                                        int id, int classId, Set<String> occupied)
            throws Lowering.Unsupported {
        // 若一个区域失败，不登记同一初始化方法之前已经成功降低的区域。
        Set<String> local = new HashSet<>(occupied);
        List<Program> programs = new ArrayList<>();
        for (InitializerTail.Part part : plan.parts) local.add(part.helper.name);
        for (InitializerTail.Part part : plan.parts) {
            Program program = Lowering.compile(entry, owner, part.helper, id + programs.size(), classId, local);
            registerLinks(local, program);
            programs.add(program);
        }
        return new CompiledInitializer(plan, programs);
    }

    private static final class CompiledInitializer {
        final InitializerTail plan;
        final List<Program> programs;

        CompiledInitializer(InitializerTail plan, List<Program> programs) {
            this.plan = plan;
            this.programs = programs;
        }
    }

    private static String ineligible(ClassNode owner, MethodNode method) {
        if (method.name.startsWith("<") && !method.name.equals("<init>") && !method.name.equals("<clinit>"))
            return "unknown JVM special method";
        if ((method.access & ACC_NATIVE) != 0) return "already native";
        if ((method.access & ACC_ABSTRACT) != 0) return "abstract method";
        if ((owner.access & ACC_RECORD) != 0 && (
                ("equals".equals(method.name) && "(Ljava/lang/Object;)Z".equals(method.desc))
                || ("hashCode".equals(method.name) && "()I".equals(method.desc))
                || ("toString".equals(method.name) && "()Ljava/lang/String;".equals(method.desc))))
            return "record Object method";
        return null;
    }

    private static byte[] rewrite(ClassReader reader, final ClassNode owner, final Set<MethodNode> selected,
                                  final Map<MethodNode, InitializerTail> initializerPlans,
                                  final MethodNode initializer, final int firstId, final List<Program> programs,
                                  final String bootstrapOwner, final String bootstrapMethod) {
        // 重用原常量池与未变更的 method_info，以保留调试信息和未知属性中的索引。
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(ASM9, writer) {
            int methodIndex;
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                             String[] exceptions) {
                MethodNode method = owner.methods.get(methodIndex++);
                if (selected.contains(method)) {
                    MethodVisitor output = super.visitMethod(access | ACC_NATIVE, name, descriptor, signature, exceptions);
                    method.instructions.clear();
                    method.tryCatchBlocks.clear();
                    method.localVariables = null;
                    method.visibleLocalVariableAnnotations = null;
                    method.invisibleLocalVariableAnnotations = null;
                    method.accept(output);
                    return null;
                }
                MethodVisitor original = super.visitMethod(access, name, descriptor, signature, exceptions);
                MethodVisitor output = method == initializer ? bound(original) : original;
                InitializerTail plan = initializerPlans.get(method);
                if (plan == null) return output;
                plan.wrapper.accept(output);
                return null;
            }

            private MethodVisitor bound(MethodVisitor original) {
                return new MethodVisitor(ASM9, original) {
                    @Override public void visitCode() {
                        super.visitCode();
                        bind(mv, owner.name, firstId, bootstrapOwner, bootstrapMethod);
                    }
                    @Override public void visitMaxs(int stack, int locals) {
                        super.visitMaxs(Math.max(2, stack), locals);
                    }
                };
            }

            @Override public void visitEnd() {
                // 采用原始声明顺序输出辅助入口，随后输出连接桩。
                for (MethodNode original : owner.methods) {
                    InitializerTail plan = initializerPlans.get(original);
                    if (plan == null) continue;
                    for (InitializerTail.Part part : plan.parts) {
                        MethodNode helper = part.helper;
                        super.visitMethod(helper.access | ACC_NATIVE, helper.name, helper.desc, null, null).visitEnd();
                    }
                }
                for (Program program : programs) {
                    for (Linkage link : program.links) link.write(writer);
                }
                if (initializer == null) {
                    MethodVisitor m = super.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
                    m.visitCode();
                    bind(m, owner.name, firstId, bootstrapOwner, bootstrapMethod);
                    m.visitInsn(RETURN);
                    m.visitMaxs(2, 0);
                    m.visitEnd();
                }
                super.visitEnd();
            }
        }, 0);
        return writer.toByteArray();
    }

    private static void bind(MethodVisitor method, String owner, int firstId,
                             String bootstrapOwner, String bootstrapMethod) {
        method.visitLdcInsn(Type.getObjectType(owner));
        method.visitLdcInsn(firstId);
        method.visitMethodInsn(INVOKESTATIC, bootstrapOwner, bootstrapMethod, "(Ljava/lang/Class;I)V", false);
    }
}
