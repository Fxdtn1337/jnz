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

/** 把前端方法映射到 Rust JNI ABI，并为直编方法生成基本块。 */
final class RustSource {
    private RustSource() {}

    static String generate(List<Program> programs, EncryptedPrograms data,
                           SecureRandom random, Path directory, Set<Integer> directIds, String register)
            throws IOException {
        StringBuilder out = new StringBuilder("mod vm;\nmod control;\nuse std::ffi::c_void;\nuse vm::{Bytes, Env, Frame, Program, Reference};\n");
        if (!directIds.isEmpty()) out.append("use vm::Vm;\n");
        byte[] mask = new byte[256];
        byte[] tags = new byte[256];
        random.nextBytes(mask);
        for (int i = 0; i < mask.length; i++) tags[i] = (byte) (data.decode[i] ^ mask[i]);
        addBytes(out, "KEY", data.key);
        addBytes(out, "MASK", mask);
        addBytes(out, "TAGS", tags);

        List<Program> shuffled = new ArrayList<>(programs);
        Collections.shuffle(shuffled, random);
        for (Program p : shuffled) {
            boolean direct = directIds.contains(p.id);
            if (direct) RustDirectSource.emit(out, p);
            else RustControlSource.emit(out, p.id, random);
            addThunk(out, p, direct);
        }

        Map<Integer, List<Program>> classes = new LinkedHashMap<>();
        for (Program p : programs) classes.computeIfAbsent(p.classId, id -> new ArrayList<>()).add(p);
        out.append("#[no_mangle]\npub unsafe extern \"system\" fn ").append(register)
           .append("(env: Env, _: Reference, owner: Reference, id: i32) {\n")
           .append("    match id {\n");
        for (Map.Entry<Integer, List<Program>> entry : classes.entrySet()) {
            int id = entry.getKey();
            List<Program> methods = entry.getValue();
            data.writeNames(methods, directory);
            out.append("        ").append(id).append(" => {\n            let thunks = [");
            for (Program p : methods) out.append("thunk_").append(p.id).append(" as *const c_void, ");
            out.append("];\n            vm::jnz_rust_bind(env, owner, ").append(id)
               .append(", ").append(bytes("n", id)).append(", KEY.as_ptr(), thunks.as_ptr(), thunks.len());\n")
               .append("        }\n");
        }
        return out.append("        _ => vm::jnz_rust_bind_error(env),\n    }\n}\n").toString();
    }

    private static void addBytes(StringBuilder out, String name, byte[] data) {
        out.append("static ").append(name).append(": [u8; ").append(data.length).append("] = [");
        for (int i = 0; i < data.length; i++) {
            if ((i & 15) == 0) out.append("\n    ");
            out.append(data[i] & 255).append(", ");
        }
        out.append("\n];\n");
    }

    private static void addThunk(StringBuilder out, Program p, boolean direct) {
        Type[] arguments = Type.getArgumentTypes(p.desc);
        char result = kind(Type.getReturnType(p.desc));
        out.append("unsafe extern \"system\" fn thunk_").append(p.id).append("(env: Env, ")
           .append(p.isStatic ? "_" : "receiver").append(": Reference");
        StringBuilder slots = new StringBuilder("[");
        if (!p.isStatic) slots.append("receiver as usize as u64, ");
        for (int i = 0; i < arguments.length; i++) {
            char type = kind(arguments[i]);
            String argument = "a" + i;
            out.append(", ").append(argument).append(": ").append(rustType(type));
            slots.append(argument);
            if (type == 'L') slots.append(" as usize as u64, ");
            else if (type == 'J') slots.append(" as u64, 0, ");
            else if (type == 'D') slots.append(".to_bits(), 0, ");
            else if (type == 'F') slots.append(".to_bits() as u64, ");
            else slots.append(" as i32 as u32 as u64, ");
        }
        out.append(") -> ").append(rustType(result)).append(" {\n    let args: [u64; ")
           .append(argumentSlots(p.isStatic, arguments)).append("] = ").append(slots).append("];\n")
           .append("    let program = Program { id: ").append(p.id).append(", locals: ").append(p.maxLocals)
           .append(", stack: ").append(p.maxStack).append(",\n        body: ").append(direct ? empty() : bytes("p", p.id))
           .append(", links: ").append(p.links.isEmpty() ? empty() : bytes("g", p.id))
           .append(", handlers: ").append(p.handlers.length == 0 ? empty() : bytes("h", p.id))
           .append(",\n        key: KEY.as_ptr(), mask: MASK.as_ptr(), tags: TAGS.as_ptr() };\n")
           .append(result == 'V' ? "    let _ = " : "    let value = ")
           .append("vm::jnz_rust_invoke(env, &program, args.as_ptr(), args.len(), ")
           .append(direct ? "run_" + p.id : "layered_" + p.id).append(");\n");
        switch (result) {
            case 'V': break;
            case 'Z': out.append("    (value & 1) as u8\n"); break;
            case 'F': out.append("    f32::from_bits(value as u32)\n"); break;
            case 'D': out.append("    f64::from_bits(value)\n"); break;
            case 'L': out.append("    value as usize as Reference\n"); break;
            default: out.append("    value as ").append(rustType(result)).append('\n');
        }
        out.append("}\n");
    }

    private static String bytes(String prefix, int id) { return "Bytes::new(include_bytes!(\"" + prefix + id + ".bin\"))"; }
    private static String empty() { return "Bytes::new(&[])"; }

    private static int argumentSlots(boolean isStatic, Type[] args) {
        int slots = isStatic ? 0 : 1;
        for (Type arg : args) slots += arg.getSize();
        return slots;
    }

    private static char kind(Type type) {
        return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY ? 'L' : type.getDescriptor().charAt(0);
    }

    private static String rustType(char type) {
        switch (type) {
            case 'Z': return "u8";
            case 'B': return "i8";
            case 'C': return "u16";
            case 'S': return "i16";
            case 'I': return "i32";
            case 'J': return "i64";
            case 'F': return "f32";
            case 'D': return "f64";
            case 'L': return "Reference";
            case 'V': return "()";
            default: throw new IllegalArgumentException("Unsupported JVM type: " + type);
        }
    }
}
