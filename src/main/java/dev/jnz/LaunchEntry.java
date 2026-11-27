package dev.jnz;

import dev.jnz.frontend.Program;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/** Resolves the application entry declared by a JAR without loading input classes. */
public final class LaunchEntry {
    private static final String MANIFEST = "META-INF/MANIFEST.MF";
    private static final String ECLIPSE = "org.eclipse.jdt.internal.jarinjarloader.JarRsrcLoader";
    private static final String ECLIPSE_MAIN = "Rsrc-Main-Class";
    private static final String MAIN = "main";
    private static final String ARGS = "([Ljava/lang/String;)V";
    private static final String NO_ARGS = "()V";
    private static final String VERSIONED = "META-INF/versions/";
    private static final int MODERN_MAIN = 25;
    private static final int LAST_SUPPORTED_RELEASE = 27;

    public final String binaryName, launcher, owner, descriptor, problem;
    public final boolean required;

    private final String manifestPath;
    private final Manifest manifest;
    private final boolean rewrite, eclipse;
    private final List<Target> targets;

    private LaunchEntry(String binaryName, String launcher, boolean required, boolean rewrite, boolean eclipse,
                        String manifestPath, Manifest manifest, List<Target> targets, String problem) {
        this.binaryName = binaryName;
        this.launcher = launcher;
        this.required = required;
        this.rewrite = rewrite;
        this.eclipse = eclipse;
        this.manifestPath = manifestPath;
        this.manifest = manifest;
        this.targets = targets;
        this.owner = targets.isEmpty() ? binaryName.replace('.', '/') : targets.get(0).owner;
        this.descriptor = targets.isEmpty() ? null : targets.get(0).descriptor;
        this.problem = problem;
    }

    /** A null selection reports an existing entry. An explicit selection requires native conversion. */
    public static LaunchEntry inspect(Map<String, byte[]> entries, String selection) throws IOException {
        String manifestPath = locateManifest(entries);
        Manifest manifest = manifestPath == null ? new Manifest()
                : new Manifest(new ByteArrayInputStream(entries.get(manifestPath)));
        Attributes attributes = manifest.getMainAttributes();
        String launcher = trim(attributes.getValue(Attributes.Name.MAIN_CLASS));
        boolean eclipse = ECLIPSE.equals(launcher);
        String declared = eclipse ? trim(attributes.getValue(ECLIPSE_MAIN)) : launcher;
        boolean required = selection != null;
        boolean override = required && !selection.equalsIgnoreCase("auto");
        String binaryName = override ? selection.trim().replace('/', '.') : declared;

        if (binaryName == null) {
            if (required) throw new IOException("JAR 未声明应用主类，请用 --main-class 指定类名");
            return null;
        }
        boolean rewrite = override && !binaryName.equals(declared);
        boolean multiRelease = "true".equalsIgnoreCase(attributes.getValue("Multi-Release"));
        List<Target> targets = new ArrayList<>();
        String problem = validName(binaryName) && !(eclipse && ECLIPSE.equals(binaryName)) ? null : "无效的应用主类";
        if (problem == null) {
            try {
                targets = new Resolver(entries, multiRelease).find(binaryName.replace('.', '/'), eclipse);
                if (targets.isEmpty()) problem = "未找到包内可启动的 main 方法";
            } catch (IllegalArgumentException | IndexOutOfBoundsException error) {
                problem = "无法解析主类或其父类";
            } catch (IOException error) {
                problem = error.getMessage();
            }
        }
        if (required && problem != null) throw new IOException("主类 " + binaryName + "，" + problem);
        return new LaunchEntry(binaryName, eclipse ? launcher : binaryName, required, rewrite, eclipse,
                manifestPath, manifest, targets, problem);
    }

    /** Adds only the actual main methods. Explicit excludes still take precedence in the frontend. */
    public String include(String includes) {
        if (!required || includes == null || includes.trim().isEmpty()) return includes;
        Set<String> result = new LinkedHashSet<>();
        for (String rule : includes.split(",")) {
            String item = rule.trim();
            if (!item.isEmpty()) result.add(item);
        }
        if (result.isEmpty() || result.contains("*")) return includes;
        for (Target target : targets) result.add(target.owner + "#" + MAIN + target.descriptor);
        return String.join(",", result);
    }

