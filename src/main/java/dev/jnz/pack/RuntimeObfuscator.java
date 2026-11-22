package dev.jnz.pack;

import dev.jnz.protect.FlowMask;
import dev.jnz.protect.LiteralMask;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.MethodNode;

/** 以新的常量池输出随机命名的运行时实现。 */
final class RuntimeObfuscator {
    static final String BOOTSTRAP = Bundle.RUNTIME + "Bootstrap";
    static final String LITERAL_SITE = Bundle.RUNTIME + "LiteralSite";
    private static final String CODEC = Bundle.RUNTIME + "StringMask";
    private static final String NAMESPACE = Bundle.RUNTIME + "ResourceNamespace";
    private static final String MANIFEST = Bundle.RUNTIME + "ManifestRoot";
    private static final String BIND = "(Ljava/lang/Class;I)V";
    private static final String REVEAL = "(Ljava/lang/String;I)Ljava/lang/String;";
    static final List<String> TEMPLATES = templateNames();

    private RuntimeObfuscator() {}

    private static List<String> templateNames() {
        List<String> templates = new java.util.ArrayList<>(Arrays.asList(
                "Bootstrap", "BindingCache", "PackedResources", "NativeBridge", "PlatformTarget",
                "ModuleIndex", "NativeLibrary", "StringMask", "LiteralSite",
                "ResourceNamespace", "ResourcePaths", "ModuleName", "BinaryStream",
                "DigestCodec", "ManifestRoot", "CatalogRecord", "CatalogReader", "MetadataCatalog",
                "ClassRoute", "GroupRoute", "BridgeNames", "BridgeResolver", "BindingFailure",
                "HostCpu", "HostSystem", "LinuxAbi", "TempWorkspace", "ModuleBudget",
                "ModuleSource", "ModuleTransfer", "LoadedGroup", "StagingCleanup"));
        templates.replaceAll(name -> Bundle.RUNTIME + name);
        return Collections.unmodifiableList(templates);
    }

    static void emit(Map<String, byte[]> entries, Bundle.Layout layout, String catalogDigest) throws IOException {
        Map<String, String> templates = layout.templates();
        Map<String, ClassReader> classes = new HashMap<>();
        Map<String, String> methods = new HashMap<>();
        Map<String, String> fields = new HashMap<>();
        for (String type : TEMPLATES) {
            ClassReader reader = readTemplate(type);
            classes.put(type, reader);
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public FieldVisitor visitField(int access, String name, String desc,
                                                         String signature, Object value) {
                    fields.put(member(type, name, desc), layout.name('f'));
                    return null;
                }

                @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature,
                                                           String[] exceptions) {
                    if (!name.startsWith("<")) {
                        String mapped;
                        if (type.equals(BOOTSTRAP) && name.equals("bind") && desc.equals(BIND)) mapped = layout.bootstrapMethod();
                        else if (type.equals(LITERAL_SITE) && name.equals("link")) mapped = layout.literalBootstrap();
                        else if (type.equals(LITERAL_SITE) && name.equals("decode")) mapped = layout.literalDecode();
                        else mapped = layout.name('m');
                        methods.put(member(type, name, desc), mapped);
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        Remapper remap = new Remapper(Opcodes.ASM9) {
            @Override public String map(String internalName) {
                return templates.getOrDefault(internalName, internalName);
            }

            @Override public String mapFieldName(String owner, String name, String descriptor) {
                return fields.getOrDefault(member(owner, name, descriptor), name);
            }

            @Override public String mapMethodName(String owner, String name, String descriptor) {
                return methods.getOrDefault(member(owner, name, descriptor), name);
            }
        };
        for (String original : TEMPLATES) {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            ClassVisitor remapper = new ClassRemapper(writer, remap);
            ClassVisitor flow = new FlowVisitor(remapper, layout);
            ClassVisitor output = original.equals(CODEC) ? flow : new LiteralVisitor(flow, layout);
            if (original.equals(NAMESPACE) || original.equals(MANIFEST))
                output = new BuildConstantsVisitor(output, original, layout, catalogDigest);
            classes.get(original).accept(output, ClassReader.SKIP_DEBUG | ClassReader.EXPAND_FRAMES);
            Bundle.put(entries, templates.get(original) + ".class", writer.toByteArray());
        }
    }

    private static ClassReader readTemplate(String name) throws IOException {
        try (InputStream template = RuntimeObfuscator.class.getResourceAsStream("/" + name + ".class")) {
            if (template == null) throw new IOException("缺少运行时类模板 " + name);
            return new ClassReader(template);
        }
    }

    private static String member(String owner, String name, String desc) {
        return owner + '#' + name + '#' + desc;
    }

    private static final class BuildConstantsVisitor extends ClassVisitor {
        private final String owner, catalogDigest;
        private final Bundle.Layout layout;

        BuildConstantsVisitor(ClassVisitor output, String owner, Bundle.Layout layout, String catalogDigest) {
            super(Opcodes.ASM9, output);
            this.owner = owner;
            this.layout = layout;
            this.catalogDigest = catalogDigest;
        }

        @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            MethodVisitor output = super.visitMethod(access, name, desc, signature, exceptions);
            return new MethodVisitor(Opcodes.ASM9, output) {
                @Override public void visitLdcInsn(Object value) {
                    if (owner.equals(NAMESPACE) && desc.equals("()J") && value instanceof Long) {
                        if (name.equals("left")) value = layout.seedLeft;
                        else if (name.equals("right")) value = layout.seedRight;
                    } else if (owner.equals(MANIFEST) && name.equals("encoded") && "JN3_CATALOG_DIGEST".equals(value)) {
                        value = catalogDigest;
                    }
                    super.visitLdcInsn(value);
                }
            };
        }
    }

    private static final class LiteralVisitor extends ClassVisitor {
        private final Bundle.Layout layout;

        LiteralVisitor(ClassVisitor output, Bundle.Layout layout) {
            super(Opcodes.ASM9, output);
            this.layout = layout;
        }

        @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                                   String[] exceptions) {
            MethodVisitor output = super.visitMethod(access, name, descriptor, signature, exceptions);
            return new MethodVisitor(Opcodes.ASM9, output) {
                @Override public void visitLdcInsn(Object value) {
                    if (!(value instanceof String) || ((String) value).isEmpty()) {
                        super.visitLdcInsn(value);
                        return;
                    }
                    int seed = layout.seed();
                    super.visitLdcInsn(LiteralMask.encode((String) value, seed));
                    super.visitLdcInsn(seed);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, CODEC, "reveal", REVEAL, false);
                }
            };
        }
    }

    private static final class FlowVisitor extends ClassVisitor {
        private final Bundle.Layout layout;

        FlowVisitor(ClassVisitor output, Bundle.Layout layout) {
            super(Opcodes.ASM9, output);
            this.layout = layout;
        }

        @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                                   String[] exceptions) {
            MethodVisitor target = super.visitMethod(access, name, descriptor, signature, exceptions);
            return new MethodNode(Opcodes.ASM9, access, name, descriptor, signature, exceptions) {
                @Override public void visitEnd() {
                    FlowMask.apply(this, layout::seed, "strong");
                    accept(target);
                }
            };
        }
    }
}
