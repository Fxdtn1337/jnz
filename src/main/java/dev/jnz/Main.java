package dev.jnz;

import dev.jnz.backend.NativeCompiler;
import dev.jnz.backend.RustCompiler;
import dev.jnz.frontend.Frontend;
import dev.jnz.frontend.Program;
import dev.jnz.frontend.MethodSelector;
import dev.jnz.frontend.PreObfuscator;
import dev.jnz.gui.Workbench;
import dev.jnz.license.*;
import dev.jnz.pack.Bundle;
import dev.jnz.runtime.Bootstrap;
import java.io.*;
import java.nio.file.*;
import java.security.PublicKey;
import java.util.*;
import java.util.function.Consumer;

public final class Main {
    private Main() {}
    public static void main(String[] args) {
        try { execute(args, System.out::println); }
        catch (Exception | LinkageError error) {
            System.err.println("j2rust: " + error.getMessage());
            if (Boolean.getBoolean("jnz.debug")) error.printStackTrace();
            System.exit(1);
        }
    }

    public static void execute(String[] args, Consumer<String> log) throws Exception {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) {
            log.accept("J2Rust  •  JVM 字节码 → Rust / JNI\n"
                    + "  gui\n  inspect --input APP.jar [--include GLOB] [--exclude GLOB] [--report FILE] [--strict]\n"
                    + "  build --input APP.jar --output PROTECTED.jar [--include GLOB] [--exclude GLOB] [--strict]\n"
                    + "        [--targets host|TRIPLE,...] [--zig FILE] [--shard-size 1M] [--module-size 1M]\n"
                    + "        [--native-backend rust|zig] [--rustc FILE]\n"
                    + "        [--vm-coverage direct|full|partial] [--vm-include GLOB]\n"
                    + "        [--jni-bridges 4] [--pre-obfuscation full|off]\n"
                    + "        [--public-key SPKI --license FILE --features HEX] [--report FILE] [--work-dir DIR]\n"
                    + "  emit-rust --input APP.jar --output DIR [--include GLOB] [--exclude GLOB]\n"
                    + "  " + Licenses.usage().replace("\n", "\n  "));
            return;
        }
        if (args[0].equals("gui")) { Workbench.open(); return; }
        if (args[0].equals("license")) { Licenses.run(Arrays.copyOfRange(args, 1, args.length)); return; }
        Options opt = new Options(args);
        boolean emit = opt.command.equals("emit-rust");
        boolean build = opt.command.equals("build") || emit;
        Path input = opt.path("input");
        Path output = build ? opt.path("output") : null;
        Path beside = build ? output : input;
        Path report = opt.has("report") ? opt.path("report")
                : emit ? output.resolve("frontend.json")
                : beside.resolveSibling(beside.getFileName() + ".reports").resolve("frontend-skips.json");
        requireDifferent(input, report);
        if (build) {
            requireDifferent(input, output);
            requireDifferent(output, report);
        }
        boolean licensed = opt.has("public-key") || opt.has("license") || opt.has("features");
        byte[] key = null, envelope = null;
        long features = 0;
        if (licensed) {
            for (String option : Arrays.asList("license", "public-key")) {
                requireDifferent(opt.path(option), output);
                requireDifferent(opt.path(option), report);
            }
            PublicKey publicKey = LicenseFile.readPublic(opt.path("public-key"));
            key = LicenseFile.sec1(publicKey);
            envelope = LicenseFile.read(opt.path("license"));
            features = Licenses.featureMask(opt.get("features", "0"));
            // 可在构建机上为另一台离线设备打包；设备、时钟与功能在运行时校验。
        }
        Map<String,byte[]> entries = Archive.read(input);
        if (build) Archive.checkWritable(entries);
        Bundle.Layout layout = Bundle.plan();
        String include = opt.get("include", "*");
        String exclude = opt.get("exclude", "");
        PreObfuscator.Stats pre = new PreObfuscator.Stats();
        if (build) {
            String mode = opt.get("pre-obfuscation", "full");
            if (mode.equals("full")) pre = PreObfuscator.apply(entries, include, exclude, layout);
            else if (!mode.equals("off")) throw new IllegalArgumentException("--pre-obfuscation 使用 full 或 off");
            log.accept("预混淆 " + pre.literals + " 个字符串，" + pre.dynamicSites + " 个字面量调用点，" + pre.jumps + " 条跳边。");
        }
        Frontend.Result result;
        try {
            result = Frontend.transform(entries, include, exclude, opt.strict,
                    layout.bootstrapOwner(), layout.bootstrapMethod());
        } catch (Frontend.StrictFailure failure) {
            byte[] json = Report.render("strict-rejected", Collections.<Program>emptyList(), failure.skips);
            Archive.publish(report, out -> out.write(json));
            throw new IOException("严格模式拒绝 " + failure.skips.size() + " 项。报告 " + report, failure);
        }
        log.accept("可转换 " + (result.programs.size() - result.initializers.size()) + " 个普通方法，抽取 "
                + result.initializers.size() + " 个初始化器代码段，跳过 " + result.skips.size() + " 项。");
        if (!build || result.programs.isEmpty()) {
            byte[] json = Report.render("inspected", result.programs, result.skips, Collections.<Integer>emptySet(),
                    result.initializers, pre, 0, "");
            Archive.publish(report, out -> out.write(json));
            log.accept("报告 " + report);
            if (build) throw new IOException("没有符合条件且可转换的方法");
            return;
        }
        int chunk = opt.size("shard-size", 1 << 20);
        int budget = opt.size("module-size", 1 << 20);
        String backend = opt.get("native-backend", "rust");
        if (!backend.equals("rust") && (!backend.equals("zig") || emit))
            throw new IllegalArgumentException("原生后端使用 rust 或 zig，emit-rust 只生成 Rust");
        Set<Integer> direct = new HashSet<Integer>();
        String coverage = opt.get("vm-coverage", backend.equals("rust") ? "direct" : "full");
        if (coverage.equals("direct") && !opt.has("vm-include")) {
            for (Program p : result.programs) if (p.handlers.length == 0) direct.add(p.id);
            int fallback = result.programs.size() - direct.size();
            log.accept("直编 " + direct.size() + " 个方法，" + fallback + " 个带异常处理表的方法使用 VM。");
        } else if (coverage.equals("partial")) {
            String selected = opt.get("vm-include", "").trim();
            if (selected.isEmpty()) throw new IllegalArgumentException("部分 VM 模式必须指定 --vm-include");
            boolean matched = false;
            int fallback = 0;
            Map<String, Frontend.Initializer> origins = new HashMap<>();
            for (Frontend.Initializer item : result.initializers) origins.put(item.entry + "#" + item.helperName, item);
            for (Program p : result.programs) {
                Frontend.Initializer source = origins.get(p.entry + "#" + p.name);
                boolean selectedVm = source == null ? MethodSelector.matchesProgram(selected, p)
                        : MethodSelector.matchesProgram(selected, p.owner, source.name, source.descriptor);
                if (selectedVm) matched = true;
                else if (p.handlers.length == 0) direct.add(p.id);
                else fallback++;
            }
            if (!matched) throw new IllegalArgumentException("--vm-include 没有匹配到可转换的方法");
            if (fallback > 0) log.accept(fallback + " 个带异常处理表的方法使用 VM。");
        } else if (!coverage.equals("full") || opt.has("vm-include")) {
            throw new IllegalArgumentException("使用 --vm-coverage direct、full 或 partial --vm-include GLOB");
        }
        int requestedBridges = opt.bridgeCount();
        List<List<Program>> groups = group(result.programs, budget, requestedBridges);
        log.accept("JNI 桥接组 " + groups.size() + " 个，每个类使用同一个桥接组。");
        Set<String> targets = new LinkedHashSet<String>();
        for (String target : opt.get("targets", "host").split(",", -1)) {
            target = target.trim();
            if (target.equals("host")) target = Bootstrap.hostTarget();
            if (!target.matches("(x86_64|aarch64)-(windows-gnu|linux-gnu|linux-musl|macos)"))
                throw new IllegalArgumentException("不支持的目标 " + target);
            targets.add(target);
        }
        String zig = emit ? null : opt.zig();
        String rustc = !emit && backend.equals("rust") ? opt.rustc() : null;
        if (rustc != null) log.accept("Rust " + rustc);
        if (!emit) {
            log.accept((backend.equals("rust") ? "JNI 辅助库 Zig " : "Zig ") + zig);
            Bundle.addRuntime(entries, layout);
            if (licensed) Bundle.addLicense(entries, key, envelope, features);
        }
        Path workParent = emit ? output : opt.has("work-dir") ? opt.path("work-dir")
                : output.resolveSibling(output.getFileName() + ".build");
        Files.createDirectories(workParent);
        Path work = Files.createTempDirectory(workParent, "run-");
        log.accept("生成源码与编译日志 " + work);
        for (List<Program> group : groups) {
            int groupId = group.get(0).id;
            Set<Integer> classes = new LinkedHashSet<Integer>();
            for (Program p : group) classes.add(p.classId);
            Bundle.Group bridge = layout.group(groupId);
            if (emit) {
                Path sources = RustCompiler.emit(group, work.resolve("group-" + groupId), key, features, direct,
                        bridge.owner(), bridge.registerMethod(), bridge.verifyMethod());
                log.accept("Rust 源码 " + sources);
                continue;
            }
            Bundle.addGroup(entries, layout, groupId, classes);
            for (String target : targets) {
                log.accept("编译 " + target + "，模块 " + groupId + "，" + group.size() + " 个方法");
                Path directory = work.resolve(target).resolve("group-" + groupId);
                List<Path> library = backend.equals("rust")
                        ? RustCompiler.compile(group, directory, target, zig, rustc, key, features, direct,
                                bridge.owner(), bridge.registerMethod(), bridge.verifyMethod())
                        : NativeCompiler.compile(group, directory, target, zig, key, features, direct,
                                bridge.owner(), bridge.registerMethod(), bridge.verifyMethod());
                if (library.size() != 1) throw new IOException("模块产物数量异常 " + directory);
                Bundle.addModule(entries, target, groupId, library.get(0), chunk);
            }
        }
        if (!emit) Frontend.updateModulePackages(entries, layout.bootstrapOwner());
        byte[] json = Report.render(emit ? "rust-source" : "built", result.programs, result.skips, direct,
                result.initializers, pre, groups.size(), backend);
        // 报告单独交付，避免在分发 JAR 中附带完整的源码方法映射。
        Archive.publish(report, out -> out.write(json));
        if (emit) {
            log.accept("源码报告 " + report);
            return;
        }
        Archive.writeJar(output, entries);
        log.accept("输出 " + output + "（" + targets.size() + " 个平台，每平台 " + groups.size() + " 个模块）");
        log.accept("报告 " + report);
    }

    private static void requireDifferent(Path a, Path b) throws IOException {
        if (a.equals(b) || Files.exists(a) && Files.exists(b) && Files.isSameFile(a,b))
            throw new IOException("输入、输出和报告必须使用独立路径 " + a);
    }

    private static List<List<Program>> group(List<Program> programs, int budget, int requested) {
        Map<Integer,List<Program>> classes = new LinkedHashMap<Integer,List<Program>>();
        for (Program p : programs) classes.computeIfAbsent(p.classId, k -> new ArrayList<Program>()).add(p);
        List<List<Program>> groups = new ArrayList<List<Program>>();
        List<Program> current = null;
        String previous = null;
        long used = 0;
        for (List<Program> methods : classes.values()) {
            String owner = methods.get(0).owner;
            int slash = owner.lastIndexOf('/');
            String pkg = slash < 0 ? "" : owner.substring(0, slash);
            long size = 0;
            for (Program method : methods) size += 9L * (method.code.length / 3);
            if (current == null || !pkg.equals(previous) || used + size > budget) {
                current = new ArrayList<Program>();
                groups.add(current);
                used = 0;
            }
            previous = pkg;
            current.addAll(methods);
            used += size;
        }
        while (groups.size() < requested) {
            int selected = -1, largest = 1;
            for (int i = 0; i < groups.size(); i++) {
                int count = countClasses(groups.get(i));
                if (count > largest) {
                    selected = i;
                    largest = count;
                }
            }
            if (selected < 0) break;
            List<Program> methods = groups.get(selected);
            int middleClass = largest / 2;
            int split = 1;
            for (; split < methods.size(); split++) {
                if (methods.get(split).classId != methods.get(split - 1).classId && --middleClass == 0) break;
            }
            groups.set(selected, new ArrayList<>(methods.subList(0, split)));
            groups.add(selected + 1, new ArrayList<>(methods.subList(split, methods.size())));
        }
        return groups;
    }

    private static int countClasses(List<Program> methods) {
        int count = 0, previous = -1;
        for (Program method : methods) {
            if (method.classId != previous) count++;
            previous = method.classId;
        }
        return count;
    }
}