    public boolean matches(String owner, String name, String descriptor) {
        if (!MAIN.equals(name)) return false;
        for (Target target : targets)
            if (target.owner.equals(owner) && target.descriptor.equals(descriptor)) return true;
        return false;
    }

    public boolean converted(List<Program> programs) {
        if (targets.isEmpty()) return false;
        for (Target target : targets) if (programs.stream().noneMatch(target::matches)) return false;
        return true;
    }

    public int javaBridges(List<Program> programs) {
        return programs.stream().filter(p -> targets.stream().anyMatch(target -> target.matches(p)))
                .mapToInt(Program::javaBridges).sum();
    }

    public void verify(List<Program> programs) throws IOException {
        if (!required || converted(programs)) return;
        for (Target target : targets) {
            if (programs.stream().noneMatch(target::matches)) {
                throw new IOException("主类入口未转换为 native " + target.entry + "#main" + target.descriptor
                        + "，请检查排除规则和跳过报告");
            }
        }
    }

    /** Applies an override to the output entries. Autodetection leaves the original manifest bytes intact. */
    public void apply(Map<String, byte[]> entries) throws IOException {
        if (!rewrite) return;
        Attributes attributes = manifest.getMainAttributes();
        if (attributes.getValue(Attributes.Name.MANIFEST_VERSION) == null)
            attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (eclipse) attributes.putValue(ECLIPSE_MAIN, binaryName);
        else attributes.put(Attributes.Name.MAIN_CLASS, binaryName);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        manifest.write(output);
        if (manifestPath != null && !MANIFEST.equals(manifestPath)) entries.remove(manifestPath);
        entries.put(MANIFEST, output.toByteArray());
    }

    private static String locateManifest(Map<String, byte[]> entries) {
        if (entries.containsKey(MANIFEST)) return MANIFEST;
        for (String name : entries.keySet()) if (MANIFEST.equalsIgnoreCase(name)) return name;
        return null;
    }

    private static String trim(String text) {
        return text == null || text.trim().isEmpty() ? null : text.trim();
    }

