package dev.jnz.backend;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.objectweb.asm.Opcodes.GOTO;

/** 使用显式入口与落空边，将基本块打散后重定位到 VM 指令号。 */
final class VmLayout {
    private VmLayout() {}

    static int[] shuffle(int[] code, SecureRandom random) {
        List<Integer> leaders = BasicBlocks.leaders(code);
        if (leaders.size() <= 2) return code;
        List<Integer> order = new ArrayList<Integer>(leaders.size()-1);
        for (int i = 0; i + 1 < leaders.size(); i++) order.add(i);
        Collections.shuffle(order, random);

        int count = code.length / 3;
        int[] positions = new int[count+1];
        int length = 1;
        for (int block : order) {
            int start = leaders.get(block), end = leaders.get(block+1);
            positions[start] = length;
            length += end - start;
            if (hasFallthrough(code, end)) length++;
        }
        positions[count] = length;
        int[] result = new int[length * 3];
        result[0] = GOTO;
        result[1] = positions[0];
        for (int block : order) {
            int start = leaders.get(block), end = leaders.get(block+1);
            int cursor = positions[start] * 3;
            System.arraycopy(code, start * 3, result, cursor, (end-start) * 3);
            for (int i = start; i < end; i++, cursor += 3) {
                if (BasicBlocks.isJump(result[cursor])) result[cursor+1] = positions[result[cursor+1]];
            }
            if (hasFallthrough(code, end)) {
                result[cursor] = GOTO;
                result[cursor+1] = positions[end];
            }
        }
        return result;
    }

    private static boolean hasFallthrough(int[] code, int end) {
        int last = code[(end-1)*3];
        return last != GOTO && !BasicBlocks.isReturn(last);
    }
}
