package dev.jnz.backend;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.objectweb.asm.Opcodes.*;

/** 标记 IR 的入口、分支目标和所有终止指令之后的位置。 */
final class BasicBlocks {
    private BasicBlocks() {}

    static List<Integer> leaders(int[] code) {
        int count = code.length / 3;
        TreeSet<Integer> leaders = new TreeSet<Integer>();
        leaders.add(0);
        leaders.add(count);
        for (int i = 0; i < count; i++) {
            int opcode = code[3*i];
            if (isJump(opcode)) leaders.add(code[3*i+1]);
            if (isJump(opcode) || isReturn(opcode)) leaders.add(i+1);
        }
        return new ArrayList<Integer>(leaders);
    }

    static boolean isJump(int opcode) {
        return (opcode >= IFEQ && opcode <= GOTO) || opcode == IFNULL || opcode == IFNONNULL;
    }

    static boolean isReturn(int opcode) {
        return (opcode >= IRETURN && opcode <= RETURN) || opcode == ATHROW;
    }
}