    private static boolean validName(String name) {
        if (name.isEmpty() || name.startsWith(".") || name.endsWith(".") || name.contains("..")) return false;
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isWhitespace(ch) || Character.isISOControl(ch) || "/\\;[]#(),?*<>@".indexOf(ch) >= 0)
                return false;
        }
        return true;
    }

    private static final class Target {
        final String entry, owner, descriptor;

        Target(String entry, String owner, String descriptor) {
            this.entry = entry;
            this.owner = owner;
            this.descriptor = descriptor;
        }

        boolean matches(Program program) {
            return entry.equals(program.entry) && owner.equals(program.owner)
                    && MAIN.equals(program.name) && descriptor.equals(program.desc);
        }
    }

    private static final class Resolver {
        private final Map<String, byte[]> entries;
        private final NavigableSet<Integer> overlays = new TreeSet<>();
        private final Map<String, ClassNode> classes = new HashMap<>();

        Resolver(Map<String, byte[]> entries, boolean multiRelease) {
            this.entries = entries;
            if (!multiRelease) return;
            for (String entry : entries.keySet()) {
                if (!entry.startsWith(VERSIONED) || !entry.endsWith(".class")) continue;
                int separator = entry.indexOf('/', VERSIONED.length());
                if (separator < 0) continue;
                String release = entry.substring(VERSIONED.length(), separator);
                if (!release.matches("[1-9][0-9]?")) continue;
                int number = Integer.parseInt(release);
                if (number >= 9 && number <= LAST_SUPPORTED_RELEASE) overlays.add(number);
            }
        }

        List<Target> find(String type, boolean eclipse) throws IOException {
            Set<Integer> checkpoints = new TreeSet<>(overlays);
            checkpoints.add(8);
            checkpoints.add(MODERN_MAIN);
            for (int release : new ArrayList<>(checkpoints)) {
                String entry = entryOf(type, release);
                if (entry == null) continue;
                int min = minimumJava(read(entry, type));
                if (min >= 8 && min <= LAST_SUPPORTED_RELEASE) checkpoints.add(min);
            }
            Map<String, Target> found = new LinkedHashMap<>();
            for (int release : checkpoints) {
                String entry = entryOf(type, release);
                if (entry == null) continue;
                ClassNode launch = read(entry, type);
                if (minimumJava(launch) > release) {
                    if (!found.isEmpty()) throw new IOException("覆盖主类 " + entry + " 需要更高版本的 Java");
                    continue;
                }
                boolean modern = !eclipse && release >= MODERN_MAIN;
                Target target = declaredOrInherited(type, release, ARGS, true, modern);
                if (target == null && modern)
                    target = declaredOrInherited(type, release, ARGS, false, true);
                if (target == null && modern)
                    target = declaredOrInherited(type, release, NO_ARGS, false, true);
                if (target == null) {
                    if (!found.isEmpty()) throw new IOException("覆盖主类 " + entry + " 未声明可启动的 main 方法");
                    continue;
                }
                found.putIfAbsent(target.entry + target.descriptor, target);
            }
            return new ArrayList<>(found.values());
        }

        private Target declaredOrInherited(String launchType, int release, String descriptor, boolean publicOnly,
                                           boolean modern) throws IOException {
            Set<String> seen = new HashSet<>();
            String type = launchType;
            while (type != null && seen.add(type)) {
                String entry = entryOf(type, release);
                if (entry == null) break;
                ClassNode data = read(entry, type);
                for (MethodNode method : data.methods) {
                    if (!MAIN.equals(method.name) || !descriptor.equals(method.desc)) continue;
                    int access = method.access;
                    if ((access & Opcodes.ACC_PRIVATE) != 0 || (publicOnly && (access & Opcodes.ACC_PUBLIC) == 0))
                        continue;
                    if (!launchType.equals(type) && (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0
                            && !samePackage(launchType, type)) continue;
                    if ((access & Opcodes.ACC_STATIC) == 0) {
                        if (!modern) continue;
                        requireInstanceConstructor(read(entryOf(launchType, release), launchType));
                    }
                    if ((access & Opcodes.ACC_ABSTRACT) != 0) continue;
                    return new Target(entry, type, descriptor);
                }
                type = data.superName;
            }
            return null;
        }

        private String entryOf(String type, int release) {
            String path = type + ".class";
            for (int overlay : overlays.headSet(release, true).descendingSet()) {
                String candidate = VERSIONED + overlay + "/" + path;
                if (entries.containsKey(candidate)) return candidate;
            }
            return entries.containsKey(path) ? path : null;
        }

        private ClassNode read(String entry, String type) {
            return classes.computeIfAbsent(entry, name -> {
                ClassNode node = new ClassNode(Opcodes.ASM9);
                new ClassReader(entries.get(name)).accept(node,
                        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                if (!node.name.equals(type)) throw new IllegalArgumentException("class entry name does not match");
                return node;
            });
        }

        private static int minimumJava(ClassNode node) {
            return (node.version & 0xffff) - 44;
        }

        private static boolean samePackage(String an, String bn) {
            int a = an.lastIndexOf('/'), b = bn.lastIndexOf('/');
            return a == b && (a < 0 || an.regionMatches(0, bn, 0, a));
        }

        private static void requireInstanceConstructor(ClassNode node) throws IOException {
            if ((node.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0)
                throw new IOException("实例 main 要求可实例化的主类");
            for (InnerClassNode nested : node.innerClasses)
                if (node.name.equals(nested.name) && nested.outerName != null && (nested.access & Opcodes.ACC_STATIC) == 0)
                    throw new IOException("实例 main 不支持非静态内部类");
            for (MethodNode method : node.methods)
                if (method.name.equals("<init>") && method.desc.equals(NO_ARGS) && (method.access & Opcodes.ACC_PRIVATE) == 0)
                    return;
            throw new IOException("实例 main 要求非 private 的无参构造器");
        }
    }
}
