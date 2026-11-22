package dev.jnz.frontend;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

/** 让 JVM 在原类的访问上下文中解析一条符号指令。方法的控制流和数据流仍由 native 执行。 */
public final class Linkage {
    public static final int OPCODE = 254;
    public static final int JVM_OPERATION = 0, ALLOCATE = 1, CONSTRUCT = 2;
    private static final Type NONE = Type.VOID_TYPE;
    private static final Type OBJECT = Type.getType(Object.class);
    public final String name, descriptor;
    public final int mode;
    private final AbstractInsnNode instruction;
    private final Type output;
    private final Type[] inputs;

    Linkage(String name, AbstractInsnNode instruction, Type output, Type... inputs) {
        this(JVM_OPERATION, name, Type.getMethodDescriptor(output, inputs), instruction, output, inputs);
    }

    private Linkage(int mode, String name, String descriptor, AbstractInsnNode instruction, Type output, Type... inputs) {
        this.mode = mode;
        this.name = name;
        this.instruction = instruction;
        this.output = output;
        this.inputs = inputs;
        this.descriptor = descriptor;
    }

    public Type[] argumentTypes() { return inputs.clone(); }
    public Type resultType() { return output; }

    static Linkage create(AbstractInsnNode instruction, String caller, String name, Set<String> occupied) {
        int op = instruction.getOpcode();
        while (occupied.contains(name)) name += "$";
        Linkage array = ArrayLinkage.create(name, instruction);
        if (array != null) return array;
        if (instruction instanceof FieldInsnNode) {
            FieldInsnNode field = (FieldInsnNode) instruction;
            Type owner = Type.getObjectType(field.owner);
            Type value = Type.getType(field.desc);
            switch (op) {
                case GETSTATIC: return new Linkage(name, instruction, value);
                case GETFIELD: return new Linkage(name, instruction, value, owner);
                case PUTSTATIC: return new Linkage(name, instruction, NONE, value);
                case PUTFIELD: return new Linkage(name, instruction, NONE, owner, value);
                default: return null;
            }
        }
        if (instruction instanceof MethodInsnNode) {
            MethodInsnNode method = (MethodInsnNode) instruction;
            if (isContextSensitive(method)) return null;
            Type[] params = Type.getArgumentTypes(method.desc);
            Type result = Type.getReturnType(method.desc);
            if (op == INVOKESTATIC) return new Linkage(name, instruction, result, params);
            Type[] inputs = new Type[params.length + 1];
            boolean constructor = op == INVOKESPECIAL && method.name.equals("<init>");
            inputs[0] = Type.getObjectType(op == INVOKESPECIAL ? caller : method.owner);
            System.arraycopy(params, 0, inputs, 1, params.length);
            if (constructor) return new Linkage(CONSTRUCT, method.owner, method.desc, null, NONE, inputs);
            return new Linkage(name, instruction, result, inputs);
        }
        if (op == NEW) return classToken(name, ((TypeInsnNode) instruction).desc, ALLOCATE);
        if (op == CHECKCAST) return new Linkage(name, instruction, Type.getObjectType(((TypeInsnNode) instruction).desc), OBJECT);
        if (op == INSTANCEOF) return new Linkage(name, instruction, Type.INT_TYPE, OBJECT);
        if (instruction instanceof InvokeDynamicInsnNode) {
            InvokeDynamicInsnNode call = (InvokeDynamicInsnNode) instruction;
            return new Linkage(name, instruction, Type.getReturnType(call.desc), Type.getArgumentTypes(call.desc));
        }
        if (instruction instanceof LdcInsnNode) {
            Object value = ((LdcInsnNode) instruction).cst;
            if (value instanceof String) return new Linkage(name, instruction, Type.getType(String.class));
            if (value instanceof ConstantDynamic)
                return new Linkage(name, instruction, Type.getType(((ConstantDynamic) value).getDescriptor()));
            if (value instanceof Handle)
                return new Linkage(name, instruction, Type.getObjectType("java/lang/invoke/MethodHandle"));
            if (value instanceof Type) {
                int sort = ((Type) value).getSort();
                if (sort == Type.OBJECT || sort == Type.ARRAY)
                    return new Linkage(name, instruction, Type.getType(Class.class));
                if (sort == Type.METHOD)
                    return new Linkage(name, instruction, Type.getObjectType("java/lang/invoke/MethodType"));
            }
        }
        return null;
    }

    static Linkage classToken(String name, String owner, int mode) {
        Type type = Type.getType(Class.class);
        return new Linkage(mode, name, Type.getMethodDescriptor(type), new LdcInsnNode(Type.getObjectType(owner)), type);
    }

    private static boolean isContextSensitive(MethodInsnNode method) {
        if (method.getOpcode() == INVOKEVIRTUAL) {
            if (method.owner.equals("java/lang/invoke/MethodHandle")
                    && (method.name.equals("invokeExact") || method.name.equals("invoke"))) return false;
            if (method.owner.equals("java/lang/invoke/VarHandle") && isVarHandleAccess(method.name)) return false;
        }
        // JNI adds one native frame and this gateway adds a Java frame; keep known stack-sensitive calls in Java.
        return method.owner.startsWith("java/lang/invoke/") || method.owner.startsWith("java/lang/reflect/")
                || method.owner.equals("java/lang/Class") || method.owner.equals("java/lang/ClassLoader")
                || method.owner.startsWith("jdk/internal/reflect/") || method.owner.startsWith("sun/reflect/");
    }

    /** VarHandle 探测和更新使用调用点描述符，交给同一个 JVM 符号指令链接。 */
    private static boolean isVarHandleAccess(String name) {
        switch (name) {
            case "get": case "set":
            case "getVolatile": case "setVolatile":
            case "getOpaque": case "setOpaque":
            case "getAcquire": case "setRelease":
            case "compareAndSet":
            case "compareAndExchange": case "compareAndExchangeAcquire": case "compareAndExchangeRelease":
            case "weakCompareAndSetPlain": case "weakCompareAndSet":
            case "weakCompareAndSetAcquire": case "weakCompareAndSetRelease":
            case "getAndSet": case "getAndSetAcquire": case "getAndSetRelease":
            case "getAndAdd": case "getAndAddAcquire": case "getAndAddRelease":
            case "getAndBitwiseOr": case "getAndBitwiseOrAcquire": case "getAndBitwiseOrRelease":
            case "getAndBitwiseAnd": case "getAndBitwiseAndAcquire": case "getAndBitwiseAndRelease":
            case "getAndBitwiseXor": case "getAndBitwiseXorAcquire": case "getAndBitwiseXorRelease":
                return true;
            default:
                return false;
        }
    }

    void write(ClassVisitor outputClass) {
        if (mode == CONSTRUCT) return;
        MethodVisitor method = outputClass.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
                name, descriptor, null, null);
        method.visitCode();
        if (instruction.getOpcode() == BALOAD || instruction.getOpcode() == BASTORE) {
            int slots = ArrayLinkage.writeByteOrBoolean(method, instruction.getOpcode());
            method.visitMaxs(slots, slots);
            method.visitEnd();
            return;
        }
        int locals = 0;
        for (Type arg : inputs) {
            method.visitVarInsn(arg.getOpcode(ILOAD), locals);
            locals += arg.getSize();
        }
        instruction.accept(method);
        method.visitInsn(output.getOpcode(IRETURN));
        method.visitMaxs(Math.max(locals, output.getSize()), locals);
        method.visitEnd();
    }
}
