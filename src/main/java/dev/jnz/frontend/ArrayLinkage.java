package dev.jnz.frontend;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.Arrays;

import static org.objectweb.asm.Opcodes.*;

/** 原类中的数组操作桥，保留 JVM 的边界、空值和元素类型检查。 */
final class ArrayLinkage {
    private static final Type OBJECT = Type.getType(Object.class);
    private static final Type INT = Type.INT_TYPE;
    private static final Type VOID = Type.VOID_TYPE;
    private static final Type[] ELEMENTS = {INT, Type.LONG_TYPE, Type.FLOAT_TYPE, Type.DOUBLE_TYPE,
            OBJECT, Type.BYTE_TYPE, Type.CHAR_TYPE, Type.SHORT_TYPE};
    // 对应 JVM NEWARRAY 的 atype 4 到 11。
    private static final String NEWARRAY_TYPES = "ZCFDBSIJ";

    private ArrayLinkage() {}

    static Linkage create(String name, AbstractInsnNode node) {
        int opcode = node.getOpcode();
        if (opcode >= IALOAD && opcode <= SALOAD) {
            Type element = ELEMENTS[opcode - IALOAD];
            Type array = opcode == BALOAD ? OBJECT : arrayOf(element);
            Type result = opcode >= BALOAD ? INT : element;
            return Linkage.nativeOperation(opcode, "array", arrayOf(element).getDescriptor(), result, array, INT);
        }
        if (opcode >= IASTORE && opcode <= SASTORE) {
            Type element = ELEMENTS[opcode - IASTORE];
            Type array = opcode == BASTORE ? OBJECT : arrayOf(element);
            Type value = opcode >= BASTORE ? INT : element;
            return Linkage.nativeOperation(opcode, "array", arrayOf(element).getDescriptor(), VOID, array, INT, value);
        }
        switch (opcode) {
            case NEWARRAY: {
                int type = ((IntInsnNode) node).operand;
                if (type < T_BOOLEAN || type > T_LONG) return null;
                Type array = Type.getType("[" + NEWARRAY_TYPES.charAt(type - T_BOOLEAN));
                return Linkage.nativeOperation(opcode, "array", array.getDescriptor(), array, INT);
            }
            case ANEWARRAY:
                return new Linkage(name, node, arrayOf(Type.getObjectType(((TypeInsnNode) node).desc)), INT);
            case MULTIANEWARRAY: {
                MultiANewArrayInsnNode array = (MultiANewArrayInsnNode) node;
                Type[] dimensions = new Type[array.dims];
                Arrays.fill(dimensions, INT);
                return new Linkage(name, node, Type.getType(array.desc), dimensions);
            }
            case ARRAYLENGTH:
                return Linkage.nativeOperation(opcode, "array", "Ljava/lang/Object;", INT, OBJECT);
            default:
                return null;
        }
    }

    private static Type arrayOf(Type element) {
        return Type.getType("[" + element.getDescriptor());
    }

}
