package dev.jnz.frontend;

import java.util.HashMap;
import java.util.Map;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.objectweb.asm.Opcodes.*;

/** 只直接解析当前定义类的确切声明，避免 JNI 查找沿继承链产生不同的链接行为。 */
final class NativeMembers {
    static final NativeMembers EMPTY = new NativeMembers(null);
    private final String owner;
    private final Map<String, Map<String, Integer>> fields = new HashMap<>();
    private final Map<String, Map<String, Integer>> methods = new HashMap<>();

    NativeMembers(ClassNode type) {
        owner = type == null || (type.access & ACC_INTERFACE) != 0 ? null : type.name;
        if (owner == null) return;
        for (FieldNode field : type.fields)
            fields.computeIfAbsent(field.name, name -> new HashMap<>()).put(field.desc, field.access);
        for (MethodNode method : type.methods)
            methods.computeIfAbsent(method.name, name -> new HashMap<>()).put(method.desc, method.access);
    }

    boolean field(FieldInsnNode field) {
        if (!field.owner.equals(owner)) return false;
        Integer flags = access(fields, field.name, field.desc);
        // Retain the JVM instruction for volatile fields so its access mode and barriers stay explicit.
        if (flags == null || (flags & ACC_VOLATILE) != 0) return false;
        int opcode = field.getOpcode();
        boolean write = opcode == PUTFIELD || opcode == PUTSTATIC;
        return (!write || (flags & ACC_FINAL) == 0)
                && ((flags & ACC_STATIC) != 0) == (opcode == GETSTATIC || opcode == PUTSTATIC);
    }

    boolean method(MethodInsnNode method, Type[] params) {
        if (!method.owner.equals(owner) || method.itf || method.name.startsWith("<")) return false;
        Integer flags = access(methods, method.name, method.desc);
        if (flags == null) return false;
        for (Type type : params) {
            if (type.getSort() == Type.ARRAY || type.getSort() == Type.OBJECT) return false;
        }
        switch (method.getOpcode()) {
            case INVOKESTATIC: return (flags & ACC_STATIC) != 0;
            case INVOKEVIRTUAL: return (flags & (ACC_STATIC | ACC_PRIVATE)) == 0;
            case INVOKESPECIAL: return (flags & (ACC_STATIC | ACC_PRIVATE)) == ACC_PRIVATE;
            default: return false;
        }
    }

    private static Integer access(Map<String, Map<String, Integer>> members, String name, String desc) {
        Map<String, Integer> overloads = members.get(name);
        return overloads == null ? null : overloads.get(desc);
    }
}
