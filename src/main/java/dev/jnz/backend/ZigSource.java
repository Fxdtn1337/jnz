package dev.jnz.backend;

import dev.jnz.frontend.Program;
import org.objectweb.asm.Type;
import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 生成 JNI 签名对应的薄桩；业务指令只进入经过认证加密的 VM 程序。 */
final class ZigSource {
    private ZigSource() {}

    static String generate(List<Program> programs, EncryptedPrograms encrypted,
                           SecureRandom random, Path directory, Set<Integer> directIds,
                           String registerSymbol) throws IOException {
        StringBuilder out = new StringBuilder(
                "const vm = @import(\"vm.zig\");\nconst jni = @import(\"jni.zig\");\n");
        byte[] mask = new byte[256], values = new byte[256];
        random.nextBytes(mask);
        for (int i = 0; i < 256; i++) values[i] = (byte) (encrypted.decode[i] ^ mask[i]);
        bytes(out, "key", encrypted.key);
        bytes(out, "mask", mask);
        bytes(out, "values", values);
        out.append("const decode: vm.Decode = .{ .mask = &mask, .values = &values };\n");
        List<Program> layout = new ArrayList<>(programs);
        Collections.shuffle(layout, random);
        for (Program program : layout) {
            boolean direct = directIds.contains(program.id);
            if (direct) DirectSource.emit(out, program);
            thunk(out, program, direct);
        }

        Map<Integer, List<Program>> owners = new LinkedHashMap<>();
        for (Program program : programs) owners.computeIfAbsent(program.classId, k -> new ArrayList<>()).add(program);
        out.append("export fn ").append(registerSymbol)
           .append("(env: jni.Env, _: jni.Ref, cls: jni.Ref, id: i32) callconv(.c) void {\n")
           .append("    switch (id) {\n");
        for (List<Program> methods : owners.values()) {
            int classId = methods.get(0).classId;
            encrypted.writeNames(methods, directory);
            out.append("        ").append(classId).append(" => jni.registerPacked(env, cls, ").append(classId)
               .append(", @embedFile(\"n").append(classId).append(".bin\"), &key, &.{");
            for (Program p : methods) out.append("&thunk_").append(p.id).append(',');
            out.append("}),\n");
        }
        return out.append("        else => jni.raise(env, \"java/lang/UnsatisfiedLinkError\", \"unknown binding id\"),\n")
                  .append("    }\n}\n").toString();
    }

    private static void bytes(StringBuilder out, String name, byte[] data) {
        out.append("const ").append(name).append(" = [_]u8{");
        for (int i = 0; i < data.length; i++) {
            if ((i & 15) == 0) out.append("\n    ");
            out.append(data[i] & 255).append(", ");
        }
        out.append("\n};\n");
    }

    private static void thunk(StringBuilder out, Program p, boolean direct) throws IOException {
        Type[] args = Type.getArgumentTypes(p.desc);
        char result = kind(Type.getReturnType(p.desc));
        out.append("fn thunk_").append(p.id).append("(env: jni.Env, ").append(p.isStatic ? "_" : "receiver").append(": jni.Ref");
        StringBuilder slots = new StringBuilder("&[_]u64{");
        if (!p.isStatic) slots.append("vm.fromRef(receiver),");
        for (int i = 0; i < args.length; i++) {
            char type = kind(args[i]);
            out.append(", a").append(i).append(": ").append(zigType(type));
            if (type == 'J') slots.append("vm.fromI64(a").append(i).append("),0,");
            else if (type == 'D') slots.append("vm.fromFloat(a").append(i).append("),0,");
            else if (type == 'F') slots.append("vm.fromFloat(a").append(i).append("),");
            else if (type == 'L') slots.append("vm.fromRef(a").append(i).append("),");
            else slots.append("vm.fromI32(a").append(i).append("),");
        }
        slots.append("}");
        out.append(") callconv(.c) ").append(zigType(result)).append(" {\n    ")
           .append(result == 'V' ? "_ = " : "const r = ");
        String links = p.links.isEmpty() ? "\"\"" : "@embedFile(\"g" + p.id + ".bin\")";
        String handlers = p.handlers.length == 0 ? "\"\"" : "@embedFile(\"h" + p.id + ".bin\")";
        if (direct) {
            out.append("vm.invokeDirect(env, ").append(p.id).append(", ").append(p.maxLocals).append(", ").append(p.maxStack)
               .append(", ").append(links).append(", &key, ").append(slots).append(", d").append(p.id).append(");\n");
        } else {
            out.append("vm.invoke(env, .{ .id = ").append(p.id).append(", .locals = ").append(p.maxLocals)
               .append(", .stack = ").append(p.maxStack).append(", .blob = @embedFile(\"p").append(p.id)
               .append(".bin\"), .links = ").append(links).append(", .handlers = ").append(handlers)
               .append(" }, &key, decode, ").append(slots).append(");\n");
        }
        if (result == 'Z') out.append("    return @intCast(r & 1);\n");
        else if (result == 'F' || result == 'D') out.append("    return vm.asFloat(").append(zigType(result)).append(", r);\n");
        else if (result == 'L') out.append("    return vm.asRef(r);\n");
        else if (result != 'V') out.append("    return vm.asInt(").append(zigType(result)).append(", r);\n");
        out.append("}\n");
    }

    private static char kind(Type type) {
        return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY ? 'L' : type.getDescriptor().charAt(0);
    }

    private static String zigType(char type) throws IOException {
        switch (type) {
            case 'Z': return "u8";
            case 'B': return "i8";
            case 'C': return "u16";
            case 'S': return "i16";
            case 'I': return "i32";
            case 'J': return "i64";
            case 'F': return "f32";
            case 'D': return "f64";
            case 'L': return "jni.Ref";
            case 'V': return "void";
            default: throw new IOException("Unsupported JVM type: " + type);
        }
    }
}
